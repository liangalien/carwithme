package com.ikang.vm.carwithme

import com.ikang.vm.plugin.api.PluginApi
import com.ikang.vm.plugin.api.PluginChannelAuth
import com.ikang.vm.plugin.api.PluginFrame
import com.ikang.vm.plugin.api.PluginFrameCodec
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 插件 → 宿主的帧服务（loopback socket）。
 *
 * ## 为什么要有它
 * 宿主与插件是两个进程。Binder 只适合低频的 oneway 控制（状态、触摸、请求 IDR），
 * 视频帧必须走独立通道：Binder 的 1MB 事务上限与逐帧跨进程拷贝都撑不住 1080p30。
 * 这里用 `127.0.0.1` 上的本机 socket，线格式见 [PluginFrameCodec]。
 *
 * ## 线程模型（与 CarWith 的 VIDEO 读线程的关系）
 * - [submit] 在 VIDEO 读线程上同步调用，**只做入队**：socket 写一旦反压，会直接
 *   拖慢 CarWith 链路的读取，进而让手机侧攒积压。队列满时丢最旧帧（与宿主
 *   `TcpStreamServer` 的每客户端队列同一处置），丢帧后由宿主侧的解码器按
 *   "payload 不连续"自行触发重同步。
 * - 写线程独立，独占一个 socket 输出流；accept 线程只负责鉴权与换客户端。
 * - 没有客户端时**不排队**：一排就会把上一代客户端的残帧喂给下一代。
 *
 * ## 安全
 * 端口由系统随机分配，只通过 Binder 回传给已授权的宿主；连上后还要过 [PluginChannelAuth]
 * 的令牌校验。两者缺一，同机其它应用都拿不到画面。
 */
internal class FrameServer(
    private val token: String,
    private val onLog: (String) -> Unit,
    private val onClientAttached: () -> Unit,
    private val onStats: (String) -> Unit,
) {

    companion object {
        /** 队列容量按帧算：8 帧 ≈ 200~400ms 的 1080p 码流，够吸收一次 GC 抖动。 */
        private const val QUEUE_CAPACITY = 8

        /** 鉴权读超时：防止连上不发数据的客户端把 accept 线程钉住。 */
        private const val AUTH_TIMEOUT_MS = 3_000

        /** accept 线程在 stop() 后靠关闭 serverSocket 退出，这个值只是兜底轮询粒度。 */
        private const val ACCEPT_POLL_MS = 200L

        /** 写线程的空闲轮询粒度（也是 stop 后线程退出的最大延迟）。 */
        private const val WRITE_POLL_MS = 200L

        /** 统计回调间隔。 */
        private const val STATS_INTERVAL_MS = 1_000L

        /** socket 发送缓冲：一帧 1080p 只在 30~150KB，64KB 足够让每次 flush 只落一次写。 */
        private const val SEND_BUFFER_BYTES = 64 * 1024
    }

    private val queue = ArrayBlockingQueue<PluginFrame>(QUEUE_CAPACITY)
    private val sentFrames = AtomicLong()
    private val sentBytes = AtomicLong()
    private val droppedFrames = AtomicLong()

    @Volatile
    private var running = false

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var writerThread: Thread? = null

    @Volatile
    private var client: Socket? = null

    /** 实际监听端口；0 = 未启动。 */
    val port: Int get() = serverSocket?.localPort ?: 0

    fun start(): Boolean {
        val server = try {
            ServerSocket().apply {
                // 只绑回环：不暴露到局域网，也不需要 INTERNET 之外的能力。
                bind(InetSocketAddress(PluginApi.LOOPBACK_HOST, 0), 2)
            }
        } catch (e: IOException) {
            onLog("帧服务监听失败：${e.message}")
            return false
        }
        serverSocket = server
        running = true
        acceptThread = Thread({ acceptLoop(server) }, "CarWithPlugin-accept").apply {
            isDaemon = true
            start()
        }
        writerThread = Thread({ writeLoop() }, "CarWithPlugin-frame").apply {
            isDaemon = true
            start()
        }
        onLog("帧服务已监听 ${PluginApi.LOOPBACK_HOST}:${server.localPort}")
        return true
    }

    /**
     * 帧入口：**在 CarWith 的 VIDEO 读线程上同步调用**，不得阻塞。
     * 队列满时丢最旧的一帧（丢帧由宿主解码器按不连续自行重同步）。
     */
    fun submit(frame: PluginFrame) {
        if (client == null) {
            // 没有接收方（宿主还没连上来 / 已经断开）：直接丢，不占用队列。
            droppedFrames.incrementAndGet()
            return
        }
        if (!queue.offer(frame)) {
            queue.poll()
            queue.offer(frame)
            droppedFrames.incrementAndGet()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        client?.let { runCatching { it.close() } }
        client = null
        acceptThread?.let { runCatching { it.join(ACCEPT_POLL_MS * 3) } }
        writerThread?.let { runCatching { it.join(WRITE_POLL_MS * 4) } }
        acceptThread = null
        writerThread = null
        queue.clear()
        onLog("帧服务已停止（累计 ${sentFrames.get()} 帧，丢弃 ${droppedFrames.get()}）")
    }

    // ---------------------------------------------------------------- accept

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (running) onLog("帧服务 accept 结束：${e.message}")
                break
            }
            if (!authenticate(socket)) {
                runCatching { socket.close() }
                onLog("帧通道接入被拒（令牌不符）：${socket.inetAddress?.hostAddress}")
                continue
            }
            // 新客户端上任：上一代残帧不能喂给它。
            queue.clear()
            val previous = client
            client = socket
            runCatching { previous?.close() }
            onLog("帧通道客户端已接入")
            // 让 CarWith 立刻产一帧 IDR：宿主刚接上，缓存里的关键帧可能已经过期。
            runCatching { onClientAttached() }
        }
    }

    private fun authenticate(socket: Socket): Boolean = try {
        socket.tcpNoDelay = true
        socket.soTimeout = AUTH_TIMEOUT_MS
        val accepted = PluginChannelAuth.read(socket.getInputStream()) == token
        // 鉴权结束后恢复正常阻塞读（帧通道本身没有读方向，这个值只影响后续误用）。
        socket.soTimeout = 0
        accepted
    } catch (e: IOException) {
        false
    }

    // ---------------------------------------------------------------- 写线程

    private fun writeLoop() {
        var statsAt = System.currentTimeMillis()
        var statFrames = 0L
        var statBytes = 0L

        while (running) {
            val socket = client
            if (socket == null) {
                queue.clear()
                sleepQuietly(WRITE_POLL_MS)
                continue
            }
            try {
                BufferedOutputStream(socket.getOutputStream(), SEND_BUFFER_BYTES).use { out ->
                    while (running && client === socket) {
                        val frame = queue.poll(WRITE_POLL_MS, TimeUnit.MILLISECONDS)
                        if (frame != null) {
                            PluginFrameCodec.write(out, frame)
                            // BufferedOutputStream 必须每帧 flush，否则帧会攒到缓冲满才发。
                            out.flush()
                            sentFrames.incrementAndGet()
                            sentBytes.addAndGet(frame.data.size.toLong())
                            statFrames++
                            statBytes += frame.data.size
                        }
                        val now = System.currentTimeMillis()
                        if (now - statsAt >= STATS_INTERVAL_MS) {
                            reportStats(now - statsAt, statFrames, statBytes)
                            statsAt = now
                            statFrames = 0
                            statBytes = 0
                        }
                    }
                }
            } catch (e: IOException) {
                if (running && client === socket) onLog("帧通道写结束：${e.message}")
            } catch (e: IllegalArgumentException) {
                // 单帧超过协议上限：丢掉这一帧，但不断整条链路。
                onLog("单帧超限已丢弃：${e.message}")
            } finally {
                if (client === socket) client = null
                runCatching { socket.close() }
            }
        }
    }

    private fun reportStats(elapsedMs: Long, frames: Long, bytes: Long) {
        val seconds = elapsedMs.coerceAtLeast(1) / 1_000.0
        val text = "帧 %.1f/s，%.2f MB/s，队列丢弃 %d，累计 %d 帧".format(
            frames / seconds,
            bytes / seconds / (1024.0 * 1024.0),
            droppedFrames.get(),
            sentFrames.get(),
        )
        runCatching { onStats(text) }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
