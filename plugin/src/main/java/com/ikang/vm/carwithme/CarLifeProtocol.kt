/*
 * CarWith 协议适配层 —— 派生自 cardisplay2（GNU GPL v3.0），已按本项目需要修改。
 * 许可证全文见模块根 LICENSE，并随 APK 打包在 assets/GPL-3.0.txt。
 * 本模块整体按 GPL-3.0 分发；与宿主（app）之间只通过 :plugin-api 的 AIDL 契约往来。
 */
package com.ikang.vm.carwithme

/**
 * CarLife 4.1（小米 CarWith 本机桥）的线格式常量与帧拼装/切分。
 *
 * 角色约定：CarWith 在本机回环端口上**监听**，本 App 扮演 HU（车机）**主动拨**这七路：
 * CMD 7240 / VIDEO 8240 / MEDIA 9240 / TTS 9241 / VR 9242 / CTRL 9340 / DATA 9440。
 * 七路必须成套建立后才开始握手（缺一路 CarWith 会停在启动画面等我们）。
 *
 * 两种帧头：
 * - CMD/CTRL：[len:2 BE][reserved:2][type:4 BE][payload]，见 [buildCmdFrame]；
 * - VIDEO/MEDIA/TTS/VR：[len:4 BE][ts:4 BE][type:4 BE][payload]，见 [buildMediaFrame]。
 *
 * 移植自 cardisplay 的 `rust-core/src/carlife.rs`（历史版本含视频转发），
 * 本工程只保留「解密 + 拆帧 + 转发 15456」需要的最小集合。
 */
internal object CarLifeProtocol {

    // ---------------------------------------------------------------- 端口

    const val PORT_CMD = 7240
    const val PORT_VIDEO = 8240
    const val PORT_MEDIA = 9240
    const val PORT_TTS = 9241
    const val PORT_VR = 9242
    const val PORT_CTRL = 9340

    /** CarWith 额外开的数据通道；公版 CarLife 没有这一路。 */
    const val PORT_DATA = 9440

    /** 七路的拨号顺序必须与下方通道绑定一致（CMD 用于握手，VIDEO 出帧）。 */
    val ALL_PORTS = intArrayOf(PORT_CMD, PORT_VIDEO, PORT_MEDIA, PORT_TTS, PORT_VR, PORT_CTRL, PORT_DATA)

    // ---------------------------------------------------------------- CMD 消息 ID

    const val CMD_HU_PROTOCOL_VERSION = 0x00018001
    const val CMD_VERSION_MATCH_STATUS = 0x00010002
    const val CMD_HU_INFO = 0x00018003
    const val CMD_MD_INFO = 0x00010004
    const val CMD_VIDEO_ENCODER_INIT = 0x00018007
    const val CMD_VIDEO_ENCODER_INIT_DONE = 0x00010008
    const val CMD_VIDEO_ENCODER_START = 0x00018009
    const val CMD_VIDEO_ENCODER_PAUSE = 0x0001800A

    /**
     * 要一帧关键帧（mcarplay 的 `CarLifeSession.requestKeyFrame`，Rust 侧 `requestKeyFrame`）。
     *
     * 注意 payload 必为空且不加密：手机上它叫 `VIDEO_ENCODER_RESET`，不是 CTRL/START。
     */
    const val CMD_VIDEO_ENCODER_RESET = 0x0001800B
    const val CMD_HU_GO_TO_FOREGROUND = 0x00018025
    const val CMD_SCREEN_ON = 0x00010018
    const val CMD_FOREGROUND = 0x0001001B
    const val CMD_HU_STATISTIC_INFO = 0x00018027
    const val CMD_HU_AUTHEN_REQUEST = 0x00018048
    const val CMD_MD_AUTHEN_RESPONSE = 0x00010049
    const val CMD_HU_AUTHEN_RESULT = 0x0001804A
    const val CMD_MD_AUTHEN_RESULT = 0x0001004B
    const val CMD_MD_FEATURE_CONFIG_REQUEST = 0x00010051
    const val CMD_HU_FEATURE_CONFIG_RESPONSE = 0x00018052
    const val CMD_MD_EXIT = 0x00010059

    // ---- CONTENT_ENCRYPTION ----
    const val CMD_MD_RSA_KEY_REQUEST = 0x0001006A
    const val CMD_MD_AES_SEED = 0x0001006C
    const val CMD_MD_ENCRYPT_READY = 0x0001006E
    const val CMD_HU_MD_AUTH_RESULT_ACK = 0x0001804C
    const val CMD_HU_RSA_PUBLIC_KEY = 0x0001806B
    const val CMD_HU_AES_ACK = 0x0001806D

    // ---------------------------------------------------------------- 媒体通道消息类型

    /** 视频帧：payload 是 AES-ECB 加密的 H.264 Annex-B 访问单元。 */
    const val VIDEO_DATA = 0x00020001

    /** 视频通道心跳（每秒一条，否则 CarWith 认为无人接收，编码器空转）。 */
    const val VIDEO_HEARTBEAT = 0x00020002

    const val MEDIA_DATA = 0x00030006
    const val TTS_DATA = 0x00040003

    // ---------------------------------------------------------------- CTRL 通道（触摸 / 硬键）

    /**
     * 触摸动作（CarLife 语义：0=DOWN / 1=UP / 2=MOVE，**不是** Android MotionEvent 的 1/2/3）。
     *
     * 这一层动作值只用于本工程内部调用（[CarWithSource.sendTouch] 与预览页），
     * 上线时会被翻成下面的 CTRL 消息 ID —— CarLife 的触摸**靠消息 ID 区分动作**，
     * payload 里没有 action 字段。
     */
    const val TOUCH_DOWN = 0
    const val TOUCH_UP = 1
    const val TOUCH_MOVE = 2

    /**
     * 触摸按下：payload 为 protobuf `1:x 2:y`（见 [CarLifeProto.touchPayload]）。
     * 坐标是**手机回报的实际编码画布像素**（`VIDEO_ENCODER_INIT_DONE` 的宽高），
     * 不是 0..1 归一化值，也不是我们请求的画布。
     */
    const val CTRL_TOUCH_DOWN = 0x00068002

    /** 触摸抬起：同样带坐标 —— CarWith 侧按“最后一点”处理，不可省略。 */
    const val CTRL_TOUCH_UP = 0x00068003

    /** 触摸移动；“按下中又收到 DOWN”也按这条发（单指手势，不做多点）。 */
    const val CTRL_TOUCH_MOVE = 0x00068004

    /** 方向盘/旋钮等硬键：payload 为 protobuf `1:keycode`（见 [CarWithSource.sendHardKey]）。 */
    const val CTRL_CAR_HARD_KEY_CODE = 0x00068008

    /**
     * 硬键里的返回键 keycode。
     *
     * 直接就是 Android 的 [android.view.KeyEvent.KEYCODE_BACK]（=4）在 CarWith 侧的映射值 14
     * （与 Rust 参考实现 `handle_console_key` 的 CONSOLE_BACK 一致），不要用 KEYCODE_BACK 本身。
     */
    const val HARD_KEY_BACK = 14

    /** 协议版本：CarWith 实测接受 4.1。 */
    const val PROTOCOL_MAJOR = 4
    const val PROTOCOL_MINOR = 1

    /** 单条媒体帧 payload 上限（超过即视为长度字段损坏，整块缓冲丢弃重同步）。 */
    private const val MAX_MEDIA_PAYLOAD = 32 * 1024 * 1024

    // ---------------------------------------------------------------- 帧拼装

    /** CMD/CTRL 帧：`[len:2 BE][00 00][type:4 BE][payload]`。 */
    fun buildCmdFrame(type: Int, payload: ByteArray): ByteArray {
        val len = payload.size.coerceAtMost(0xFFFF)
        val out = ByteArray(8 + len)
        out[0] = (len ushr 8).toByte()
        out[1] = len.toByte()
        out[4] = (type ushr 24).toByte()
        out[5] = (type ushr 16).toByte()
        out[6] = (type ushr 8).toByte()
        out[7] = type.toByte()
        System.arraycopy(payload, 0, out, 8, len)
        return out
    }

    /** 媒体帧：`[len:4 BE][timestamp:4 BE][type:4 BE][payload]`。 */
    fun buildMediaFrame(type: Int, timestampMs: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(12 + payload.size)
        putInt(out, 0, payload.size)
        putInt(out, 4, timestampMs)
        putInt(out, 8, type)
        System.arraycopy(payload, 0, out, 12, payload.size)
        return out
    }

    private fun putInt(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value ushr 24).toByte()
        dst[offset + 1] = (value ushr 16).toByte()
        dst[offset + 2] = (value ushr 8).toByte()
        dst[offset + 3] = value.toByte()
    }

    /** CMD 帧切片结果；[consumed] 为整帧字节数（8 + len）。 */
    class CmdFrame(val type: Int, val payload: ByteArray, val consumed: Int)

    /** 媒体帧切片结果；[timestampMs] 是 CarWith 打在帧头上的时间戳（毫秒）。 */
    class MediaFrame(val type: Int, val timestampMs: Int, val payload: ByteArray, val consumed: Int)

    /**
     * 可增长的接收缓冲：append 收到的字节，peek 出一帧后 consume。
     *
     * 与 Rust 版 `buf.drain(..consumed)` 等价，但避免每帧做一次整块搬运：
     * 消费用读指针推进，攒到一半以上才压缩一次。
     */
    class FrameBuffer {
        private var data = ByteArray(256 * 1024)
        private var head = 0
        private var tail = 0

        val length: Int get() = tail - head

        fun append(src: ByteArray, offset: Int, count: Int) {
            ensure(count)
            System.arraycopy(src, offset, data, tail, count)
            tail += count
        }

        fun clear() {
            head = 0
            tail = 0
        }

        private fun ensure(extra: Int) {
            if (data.size - tail >= extra) return
            val len = length
            // 先把有效数据搬到 0：只要 head 前进过就搬，不能只看 head >= size/2 —— 下面扩容
            // 是按"尾部位置"算的，若这里没搬而 tail 又靠后，append 会直接写出界。
            if (head > 0) {
                System.arraycopy(data, head, data, 0, len)
                head = 0
                tail = len
                if (data.size - tail >= extra) return
            }
            // 扩容必须按 tail + extra 算：按 len + extra 算会漏掉"tail 之前的空洞"，
            // 在接收缓冲里打爆过一次（一次大的积压排空就 ArrayIndexOutOfBoundsException，
            // 直接弄断整轮会话）。
            val needed = tail + extra
            var size = data.size
            while (size < needed) size = size shl 1
            data = data.copyOf(size)
        }

        /** 切一条 CMD 帧；数据不足返回 null。 */
        fun peekCmd(): CmdFrame? {
            if (length < 8) return null
            val len = ((data[head].toInt() and 0xFF) shl 8) or (data[head + 1].toInt() and 0xFF)
            if (length < 8 + len) return null
            val type = intAt(head + 4)
            val payload = data.copyOfRange(head + 8, head + 8 + len)
            return CmdFrame(type, payload, 8 + len)
        }

        /**
         * 切一条媒体帧；数据不足返回 null。
         *
         * 长度字段非法时返回 `type=0` 且 consumed=整块长度，调用方据此清空缓冲重同步。
         */
        fun peekMedia(): MediaFrame? {
            if (length < 12) return null
            val len = (intAt(head).toLong() and 0xFFFFFFFFL)
            if (len > MAX_MEDIA_PAYLOAD) {
                return MediaFrame(0, 0, ByteArray(0), length)
            }
            val total = 12 + len.toInt()
            if (length < total) return null
            val ts = intAt(head + 4)
            val type = intAt(head + 8)
            val payload = data.copyOfRange(head + 12, head + total)
            return MediaFrame(type, ts, payload, total)
        }

        fun consume(count: Int) {
            if (count <= 0) return
            head += count
            if (head >= tail) {
                head = 0
                tail = 0
            }
        }

        private fun intAt(offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }
}
