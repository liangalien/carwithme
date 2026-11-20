/*
 * CarWith 协议适配层 —— 派生自 cardisplay2（GNU GPL v3.0），已按本项目需要修改。
 * 许可证全文见模块根 LICENSE，并随 APK 打包在 assets/GPL-3.0.txt。
 * 本模块整体按 GPL-3.0 分发；与宿主（app）之间只通过 :plugin-api 的 AIDL 契约往来。
 */
package com.ikang.vm.carwithme

/**
 * CarWith VIDEO 通道的 Annex-B 组帧器（逐字节移植自 cardisplay `rust-core/src/carlife/video.rs`）。
 *
 * **CarWith 的包边界不是访问单元（AU）边界**，这是整条链路最容易翻车的地方：
 * - 一个 `VIDEO_DATA` 里可能有「上一帧的尾巴 + 下一帧的头」，也可能只有半帧；
 * - 所以必须把 NAL 攒在缓冲里，直到**后继 NAL 的头部证明这是新一幅图**才把上一幅图交出去
 *   （见 [boundary]），最后一个 NAL 永远留在缓冲里等下一包补全。
 *
 * 另外两件事也在这一层做：
 * - **IDR 自带参数集**：每幅 IDR 输出前把缓存的 SPS/PPS 补进去（编码器不一定每帧都重发），
 *   这样 15456 侧任何一个客户端都能从任意 IDR 起步；
 * - **缺省只解 H.264**：H.265(VPS/SPS/PPS 三件套) 在这里被识别出来并**整轮丢弃**，
 *   因为仪表侧（15456 的接收端）的裸流 demuxer 只认 H.264。识别到就通过 [hevc] 告警。
 *
 * 单线程使用：只有 VIDEO 读线程会碰它（与 Rust 侧 `handle_media_client` 一致）。
 */
internal class CarWithVideoParser {

    companion object {
        /** 单个 AU + 缓存的参数集上限；超过即判定源端异常，整轮作废。 */
        private const val LIMIT = 4 * 1024 * 1024

        /** start code 统一补成 4 字节，输出与检查都按这个前缀算长度。 */
        private val PREFIX = byteArrayOf(0, 0, 0, 1)

        private const val NAL_SLICE = 1
        private const val NAL_IDR = 5
        private const val NAL_SEI = 6
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val NAL_AUD = 9
    }

    private var buffer = ByteArray(128 * 1024)
    private var bufferSize = 0
    private val nals = ArrayList<ByteArray>(16)
    private var bytes = 0
    private var vcl = false
    private var idr = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    // HEVC 探测的暂存（只用于识别"对端切到了 H.265"，不解析内容）
    private var probeVps: ByteArray? = null
    private var probeSps: ByteArray? = null

    /**
     * 是否已判定上游在用 H.265。
     *
     * 置位后 [push] 不再产出任何 AU —— 仪表侧只吃 H.264 裸流，把 HEVC 当作 H.264 喂过去
     * 只会让接收端解码器报错甚至断开，不如保持会话活着并告警。
     */
    var hevc: Boolean = false
        private set

    /** 当前缓存的参数集（Annex-B：SPS + PPS）；任一缺失则为 null。 */
    val parameterSets: ByteArray?
        get() {
            val s = sps ?: return null
            val p = pps ?: return null
            val out = ByteArray(PREFIX.size * 2 + s.size + p.size)
            System.arraycopy(PREFIX, 0, out, 0, PREFIX.size)
            System.arraycopy(s, 0, out, PREFIX.size, s.size)
            var offset = PREFIX.size + s.size
            System.arraycopy(PREFIX, 0, out, offset, PREFIX.size)
            offset += PREFIX.size
            System.arraycopy(p, 0, out, offset, p.size)
            return out
        }

    /**
     * 喂一段解密后的 Annex-B 字节，返回本次**恰好凑齐**的完整访问单元（可能为 0 个或多个）。
     *
     * @throws VideoParseException 累积超过 [LIMIT]（源端异常，调用方应重连）
     */
    fun push(payload: ByteArray): List<AccessUnit> {
        if (hevc || payload.isEmpty()) return emptyList()
        if (bufferSize + payload.size + bytes > LIMIT) {
            reset()
            throw VideoParseException("CarWith 视频访问单元超过 ${LIMIT / 1024 / 1024}MB")
        }
        append(payload)

        val starts = startCodes()
        val out = ArrayList<AccessUnit>(4)
        for (index in 0 until starts.size - 1) {
            val begin = starts[index][0] + starts[index][1]
            val end = starts[index + 1][0]
            if (end <= begin) continue
            consume(buffer.copyOfRange(begin, end), out)
            if (hevc) return out
            if (bytes + cachedParameterBytes() > LIMIT || out.any { it.data.size > LIMIT }) {
                reset()
                throw VideoParseException("CarWith 视频访问单元超过 ${LIMIT / 1024 / 1024}MB")
            }
        }
        if (starts.isNotEmpty()) {
            val last = starts.last()
            val begin = last[0] + last[1]
            if (begin < bufferSize) {
                val trailing = buffer.copyOfRange(begin, bufferSize)
                // 它自己的头已经证明是新一幅图（哪怕帧体还在下一包里），先把上一幅交出去。
                if (boundary(trailing)) flush(out)
            }
            removePrefix(last[0])
        }
        return out
    }

    /** 丢弃全部状态（解析错误/重连时调用）。 */
    fun reset() {
        bufferSize = 0
        nals.clear()
        bytes = 0
        vcl = false
        idr = false
        sps = null
        pps = null
        probeVps = null
        probeSps = null
    }

    // ---------------------------------------------------------------- NAL 处理

    /**
     * 当前 NAL 是否开启新的一幅图（Rust `boundary`）。
     *
     * 判据：AUD 一定开新图；已经在片数据里时，SEI/SPS/PPS 视为新图的前缀；
     * 片数据自身则看 `first_mb_in_slice == 0`（多切片的一幅图只有第一片是 0）。
     */
    private fun boundary(nal: ByteArray): Boolean {
        if (nal.isEmpty()) return false
        val kind = nal[0].toInt() and 0x1F
        if (kind == NAL_AUD) return true
        if (!vcl) return false
        if (kind == NAL_SEI || kind == NAL_SPS || kind == NAL_PPS) return true
        return (kind == NAL_SLICE || kind == NAL_IDR) && firstMb(nal) == 0
    }

    private fun consume(nal: ByteArray, out: MutableList<AccessUnit>) {
        if (nal.isEmpty()) return
        // 先探 HEVC：切到 H.265 时把已攒的 H.264 收尾，然后整轮只告警不再出帧。
        if (probeHevcConfig(nal)) {
            flush(out)
            hevc = true
            reset()
            return
        }
        if (boundary(nal)) flush(out)
        when (nal[0].toInt() and 0x1F) {
            NAL_SPS -> {
                // 换了 SPS 就不能再配旧的 PPS。
                val old = sps
                if (old != null && !old.contentEquals(nal)) {
                    pps = null
                    nals.removeAll { isParameterSet(it) }
                    bytes = nals.sumOf { it.size + PREFIX.size }
                }
                sps = nal
            }

            NAL_PPS -> {
                nals.removeAll { (it[0].toInt() and 0x1F) == NAL_PPS }
                bytes = nals.sumOf { it.size + PREFIX.size }
                pps = nal
            }

            NAL_SLICE -> vcl = true
            NAL_IDR -> {
                vcl = true
                idr = true
            }
        }
        bytes += nal.size + PREFIX.size
        nals += nal
    }

    private fun flush(out: MutableList<AccessUnit>) {
        if (vcl) {
            out += AccessUnit(buildAccessUnit(), VideoCodec.H264, idr)
        }
        nals.clear()
        bytes = 0
        vcl = false
        idr = false
    }

    /**
     * 拼一个 AU：IDR 时若本帧没带参数集，把缓存的 SPS/PPS 补在最前面。
     *
     * 早期 IDR 可能因为还没拿到参数集而被上层丢弃（例如 15456 尚无客户端），
     * 后面每个 IDR 都必须自带参数集才能让新客户端随时起步。
     */
    private fun buildAccessUnit(): ByteArray {
        val has = BooleanArray(2)
        for (nal in nals) {
            when (nal[0].toInt() and 0x1F) {
                NAL_SPS -> has[0] = true
                NAL_PPS -> has[1] = true
            }
        }
        val cached = ArrayList<ByteArray>(2)
        if (idr) {
            if (!has[0]) sps?.let { cached += it }
            if (!has[1]) pps?.let { cached += it }
        }
        // 长度必须与下面的拷贝逐项对齐：每个 NAL（含补齐进来缓存参数集）都是
        // 「4 字节前缀 + 负载」。早先这里把参数集只按 `cached.size * PREFIX.size` 计成 4 字节/个，
        // 漏算了 SPS/PPS 的负载，导致 IDR 需要补参数集时 arraycopy 越界（
        // ArrayIndexOutOfBoundsException）→ 整轮会话在首个 IDR 处被拆掉、15456 一帧都发不出去。
        var size = 0
        for (nal in cached) size += nal.size + PREFIX.size
        for (nal in nals) size += nal.size + PREFIX.size
        val out = ByteArray(size)
        var offset = 0
        for (list in arrayOf(cached, nals)) {
            for (nal in list) {
                System.arraycopy(PREFIX, 0, out, offset, PREFIX.size)
                offset += PREFIX.size
                System.arraycopy(nal, 0, out, offset, nal.size)
                offset += nal.size
            }
        }
        return out
    }

    private fun isParameterSet(nal: ByteArray): Boolean {
        val kind = nal[0].toInt() and 0x1F
        return kind == NAL_SPS || kind == NAL_PPS
    }

    private fun cachedParameterBytes(): Int {
        var total = 0
        sps?.let { total += it.size + PREFIX.size }
        pps?.let { total += it.size + PREFIX.size }
        return total
    }

    // ---------------------------------------------------------------- HEVC 探测

    /**
     * 仅识别「VPS → SPS → PPS」这个有序三件套（Rust `hevc::nal_type` 的头部过滤条件）。
     *
     * 不做 hvcC 校验：识别到也只会丢弃，不存在"误判成 HEVC 而解析出错"的风险。
     * H.264 的 SPS(0x67)/PPS(0x68) 过不了这里的头部过滤，正常流不会误判。
     */
    private fun probeHevcConfig(nal: ByteArray): Boolean {
        if (nal.size < 3 || (nal[0].toInt() and 1) != 0 || (nal[1].toInt() and 0xF8) != 0) {
            probeVps = null
            probeSps = null
            return false
        }
        when ((nal[0].toInt() shr 1) and 0x3F) {
            32 -> {
                probeVps = nal
                probeSps = null
            }

            33 -> if (probeVps != null) probeSps = nal
            34 -> if (probeVps != null && probeSps != null) return true
            else -> {
                probeVps = null
                probeSps = null
            }
        }
        return false
    }

    // ---------------------------------------------------------------- 位级工具

    /**
     * 读片数据头的 `first_mb_in_slice`（ue(v)，Rust `first_mb`）。
     *
     * 只看前 32 字节、去掉防竞争字节 `00 00 03`，够判断是否为 0 即可。
     */
    private fun firstMb(nal: ByteArray): Int {
        val rbsp = ByteArray(nal.size.coerceAtMost(40))
        var length = 0
        var zeros = 0
        var i = 1
        while (i < nal.size && i <= 32) {
            val b = nal[i].toInt() and 0xFF
            i++
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            if (length >= rbsp.size) break
            rbsp[length++] = b.toByte()
            zeros = if (b == 0) zeros + 1 else 0
        }

        fun bitAt(index: Int): Int {
            val byteIndex = index / 8
            if (byteIndex >= length) return -1
            return ((rbsp[byteIndex].toInt() and 0xFF) shr (7 - index % 8)) and 1
        }

        var pos = 0
        var leading = 0
        while (true) {
            val bit = bitAt(pos)
            if (bit < 0 || leading >= 32) return -1
            pos++
            if (bit == 1) break
            leading++
        }
        var code = 1
        for (n in 0 until leading) {
            val bit = bitAt(pos)
            if (bit < 0) return -1
            pos++
            code = (code shl 1) or bit
        }
        return code - 1
    }

    // ---------------------------------------------------------------- 缓冲

    private fun append(payload: ByteArray) {
        if (bufferSize + payload.size > buffer.size) {
            var size = buffer.size
            while (size < bufferSize + payload.size) size = size shl 1
            buffer = buffer.copyOf(size)
        }
        System.arraycopy(payload, 0, buffer, bufferSize, payload.size)
        bufferSize += payload.size
    }

    private fun removePrefix(count: Int) {
        if (count <= 0) return
        if (count >= bufferSize) {
            bufferSize = 0
            return
        }
        System.arraycopy(buffer, count, buffer, 0, bufferSize - count)
        bufferSize -= count
    }

    /** 缓冲里所有 start code 的 `[偏移, 前缀长度]`（Rust `start_codes`）。 */
    private fun startCodes(): List<IntArray> {
        val result = ArrayList<IntArray>(8)
        var i = 0
        while (i + 3 <= bufferSize) {
            if (buffer[i].toInt() == 0 && buffer[i + 1].toInt() == 0) {
                if (buffer[i + 2].toInt() == 1) {
                    result += intArrayOf(i, 3)
                    i += 3
                    continue
                }
                if (i + 4 <= bufferSize && buffer[i + 2].toInt() == 0 && buffer[i + 3].toInt() == 1) {
                    result += intArrayOf(i, 4)
                    i += 4
                    continue
                }
            }
            i++
        }
        return result
    }
}

/** 一个完整的 H.264 访问单元（Annex-B，参数集已按需补齐）。 */
internal class AccessUnit(
    val data: ByteArray,
    val codec: VideoCodec,
    /** 是否含 IDR：只有关键帧才能作为 15456 客户端的起步帧。 */
    val keyFrame: Boolean,
)

internal enum class VideoCodec { H264, HEVC }

/** 视频流不可解析（超过上限等）：整轮重连。 */
internal class VideoParseException(message: String) : Exception(message)
