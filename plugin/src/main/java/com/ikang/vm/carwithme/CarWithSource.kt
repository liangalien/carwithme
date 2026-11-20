/*
 * CarWith 协议适配层 —— 派生自 cardisplay2（GNU GPL v3.0），已按本项目需要修改。
 * 许可证全文见模块根 LICENSE，并随 APK 打包在 assets/GPL-3.0.txt。
 * 本模块整体按 GPL-3.0 分发；与宿主（app）之间只通过 :plugin-api 的 AIDL 契约往来，
 * 不共享实现代码、不链接、不 bundled 分发。
 */
package com.ikang.vm.carwithme

import android.os.SystemClock
import android.util.Log
import com.ikang.vm.plugin.api.PluginFrame
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarWith（无特权模式）的帧源：主动拨本机七路 → 握手 → 解密视频 → 产出 [PluginFrame]。
 *
 * 角色与 cardisplay `rust-core/src/carlife.rs` 的 `run_supervisor` 一致：
 * **本 App 是 HU（盒子），CarWith 在本机回环端口上监听，我们逐路拨过去**。
 *
 * 七路必须成套（CMD 7240 / VIDEO 8240 / MEDIA 9240 / TTS 9241 / VR 9242 / CTRL 9340 /
 * DATA 9440）：缺一路就整轮拆掉、1s 后重试 —— CarWith 的启动流程以"七路都被拨过"为信号，
 * 只连一半会把它卡在启动画面。
 *
 * 视频链路（这是整条推流的关键）：
 * ```
 * 8240 上的 [len:4][ts:4][type:4][payload]
 *   → type == VIDEO_DATA(0x20001)
 *   → 握手完成（session.started）前一律丢弃
 *   → AES-ECB/PKCS7 解密
 *   → [CarWithVideoParser] 跨包组帧（CarWith 的包边界≠AU 边界）
 *   → H.264 Annex-B 访问单元（IDR 自带 SPS/PPS）   ← 不转码、不渲染
 *   → onFrame(PluginFrame) → 插件帧服务 → loopback → 宿主 StreamHub → 15456 → 仪表
 * ```
 *
 * 三条保命机制（缺了就是"握手全过、画面一直黑"）：
 * - VIDEO 通道**每秒**发一条 `VIDEO_HEARTBEAT(0x20002)`，否则 CarWith 认为无人接收、编码器空转；
 * - 新客户端接入时缓存的关键帧可能已过期（15456 只复用 1.5s 内的 IDR），此时发
 *   `VIDEO_ENCODER_RESET(0x1800B)`（空 payload、不加密）让手机重新产一帧（见 [requestKeyframe]）；
 * - 静止补帧看门狗：CarWith 是 damage-driven 的，长时间无帧时逐级升级到**重开编码器**，
 *   否则预览就停在首帧（见 [idleWatchdogLoop]）；
 * - 解密/组帧一旦失败就**整轮重连**：那种状态下继续转发只会把错位的字节当成画面。
 *
 * 触摸（可选能力，见 [sendTouch]）：走另一条已经连着的 **CTRL 9340**，与 CMD 同一套
 * 8 字节帧 + 同一把 AES 会话密钥，只是消息 ID 不同（0x68002 按下 / 0x68004 移动 /
 * 0x68003 抬起，payload 是 protobuf 的 x/y）。这条路上只有 HU→MD 方向，本端不读回包，
 * 所以可以直接与 [startDrain] 的读线程共存。
 *
 * 帧源自带重连：连不上/被对端关闭都只影响本轮，supervisor 线程按 [RETRY_DELAY_MS] 重来，
 * 因此宿主（StreamHub）不需要为"CarWith 还没启动"做退避。
 *
 * @param host CarWith 监听地址（本机回环）
 * @param width/height 期望画布（用户设置值，[CarWithSession.canvasFor] 只做偶数对齐与上下限收敛）
 * @param fps 期望帧率（上限 30）
 * @param btAddress/btName `HU_INFO` 里 required 的蓝牙字段（蓝牙歌词场景下是真值，否则占位即可）
 * @param onFrame 解出的 Annex-B 帧回调（在 supervisor 的 VIDEO 读线程上同步调用，重载为
 *        socket 写入时必须非阻塞 —— 插件侧由 FrameServer 的有界队列承担）。
 *        类型是契约层的 [PluginFrame]：本模块不依赖宿主任何代码。
 * @param onLog 可读日志（默认写 logcat）
 * @param onLinked 握手完成（`VIDEO_ENCODER_START` 已发出）时回调一次，带上手机在 `INIT_DONE`
 *        里回报的实际编码画布（宽, 高）；供控制台 CarWith 面板显示“已连接”、也供屏列表标注 CarWith 屏尺寸
 * @param onDisconnected 本轮会话结束（断开/被对端关闭/出错重连）时回调，供控制台把“已连接”收回去
 */
class CarWithSource(
    private val host: String = DEFAULT_HOST,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val fps: Int = 30,
    private val btAddress: String = DEFAULT_BT_ADDRESS,
    private val btName: String = DEFAULT_BT_NAME,
    private val onFrame: (PluginFrame) -> Unit,
    private val onLog: (String) -> Unit = { Log.i(TAG, it) },
    private val onLinked: (Int, Int) -> Unit = { _, _ -> },
    private val onDisconnected: () -> Unit = {},
) {

    companion object {
        private const val TAG = "CarWithSource"

        /** CarWith 只在本机回环上监听。 */
        const val DEFAULT_HOST = "127.0.0.1"

        /** `HU_INFO` 里必填的蓝牙地址/名称占位值。 */
        const val DEFAULT_BT_ADDRESS = "00:00:00:00:00:00"
        const val DEFAULT_BT_NAME = "HyperCar"

        /** 单路拨号超时。 */
        private const val CONNECT_TIMEOUT_MS = 3_000

        /** 整轮失败后的重试间隔（CarWith 未启动时就是 1s 一次地试）。 */
        private const val RETRY_DELAY_MS = 1_000L

        /** VIDEO 心跳间隔：CarWith 以"1s 内有没有心跳"判断对端还在不在。 */
        private const val HEARTBEAT_INTERVAL_MS = 1_000L

        /** 关键帧请求的最小间隔（与 Rust 侧 `requestKeyFrame` 的节流一致）。 */
        private const val KEYFRAME_REQUEST_MIN_INTERVAL_MS = 1_000L

        /**
         * 静止补帧：这么久没有新帧就把"此刻的画面"要过来。
         *
         * CarWith 的编码器是 damage-driven 的：手机侧虚拟屏没有变化时它**一帧也不编**。
         * 更麻烦的是首帧往往只是它的启动画面，手机随后切到桌面也不会补帧，于是预览就
         * "卡在第一帧，点一下（产生 damage）才会动"。
         */
        private const val IDLE_KEYFRAME_AFTER_MS = 1_200L

        /** 静止看门狗的检查粒度。 */
        private const val IDLE_CHECK_INTERVAL_MS = 400L

        /** 请 IDR 发出后这么久仍无新帧，就升级为"重开编码器"（见 [restartEncoder]）。 */
        private const val REFRESH_ESCALATE_MS = 700L

        /**
         * 静止期补帧的整轮周期。
         *
         * 重开编码器要让手机重建 MediaCodec（约 130ms），不能一秒好几次；2s 足够让画面
         * 跟上现实，也把静止时的额外码率/功耗定在可接受的范围。
         */
        private const val IDLE_REFRESH_INTERVAL_MS = 2_000L

        /**
         * VIDEO 读线程活性上限：这么久一个字节都没读进来就判定读线程卡死。
         *
         * 手机侧是"一直在推"的（实测静止补帧期间车机编码器仍以 ~18fps 出帧，socket
         * Recv-Q 一路堆到 1MB 以上），所以"冰住的读线程"与"没有画面"必须分开看：
         * 前者只能靠拆掉本轮重建，后者才该发补帧请求。
         */
        private const val VIDEO_READ_STALL_MS = 6_000L

        /** CMD 写卡在 socket 上的判定上限（写线程被 socket 反压钉住时整轮收尾都会作废）。 */
        private const val CMD_WRITE_STALL_MS = 3_000L

        /** 卡死诊断（线程栈）的最小间隔，避免反复打爆 logcat。 */
        private const val STALL_DUMP_INTERVAL_MS = 10_000L

        /** 收尾时等「暂停编码器」真正写出去的上限。 */
        private const val PAUSE_SEND_WAIT_MS = 200L

        /** 独立体检线程的节拍。 */
        private const val MONITOR_INTERVAL_MS = 4_000L

        /** 体检线程判定"读停住了"并打线程栈的门槛（比看门狗的拆会话门槛宽一些）。 */
        private const val MONITOR_DUMP_AFTER_MS = 8_000L

        /** 接收缓冲上限：超过即视为字节流错位，整块丢弃重同步。 */
        private const val MAX_BUFFER_BYTES = 8 * 1024 * 1024

        /** `stop()` 等待线程收尾的上限。 */
        private const val STOP_JOIN_MS = 1_500L

        private const val READ_BUFFER = 256 * 1024

        /** 同一条失败原因最多每 10s 打一次，避免 CarWith 没开时刷屏。 */
        private const val LOG_THROTTLE_MS = 10_000L

        /** [CarLifeProtocol.ALL_PORTS] 里 CTRL 9340 的下标：触摸只走这一路。 */
        private const val CTRL_INDEX = 5

        /** CTRL 写队列容量：拖动时允许的积压上限，满了丢最旧（对齐 Rust 侧 QueuedControl 的 256）。 */
        private const val CTRL_QUEUE_CAPACITY = 256

        /** 写线程的空闲轮询粒度（也是 stop 后线程退出的最大延迟）。 */
        private const val CTRL_QUEUE_POLL_MS = 200L

        /** 会话收尾补“抬起”时允许等待写线程的上限。 */
        private const val RELEASE_TOUCH_WAIT_MS = 300L
    }

    @Volatile
    private var running = false

    private var supervisor: Thread? = null

    /** 本轮已建立的全部 socket（stop/异常时统一关闭）。 */
    private val sockets = arrayOfNulls<Socket>(CarLifeProtocol.ALL_PORTS.size)

    /** 本轮 CMD 写入口（[requestKeyframe] 从别的线程用它发 0x1800B）。 */
    @Volatile
    private var keyframeWriter: CmdWriter? = null

    /** 上次向手机要 IDR 的时刻（节流用）。 */
    @Volatile
    private var lastKeyframeRequestAtMs = 0L

    /** 有待发的 IDR 请求：握手未完成 / 节流窗口内时挂起，条件一满足立刻补发。 */
    @Volatile
    private var keyframePending = false

    /** 上一帧产出时刻（[SystemClock.elapsedRealtime]）；0 = 本轮还没出过帧。 */
    @Volatile
    private var lastFrameAtMs = 0L

    /** 静止补帧看门狗。 */
    private var idleWatchdog: Thread? = null

    /** 独立体检线程：只读标志位 + 打日志，不参与任何协议逻辑。 */
    private var monitor: Thread? = null

    /** 上一次发补帧请求（重置或重开编码器）的时刻，用于 [IDLE_REFRESH_INTERVAL_MS] 限流。 */
    @Volatile
    private var lastRefreshAtMs = 0L

    /** 本轮 VIDEO socket：看门狗判定读线程卡死时要能强拆它。 */
    @Volatile
    private var videoSocket: Socket? = null

    /** 上一次从 VIDEO 读到字节的时刻；0 = 本轮还没读过。 */
    @Volatile
    private var lastVideoReadAtMs = 0L

    /** 上一次打全线程栈的时刻（[dumpCarWithThreads] 限流用）。 */
    @Volatile
    private var lastStallDumpAtMs = 0L

    // ---- VIDEO 通道诊断计数（只看不回，用于区分"手机没发"与"发了我们没用"）----
    @Volatile
    private var videoBytes = 0L
    @Volatile
    private var videoMediaFrames = 0L
    @Volatile
    private var videoDataFrames = 0L
    @Volatile
    private var videoDroppedNotStarted = 0L
    @Volatile
    private var videoAus = 0L

    /** 是否已排了一个延迟补发任务（避免同一窗口内叠多个）。 */
    @Volatile
    private var keyframeFlushScheduled = false

    /** 延迟补发 IDR 的单线程调度器：只在“请求撞上节流窗口”时用一次。 */
    private val keyframeScheduler = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "CarWithSource-keyframe").apply { isDaemon = true }
    }

    /** 本轮 CTRL 写入口（触摸）；只在会话存活期间非空。 */
    @Volatile
    private var ctrlWriter: CmdWriter? = null

    /** 本轮握手状态机（用来判 [CarWithSession.started]）；与 [ctrlWriter] 同生共死。 */
    @Volatile
    private var activeSession: CarWithSession? = null

    /** 手机在 `INIT_DONE` 里回报的实际编码画布（触摸坐标系）；0 = 未知。 */
    @Volatile
    private var canvasWidth = 0
    @Volatile
    private var canvasHeight = 0

    /** 触摸手势状态：协议是单指，同一时刻只有一根手指。 */
    private val touchLock = Any()
    private var touchActive = false
    private var touchLastX = 0
    private var touchLastY = 0

    /**
     * CTRL 写队列（单线程 + 有界）。
     *
     * 为什么必须离开调用线程：触摸从 UI 线程进来，而 Android 的 BlockGuard 对主线程上的
     * socket 写直接抛 [android.os.NetworkOnMainThreadException]（不是 StrictMode 能关的开关）。
     * 之前“UI 线程直接写 CTRL”的写法每次都在 `write` 第一行失败，表现为“摸了没反应”。
     *
     * 为什么是单线程：触摸是有序状态机（DOWN 之后的 MOVE/UP 才有效），并发写会让手机侧
     * 收到“MOVE 先于 DOWN”这种序列。
     */
    private val ctrlTasks = ArrayBlockingQueue<() -> Unit>(CTRL_QUEUE_CAPACITY)
    private val ctrlLock = Any()
    @Volatile
    private var ctrlWorker: Thread? = null

    /** 上次交给 15456 的参数集：内容变了才重发（接收端自己会缓存）。 */
    private var lastConfig: ByteArray? = null

    private var lastWarnAtMs = 0L

    /** 本轮会话里已记录过的「未处理命令」类型（同类型只记一次，避免刷屏）。 */
    private val loggedUnknownCmds = HashSet<Int>()

    /** 本轮会话里已记录过的「VIDEO 通道非视频帧」类型（同类型只记一次）。 */
    private val loggedMediaTypes = HashSet<Int>()

    fun start() {
        if (running) return
        running = true
        startCtrlWorker()
        supervisor = Thread({ supervise() }, "CarWithSource").apply {
            isDaemon = true
            start()
        }
        idleWatchdog = Thread({ idleWatchdogLoop() }, "CarWithSource-idle").apply {
            isDaemon = true
            start()
        }
        monitor = Thread({ monitorLoop() }, "CarWithSource-monitor").apply {
            isDaemon = true
            start()
        }
        onLog("CarWith 帧源启动：拨号 $host:${CarLifeProtocol.ALL_PORTS.joinToString(",")}")
    }

    fun isRunning(): Boolean = running

    /**
     * 向手机要一帧 IDR（CMD `VIDEO_ENCODER_RESET(0x1800B)`，1s 节流）。
     *
     * 15456 侧的新客户端接入时，缓存的 IDR 可能已经过期（[TcpStreamServer] 只复用 1.5s
     * 内的关键帧），而 CarWith 的自然 GOP 不归我们控。这里发一条重置命令让手机立即产一帧
     * IDR（等价于 Rust 侧的 `requestKeyFrame` / mcarplay 的 `CarLifeSession.requestKeyFrame`）。
     *
     * ## 为什么请求不能“丢就丢了”
     * 曾经这里撞上节流窗口就直接 `return true`（不发）；可静态画面下手机**只在被请求时**才
     * 产帧，被吞掉的这一次就是新消费者（刚接面的预览页）唯一能拿到的那帧 —— 表现为
     * “连上了、卡在第一帧，点一下就动”（点一下产生 damage，手机才重新编码）。
     * 现在改为：请求一律记成 pending，握手没完成就等 `VIDEO_ENCODER_START`，在节流窗口内
     * 就排一次延迟补发（见 [flushKeyframeRequest]）。
     *
     * @return false 表示帧源已停（[start] 未调用或已 [stop]）；**不**表示没会话 ——
     *         没有会话时请求会被挂起，等握手完成后补发
     */
    fun requestKeyframe(): Boolean {
        if (!running) return false
        // 没有会话也**不能吞**：记成 pending，等握手完成（cmdLoop 发 VIDEO_ENCODER_START）
        // 再一次性补发 —— 宿主预览页接面时请的那一帧正好落在这个窗口里。
        keyframePending = true
        flushKeyframeRequest()
        return true
    }

    /**
     * 把挂着的 IDR 请求真正发出去。
     *
     * - 没有会话 / 握手未完成：什么都不做 —— 此时写 0x1800B 手机会直接丢弃；请求留在
     *   [keyframePending] 上，等 [cmdLoop] 发出 `VIDEO_ENCODER_START` 后再冲一次；
     * - 还在节流窗口内：排一次延迟补发，不能把请求吞掉。
     */
    private fun flushKeyframeRequest() {
        if (!keyframePending) return
        val writer = keyframeWriter ?: return
        val session = activeSession ?: return
        if (!session.started) return
        val now = SystemClock.elapsedRealtime()
        val waitMs = lastKeyframeRequestAtMs + KEYFRAME_REQUEST_MIN_INTERVAL_MS - now
        if (waitMs > 0) {
            scheduleKeyframeFlush(waitMs)
            return
        }
        keyframePending = false
        // 这条消息在手机上要求 payload 为空且**不加密**：走 send() 会被 AES/PKCS7 填成
        // 16 字节密文，手机直接忽略 —— 这正是"请 IDR 一直没反应"的原因之一。
        runCatching { writer.sendEmptyPlain(CarLifeProtocol.CMD_VIDEO_ENCODER_RESET) }
        lastKeyframeRequestAtMs = now
    }

    /** 在节流窗口结束后补发一次（只排一个）。 */
    private fun scheduleKeyframeFlush(delayMs: Long) {
        if (keyframeFlushScheduled) return
        keyframeFlushScheduled = true
        val submitted = runCatching {
            keyframeScheduler.schedule(
                {
                    keyframeFlushScheduled = false
                    flushKeyframeRequest()
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        }.isSuccess
        if (!submitted) keyframeFlushScheduled = false
    }

    // ---------------------------------------------------------------- 触摸

    /**
     * 向手机侧 CarWith 虚拟屏注入一次触摸（CTRL 9340）。
     *
     * 协议契约（参照 cardisplay `TouchGesture::accept`，已在我们这条七路链路上）：
     * - 动作翻成三个不同消息 ID：`0x68002` 按下 / `0x68004` 移动 / `0x68003` 抬起；
     * - payload 是 protobuf `1:x 2:y`，**实际编码画布像素**（[CarWithSession.actualWidth]）；
     * - 未按下时的移动/抬起直接丢弃；按下中再收到按下按移动发；抬起带最后一点；
     * - 必须已发过 `VIDEO_ENCODER_START`（[CarWithSession.started]），之前写进去会被手机丢弃。
     *
     * 本方法从 UI 线程调用：只做前置检查与入队，socket 写由 [ctrlWorker] 串行完成
     * （主线程直接写 socket 会被 BlockGuard 判成 [android.os.NetworkOnMainThreadException]）；
     * 写失败视为本轮会话结束，交给 supervisor 重连，这里**不做重试**。
     *
     * @return false 表示没发（未握手完/无会话/参数非法/写失败），调用方不要把它当致命错误
     */
    fun sendTouch(action: Int, x: Int, y: Int): Boolean {
        val writer = ctrlWriter
        if (writer == null) {
            warnThrottled("触摸未发送：CTRL 通道未接通（CarWith 未连上）")
            return false
        }
        if (activeSession?.started != true) {
            warnThrottled("触摸未发送：手机侧编码器还未启动（VIDEO_ENCODER_START 未收到）")
            return false
        }
        val w = canvasWidth
        val h = canvasHeight
        if (w < 2 || h < 2) {
            warnThrottled("触摸未发送：画布尺寸未知（${w}x$h）")
            return false
        }
        return enqueueCtrl { writeTouch(writer, action, x, y, w, h) }
    }

    /** 真正写触摸帧：只在 [ctrlWorker] 线程上执行，状态机与网关都在这里。 */
    private fun writeTouch(writer: CmdWriter, action: Int, x: Int, y: Int, w: Int, h: Int) {
        synchronized(touchLock) {
            val kind = when (action) {
                CarLifeProtocol.TOUCH_DOWN -> if (touchActive) {
                    CarLifeProtocol.CTRL_TOUCH_MOVE
                } else {
                    touchActive = true
                    CarLifeProtocol.CTRL_TOUCH_DOWN
                }
                CarLifeProtocol.TOUCH_MOVE -> {
                    if (!touchActive) return
                    CarLifeProtocol.CTRL_TOUCH_MOVE
                }
                CarLifeProtocol.TOUCH_UP -> {
                    if (!touchActive) return
                    touchActive = false
                    CarLifeProtocol.CTRL_TOUCH_UP
                }
                else -> return
            }
            // 钳到画布内：预览侧按显示区域等比换算，极端手势（越界/负值）在这里收敛。
            val px = x.coerceIn(0, w - 1)
            val py = y.coerceIn(0, h - 1)
            if (kind != CarLifeProtocol.CTRL_TOUCH_UP) {
                touchLastX = px
                touchLastY = py
            }
            runCatching {
                writer.send(kind, CarLifeProto.touchPayload(touchLastX, touchLastY))
            }.onFailure {
                // 写 CTRL 失败是「触摸完全无反应」最直接的分界点：把异常类型与堆栈完整留下，
                // 只打 message 时 IOException/NPE 常常是 null，事后无法定位。
                Log.e(TAG, "sendTouch 写 CTRL 失败 kind=0x${kind.toString(16)}", it)
                warnThrottled("触摸写 CTRL 失败：${it.javaClass.simpleName}(${it.message})")
            }
        }
    }

    /** 入队一次 CTRL 写；false 只表示队列满被丢，调用方不必当致命错误。 */
    private fun enqueueCtrl(task: () -> Unit): Boolean {
        startCtrlWorker()
        if (ctrlTasks.offer(task)) return true
        // 拖动风暴：丢掉最旧的一条给新事件让路（DOWN/UP 总是后到的，不会被丢）
        ctrlTasks.poll()
        if (ctrlTasks.offer(task)) return true
        warnThrottled("CTRL 写队列已满，触摸事件被丢弃")
        return false
    }

    private fun startCtrlWorker() {
        synchronized(ctrlLock) {
            if (ctrlWorker?.isAlive == true) return
            ctrlWorker = Thread({ ctrlLoop() }, "CarWithSource-ctrl").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun ctrlLoop() {
        while (true) {
            val task = try {
                ctrlTasks.poll(CTRL_QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (task != null) {
                runCatching { task() }
                continue
            }
            synchronized(ctrlLock) {
                // 只有“不再运行且队列已空”才退出；清引用与 [startCtrlWorker] 的检查同锁，
                // 避开“线程已在退出但 isAlive 仍为 true”时重启后队列无人消费的窗口。
                if (!running && ctrlTasks.isEmpty()) {
                    ctrlWorker = null
                    return
                }
            }
        }
    }

    /**
     * 硬键（方向盘/返回等）：与触摸同一条 CTRL 通道，消息 ID 为
     * [CarLifeProtocol.CTRL_CAR_HARD_KEY_CODE]，payload 是 protobuf `1:keycode`。
     *
     * 与 Rust 参考实现 `queue_hard_key` 一致：只发一条，不带按下/抬起两个状态。
     *
     * @return false 表示没发（未握手完/无会话/写失败），调用方不要把它当致命错误
     */
    fun sendHardKey(code: Int): Boolean {
        val writer = ctrlWriter
        if (writer == null) {
            warnThrottled("硬键未发送：CTRL 通道未接通")
            return false
        }
        if (activeSession?.started != true) {
            warnThrottled("硬键未发送：手机侧编码器还未启动")
            return false
        }
        return enqueueCtrl {
            runCatching {
                writer.send(CarLifeProtocol.CTRL_CAR_HARD_KEY_CODE, CarLifeProto.varint(1, code.toLong()))
            }.onFailure {
                Log.e(TAG, "sendHardKey 写 CTRL 失败 code=$code", it)
                warnThrottled("硬键写 CTRL 失败：${it.javaClass.simpleName}(${it.message})")
            }
        }
    }

    /**
     * 手指还按着就把连接拆了，手机侧会留下"一直按下"的粘滞触摸；会话结束前补一帧抬起。
     * 必须在关闭 socket 之前调用（[runSession] 的 finally 第一件事）。
     */
    private fun releaseTouch() {
        val writer = ctrlWriter ?: return
        val done = CountDownLatch(1)
        val queued = enqueueCtrl {
            try {
                synchronized(touchLock) {
                    if (!touchActive) return@synchronized
                    touchActive = false
                    runCatching {
                        writer.send(
                            CarLifeProtocol.CTRL_TOUCH_UP,
                            CarLifeProto.touchPayload(touchLastX, touchLastY),
                        )
                    }
                }
            } finally {
                done.countDown()
            }
        }
        // 必须在 closeAll 之前把 UP 真正写出去；等待有界，写不出来也不能阻塞拆会话。
        if (queued) done.await(RELEASE_TOUCH_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        if (!running && supervisor == null) return
        running = false
        closeAll()
        supervisor?.let { thread ->
            runCatching { thread.join(STOP_JOIN_MS) }
        }
        supervisor = null
        idleWatchdog?.let { thread ->
            runCatching { thread.interrupt() }
            runCatching { thread.join(STOP_JOIN_MS) }
        }
        idleWatchdog = null
        monitor?.let { thread ->
            runCatching { thread.interrupt() }
            runCatching { thread.join(STOP_JOIN_MS) }
        }
        monitor = null
        keyframeScheduler.shutdownNow()
        onLog("CarWith 帧源已停止")
    }

    // ---------------------------------------------------------------- supervisor

    private fun supervise() {
        while (running) {
            val channels = dialAll()
            if (channels == null) {
                if (!sleepWhileRunning(RETRY_DELAY_MS)) break
                continue
            }
            try {
                runSession(channels)
            } catch (t: Throwable) {
                if (running) {
                    onLog("CarWith 会话中断：${t.message}")
                    Log.d(TAG, "session aborted", t)
                }
            } finally {
                closeAll()
                // 一轮会话结束：握手状态作废（supervisor 随后会重连，连上会再回调 onLinked）。
                onDisconnected()
            }
            if (!sleepWhileRunning(RETRY_DELAY_MS)) break
        }
        running = false
    }

    /** 依次拨七路；任一路失败则关掉已连的、返回 null（下一轮整体重来）。 */
    private fun dialAll(): List<Socket>? {
        val opened = ArrayList<Socket>(CarLifeProtocol.ALL_PORTS.size)
        for (port in CarLifeProtocol.ALL_PORTS) {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                opened += socket
            } catch (e: IOException) {
                opened.forEach { runCatching { it.close() } }
                warnThrottled("连接 $host:$port 失败（CarWith 未启动或虚拟屏未开）：${e.message}")
                return null
            }
        }
        synchronized(sockets) {
            for (index in opened.indices) sockets[index] = opened[index]
        }
        onLog("CarWith 七路已接通（CMD/VIDEO/MEDIA/TTS/VR/CTRL/DATA）")
        return opened
    }

    private fun runSession(channels: List<Socket>) {
        val cmd = channels[0]
        val video = channels[1]
        val session = CarWithSession(width, height, fps, btAddress, btName)
        val (requestedW, requestedH) = session.requestedCanvas
        onLog("CarWith 握手开始：请求画布 ${requestedW}x$requestedH @${fps.coerceIn(1, 30)}fps")

        val cmdWriter = CmdWriter(cmd.getOutputStream(), session)
        val firstFrame = AtomicBoolean(false)
        val parser = CarWithVideoParser()
        // 15456 侧要 IDR 时走这个写口（0x1800B，与 Rust 侧 requestKeyFrame 同一路径）。
        keyframeWriter = cmdWriter
        // 触摸走另一条已经连着的 CTRL：写口与会话同生共死，读方向由 startDrain 收走。
        ctrlWriter = CmdWriter(channels[CTRL_INDEX].getOutputStream(), session)
        activeSession = session
        canvasWidth = 0
        canvasHeight = 0
        lastConfig = null
        lastFrameAtMs = 0L
        lastRefreshAtMs = 0L
        lastVideoReadAtMs = 0L
        videoSocket = video
        synchronized(loggedUnknownCmds) { loggedUnknownCmds.clear() }

        // 1) 版本声明必须是 CMD 通道上的第一条消息。
        cmdWriter.send(CarLifeProtocol.CMD_HU_PROTOCOL_VERSION, CarLifeProto.versionPayload(
            CarLifeProtocol.PROTOCOL_MAJOR, CarLifeProtocol.PROTOCOL_MINOR
        ))

        // 2) VIDEO 心跳：每秒一条，独立线程（读线程会长期阻塞在 read 上）。
        val heartbeat = startHeartbeat(video)

        // 3) 其余五路只 drain：CarWith 会往里推音频/语音识别/控制/数据，读慢会反压整条链路。
        val drains = channels.drop(2).map { startDrain(it) }

        val cmdThread = Thread({ cmdLoop(session, cmd, cmdWriter) }, "CarWithSource-cmd").apply {
            isDaemon = true
            start()
        }

        try {
            // 本线程即 VIDEO 读线程：阻塞到连接结束，帧在回调里直接交给推流服务。
            videoLoop(session, video, parser, firstFrame)
        } finally {
            // 手指还按着就断线会在手机侧留下粘滞触摸：先补一帧抬起，再拆连接。
            releaseTouch()
            keyframeWriter = null
            ctrlWriter = null
            activeSession = null
            // 本轮没兑现的 IDR 请求不能带到下一轮：新会话自己会在握手完成时重请。
            keyframePending = false
            canvasWidth = 0
            canvasHeight = 0
            // 顺序要紧：先通知手机停编码器，再拆连接，避免 CarWith 侧留下"假活"的编码器。
            // 但这条写必须**有界**：CMD 写一旦卡在 socket 上（对端不读，或 CmdWriter 的锁正被
            // 另一个卡住的写持有），直接写会把整轮收尾、连带 closeAll() 一起钉死 —— 现场就是
            // "视频 socket 一直 ESTAB、对端把 Recv-Q 灌满、读线程却再也不动"。
            sendPauseBestEffort(cmdWriter)
            videoSocket = null
            closeAll()
            heartbeat.interrupt()
            drains.forEach { it.interrupt() }
            runCatching { cmdThread.join(500) }
        }
    }

    // ---------------------------------------------------------------- CMD 通道

    /** CMD 读循环：解帧 → 解密 → 交给状态机 → 把动作加密写回。 */
    private fun cmdLoop(session: CarWithSession, socket: Socket, writer: CmdWriter) {
        val buffer = CarLifeProtocol.FrameBuffer()
        val chunk = ByteArray(16 * 1024)
        try {
            val input = socket.getInputStream()
            while (running && !socket.isClosed) {
                val read = input.read(chunk)
                if (read <= 0) throw IOException("CarWith CMD 通道 EOF")
                buffer.append(chunk, 0, read)
                while (running) {
                    val frame = buffer.peekCmd() ?: break
                    buffer.consume(frame.consumed)
                    val body = session.decrypt(frame.payload)
                    logIncomingCmd(frame.type, body)
                    for (action in session.receive(frame.type, body)) {
                        writer.send(action.type, action.payload)
                        when (action.type) {
                            CarLifeProtocol.CMD_VIDEO_ENCODER_INIT -> onLog(
                                "CarWith 请求开编码器：" +
                                    "${firstInt(action.payload, 1)}x${firstInt(action.payload, 2)}"
                            )
                            CarLifeProtocol.CMD_VIDEO_ENCODER_START -> {
                                // 触摸坐标系 = 实际编码画布（不是请求值，手机会对齐到自己的屏）。
                                canvasWidth = session.actualWidth
                                canvasHeight = session.actualHeight
                                onLog(
                                    "CarWith 握手完成：已发 VIDEO_ENCODER_START，" +
                                        "实际分辨率 ${session.actualWidth}x${session.actualHeight}"
                                )
                                // 每一轮会话只报一次：UI 只关心“现在连着没”。
                                onLinked(session.actualWidth, session.actualHeight)
                                // 握手前挂着的 IDR 请求（宿主刚接上就要的那一帧）此刻才能发。
                                flushKeyframeRequest()
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) onLog("CarWith CMD 通道结束：${t.message}")
        } finally {
            // CMD 一断整轮就没意义了：关掉 VIDEO，让视频读线程立刻退出、supervisor 重连。
            closeAll()
        }
    }

    /**
     * 记录 CMD 通道上**收到**的每一条消息（每轮会话每种类型只记一条）。
     *
     * 必须打在 [CarWithSession.receive] 之前：该方法返回的是"要回发给手机的动作"，
     * 类型已被换成车机侧应答（如收到 MD_FEATURE_CONFIG_REQUEST 会变成 HU_FEATURE_CONFIG_RESPONSE），
     * 在 action 层取不到对端真正发来的 type。远程输入法不可用时，只有这一层能回答
     * 「对端到底有没有下发输入法/能力类命令」。
     */
    private fun logIncomingCmd(type: Int, payload: ByteArray) {
        synchronized(loggedUnknownCmds) {
            if (!loggedUnknownCmds.add(type)) return
        }
        val head = payload.take(48).joinToString(" ") { "%02x".format(it) }
        Log.d(TAG, "CMD 收到 type=0x${type.toString(16)} len=${payload.size} head=$head")
    }

    // ---------------------------------------------------------------- VIDEO 通道

    private fun videoLoop(
        session: CarWithSession,
        socket: Socket,
        parser: CarWithVideoParser,
        firstFrame: AtomicBoolean,
    ) {
        val buffer = CarLifeProtocol.FrameBuffer()
        val chunk = ByteArray(READ_BUFFER)
        val input = socket.getInputStream()
        var frames = 0L
        var hevcWarned = false
        videoBytes = 0L
        videoMediaFrames = 0L
        videoDataFrames = 0L
        videoDroppedNotStarted = 0L
        videoAus = 0L
        while (running && !socket.isClosed) {
            val read = input.read(chunk)
            if (read <= 0) throw IOException("CarWith VIDEO 通道 EOF")
            lastVideoReadAtMs = SystemClock.elapsedRealtime()
            videoBytes += read
            buffer.append(chunk, 0, read)
            if (buffer.length > MAX_BUFFER_BYTES) {
                // 长度字段连续错位时缓冲会失控：整块丢弃重同步（与 Rust 侧 8MB 上限同一处置）。
                warnThrottled("CarWith 视频缓冲超过 ${MAX_BUFFER_BYTES / 1024 / 1024}MB，已清空重同步")
                buffer.clear()
                continue
            }
            while (true) {
                val frame = buffer.peekMedia() ?: break
                if (frame.type == 0) {
                    // 长度字段损坏：整块丢弃重同步（心跳/音频帧也会走到这里重新对齐）。
                    buffer.clear()
                    break
                }
                buffer.consume(frame.consumed)
                videoMediaFrames++
                if (frame.type != CarLifeProtocol.VIDEO_DATA) {
                    if (loggedMediaTypes.add(frame.type)) {
                        Log.d(TAG, "VIDEO 通道非视频帧 type=0x${frame.type.toString(16)} len=${frame.payload.size}")
                    }
                    continue
                }
                videoDataFrames++
                // 握手未完成就到达的帧是"上一代"的残留，解密不了也解不出完整 AU。
                if (!session.started) {
                    videoDroppedNotStarted++
                    continue
                }
                val plain = try {
                    session.decrypt(frame.payload)
                } catch (t: Throwable) {
                    // 解密失败 = 这一代字节流已错位（seed 换了等）：继续转发只会把垃圾当画面。
                    throw IOException("CarWith 视频帧解密失败：${t.message}", t)
                }
                if (plain.isEmpty()) continue
                val units = try {
                    parser.push(plain)
                } catch (e: VideoParseException) {
                    throw IOException(e.message ?: "CarWith 视频组帧失败", e)
                }
                if (parser.hevc) {
                    if (!hevcWarned) {
                        hevcWarned = true
                        onLog("CarWith 正在推 H.265(HEVC)，但仪表侧只解 H.264：视频帧已丢弃")
                    }
                    continue
                }
                for (unit in units) {
                    if (unit.codec != VideoCodec.H264) continue
                    videoAus++
                    if (firstFrame.compareAndSet(false, true)) {
                        onLog(
                            "CarWith 首帧到达：${unit.data.size}B " +
                                (if (unit.keyFrame) "(IDR)" else "(P 帧)") + "，开始转发到 15456"
                        )
                    }
                    frames++
                    emitUnit(parser, unit)
                    if (frames % 300L == 0L) onLog("CarWith 已转发 $frames 帧")
                }
            }
        }
    }

    /**
     * 一个 AU → （参数集变化时的）配置帧 + 媒体帧。
     *
     * 时间戳用**本机单调时钟到达时间**，不用 CarWith 帧头的时间：加密会话下那个字段是
     * Unix 秒（Rust 侧同样用 `monotonic_nanos()/1000`），换算成微秒会差出好几个量级。
     */
    private fun emitUnit(parser: CarWithVideoParser, unit: AccessUnit) {
        val ptsUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        lastFrameAtMs = SystemClock.elapsedRealtime()
        val config = parser.parameterSets
        if (config != null && !config.contentEquals(lastConfig)) {
            lastConfig = config
            onFrame(PluginFrame(ptsUs = ptsUs, isConfig = true, isKeyFrame = false, data = config))
        }
        onFrame(
            PluginFrame(
                ptsUs = ptsUs,
                isConfig = false,
                isKeyFrame = unit.keyFrame,
                data = unit.data
            )
        )
    }

    // ---------------------------------------------------------------- 心跳 / drain

    private fun startHeartbeat(video: Socket): Thread {
        val thread = Thread({
            val out = video.getOutputStream()
            val empty = ByteArray(0)
            while (running && !video.isClosed) {
                val ok = runCatching {
                    out.write(CarLifeProtocol.buildMediaFrame(CarLifeProtocol.VIDEO_HEARTBEAT, unixSeconds(), empty))
                    out.flush()
                }.isSuccess
                if (!ok) break
                sleepQuietly(HEARTBEAT_INTERVAL_MS)
            }
        }, "CarWithSource-heartbeat")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun startDrain(socket: Socket): Thread {
        val port = runCatching { socket.port }.getOrDefault(-1)
        val thread = Thread({
            val buffer = ByteArray(4096)
            var reason = "running=false"
            runCatching {
                val input = socket.getInputStream()
                while (running && !socket.isClosed) {
                    val n = input.read(buffer)
                    if (n <= 0) {
                        reason = "read=$n"
                        break
                    }
                }
            }.onFailure {
                reason = "${it.javaClass.simpleName}: ${it.message}"
            }
            // 对端在握手后关掉某条通道（尤其 CTRL）时，写方向会在下一次写才报错；
            // 这条日志把“谁先死”标出来，避免只能靠猜。
            if (running) onLog("通道 :$port 读结束（$reason）")
        }, "CarWithSource-drain")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    // ---------------------------------------------------------------- 工具

    /**
     * CMD 写线程安全包装：CMD 读线程（回包）、心跳线程、外部关键帧请求可能同时写，
     * 必须整帧原子（CarWith 按帧头长度切帧，半帧交错就整轮作废）。
     */
    private class CmdWriter(private val out: OutputStream, private val session: CarWithSession) {
        /** 正在写的起点；0 = 空闲。写卡在 socket 上时，看门狗靠它判定整轮该拆。 */
        @Volatile
        var writeStartedAtMs = 0L
            private set

        fun send(type: Int, payload: ByteArray) {
            val wire = CarLifeProtocol.buildCmdFrame(type, session.encrypt(payload))
            synchronized(this) {
                writeStartedAtMs = SystemClock.elapsedRealtime()
                try {
                    out.write(wire)
                    out.flush()
                } finally {
                    writeStartedAtMs = 0L
                }
            }
        }

        /**
         * 发一条**空 payload 且不加密**的 CMD。
         *
         * `VIDEO_ENCODER_RESET(0x1800B)` 在手机上要求 payload 为空；走 [send] 时
         * AES/PKCS7 会把空串加密成 16 字节密文，手机侧把它当成解码失败整条忽略。
         */
        fun sendEmptyPlain(type: Int) {
            val wire = CarLifeProtocol.buildCmdFrame(type, ByteArray(0))
            synchronized(this) {
                out.write(wire)
                out.flush()
            }
        }
    }

    private fun closeAll() {
        synchronized(sockets) {
            for (index in sockets.indices) {
                runCatching { sockets[index]?.close() }
                sockets[index] = null
            }
        }
    }

    /**
     * 重开手机侧编码器（`VIDEO_ENCODER_INIT` 再走一遍 INIT_DONE → START）。
     *
     * 实测这是唯一能让 CarWith 立刻产出一帧的手段：`VIDEO_ENCODER_RESET(0x1800B)`
     * 在静止画面下并不触发新帧，而重开编码器会让它重建 MediaCodec 并重新合成当前画面
     * （START 后约 90ms 到达一帧 IDR）。
     */
    private fun restartEncoder(): Boolean {
        val writer = keyframeWriter ?: return false
        val action = activeSession?.requestEncoderRestart() ?: return false
        return runCatching { writer.send(action.type, action.payload) }.isSuccess
    }

    /**
     * 静止补帧 + 活性看门狗（每 [IDLE_CHECK_INTERVAL_MS] 一轮）。
     *
     * 两件独立的事，顺序不能变：
     * 1. **活性**（[enforceLiveness]）：会话在、手机一直在推流，而 VIDEO 读线程长时间一个字节
     *    都没读进来 —— 这是"预览冻死"最硬的判据，只能拆掉本轮重建；
     * 2. **补帧**：链路正常但画面静止时先发 `VIDEO_ENCODER_RESET(0x1800B)`，
     *    [REFRESH_ESCALATE_MS] 后仍无新帧就 [restartEncoder]。
     *
     * 次序与限流都很要紧：重开编码器会让手机重建 MediaCodec，**刚收到正常帧时绝不能做**。
     * 这里曾经写成"只要不在升级窗口就重开编码器"，结果首帧到达 40ms 后就把手机的编码器
     * 强拆了一次，整条视频流随即僵死（手机继续编码、我们再也读不到）。
     */
    private fun idleWatchdogLoop() {
        var attemptAtMs = 0L
        while (running) {
            sleepQuietly(IDLE_CHECK_INTERVAL_MS)
            if (!running) break
            val now = SystemClock.elapsedRealtime()
            if (enforceLiveness(now)) continue
            if (activeSession?.started != true) {
                attemptAtMs = 0L
                continue
            }
            val last = lastFrameAtMs
            if (last == 0L) continue

            // 上一轮请求已经兑现（有新帧了）：这一轮什么都不做，等下一次静止。
            if (attemptAtMs != 0L && last >= attemptAtMs) attemptAtMs = 0L
            // 整轮周期限流：重开编码器不能一秒好几次。
            if (now - lastRefreshAtMs < IDLE_REFRESH_INTERVAL_MS) continue

            if (attemptAtMs == 0L) {
                // 真的静下来了才值得打扰手机；刚刚出过帧就直接退出去。
                if (now - last < IDLE_KEYFRAME_AFTER_MS) continue
                lastRefreshAtMs = now
                attemptAtMs = now
                logVideoDiagnostics("静止补帧")
                requestKeyframe()
                continue
            }
            // 请求还没兑现，过了升级窗口就重开编码器 —— 实测只有这一级真能出帧。
            if (now - attemptAtMs < REFRESH_ESCALATE_MS) continue
            lastRefreshAtMs = now
            attemptAtMs = now
            logVideoDiagnostics("静止补帧（升级：重开编码器）")
            if (!restartEncoder()) Log.w(TAG, "静止补帧：重开编码器被拒（会话未就绪）")
        }
    }

    /**
     * 会话活性检查：任何一条阻塞读/写卡住都必须在这里被拆掉。
     *
     * @return true 表示本轮已经做了破坏性处理（拆会话），调用方不要再发补帧请求
     */
    private fun enforceLiveness(now: Long): Boolean {
        // 1) VIDEO 读线程：手机一直在灌流，我们却长时间读不到任何字节。
        val socket = videoSocket
        val lastRead = lastVideoReadAtMs
        if (socket != null && lastRead != 0L && activeSession?.started == true) {
            val idle = now - lastRead
            if (idle >= VIDEO_READ_STALL_MS) {
                dumpCarWithThreads("VIDEO 读线程 ${idle}ms 未读到字节（手机侧仍在推流）")
                onLog("CarWith 视频读线程疑似卡死（${idle}ms 无数据），强制重建会话")
                // 关掉它会让阻塞中的 read 抛异常，supervisor 随即重连。
                runCatching { socket.close() }
                // 拆会话前的几轮里不要再重复报同一件事。
                lastVideoReadAtMs = now
                return true
            }
        }
        // 2) CMD 写：CmdWriter 的锁被一个卡在 socket 上的写长期持有，同样会把整轮收尾
        //    （含 closeAll）钉死，而且在释放前任何补帧请求也发不出去。
        val writing = keyframeWriter?.writeStartedAtMs ?: 0L
        if (writing != 0L && now - writing >= CMD_WRITE_STALL_MS) {
            dumpCarWithThreads("CMD 写 ${now - writing}ms 未完成（对端可能不读 7240）")
            onLog("CarWith CMD 写入卡死（${now - writing}ms），强制重建会话")
            closeAll()
            return true
        }
        return false
    }

    /**
     * 独立体检线程：每 [MONITOR_INTERVAL_MS] 打一行状态，并在"会话已启动却长时间读不到字节"
     * 时把线程栈打出来。
     *
     * 它存在的原因：看门狗本身也可能卡住（实测出现过"补帧看门狗一行日志都没有、读线程也停了"），
     * 所以取证必须放在一条只 sleep + 读标志位的线程上，不许参与任何协议逻辑。
     */
    private fun monitorLoop() {
        var tick = 0
        while (running) {
            sleepQuietly(MONITOR_INTERVAL_MS)
            if (!running) break
            tick++
            val now = SystemClock.elapsedRealtime()
            val started = activeSession?.started == true
            val readAgo = if (lastVideoReadAtMs == 0L) -1L else now - lastVideoReadAtMs
            val frameAgo = if (lastFrameAtMs == 0L) -1L else now - lastFrameAtMs
            val writing = keyframeWriter?.writeStartedAtMs ?: 0L
            Log.d(
                TAG,
                "monitor#$tick：握手完成=$started，读空闲 ${readAgo}ms，帧空闲 ${frameAgo}ms，" +
                    "读入 ${videoBytes / 1024}KB，媒体帧 $videoMediaFrames，AU $videoAus，" +
                    "CMD 在写=${if (writing == 0L) 0 else now - writing}ms",
            )
            if (started && readAgo >= MONITOR_DUMP_AFTER_MS) {
                dumpCarWithThreads("体检：VIDEO 读空闲 ${readAgo}ms，帧空闲 ${frameAgo}ms")
            }
            if (writing != 0L && now - writing >= CMD_WRITE_STALL_MS) {
                dumpCarWithThreads("体检：CMD 写 ${now - writing}ms 未完成")
            }
        }
    }

    /** 补帧看门狗每轮都打一条：read 阻塞时这条日志依然会出来，用来分辨"没发"与"没读"。 */
    private fun logVideoDiagnostics(prefix: String) {
        Log.d(
            TAG,
            "$prefix：VIDEO 读入 ${videoBytes / 1024}KB，媒体帧 $videoMediaFrames，" +
                "VIDEO_DATA $videoDataFrames，解出 AU $videoAus，握手前丢弃 $videoDroppedNotStarted",
        )
    }

    /**
     * 卡死现场打印相关线程的栈。
     *
     * 只打 `CarWith*` 与 `main`：卡的必然是本模块自己的线程，全量打会把 logcat 冲掉。
     * 直接走 [Log] 而不经 [onLog]（→ 回调线程），避免"回调线程本身就是卡点"时诊断一起失灵。
     */
    private fun dumpCarWithThreads(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStallDumpAtMs < STALL_DUMP_INTERVAL_MS) return
        lastStallDumpAtMs = now
        Log.w(TAG, "===== 线程栈 dump 开始：$reason =====")
        runCatching {
            for ((thread, frames) in Thread.getAllStackTraces()) {
                val name = thread.name
                if (!name.startsWith("CarWith") && name != "main") continue
                Log.w(TAG, "-- $name state=${thread.state}")
                for (frame in frames) Log.w(TAG, "      at $frame")
            }
        }
        Log.w(TAG, "===== 线程栈 dump 结束 =====")
    }

    /** 收尾时发 `VIDEO_ENCODER_PAUSE`：写完最好，写不完也**绝不能**拖住 [closeAll]。 */
    private fun sendPauseBestEffort(writer: CmdWriter) {
        val sender = Thread({
            runCatching { writer.send(CarLifeProtocol.CMD_VIDEO_ENCODER_PAUSE, ByteArray(0)) }
        }, "CarWithSource-pause")
        sender.isDaemon = true
        runCatching {
            sender.start()
            sender.join(PAUSE_SEND_WAIT_MS)
        }
    }

    private fun sleepWhileRunning(millis: Long): Boolean {
        return try {
            Thread.sleep(millis)
            running
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun warnThrottled(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWarnAtMs < LOG_THROTTLE_MS) return
        lastWarnAtMs = now
        onLog(message)
    }

    /**
     * 媒体帧头的时间戳。
     *
     * 加密会话下 CarWith 的媒体帧头用 **Unix 秒**（见 Rust 侧 `handle_media_client` 的心跳分支），
     * 不是相对毫秒；心跳不靠这个字段做节奏，但保持与手机上一致更安全。
     */
    private fun unixSeconds(): Int = (System.currentTimeMillis() / 1_000L).toInt()

    private fun firstInt(payload: ByteArray, field: Int): Long =
        CarLifeProto.firstInt(payload, field) ?: 0L
}
