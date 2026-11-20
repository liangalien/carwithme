/*
 * CarWith 协议适配层 —— 派生自 cardisplay2（GNU GPL v3.0），已按本项目需要修改。
 * 许可证全文见模块根 LICENSE，并随 APK 打包在 assets/GPL-3.0.txt。
 * 本模块整体按 GPL-3.0 分发；与宿主（app）之间只通过 :plugin-api 的 AIDL 契约往来。
 */
package com.ikang.vm.carwithme

import java.security.KeyPair
import java.util.UUID

/**
 * CarWith 4.1 的 CMD 通道握手状态机（HU/盒子一侧）。
 *
 * 移植自 cardisplay `rust-core/src/carlife/session.rs` 的 `Session::receive`：手机每发来一条 CMD，
 * 本状态机返回「该回什么」的列表（[Action]），由读写线程加密后写回 7240。
 *
 * 握手时序（顺序错一步手机就停在启动画面）：
 * 1. 我们主动发 `HU_PROTOCOL_VERSION(4.1)`；
 * 2. `MD_VERSION_MATCH_STATUS(1)` → 回 `HU_STATISTIC_INFO`；
 * 3. `MD_INFO`（brand/model/sdk）→ 回 `HU_INFO` + `HU_AUTHEN_REQUEST(challenge)`；
 * 4. `MD_AUTHEN_RESPONSE(md5 证明)` → 校验证书后回 `HU_AUTHEN_RESULT(true)`；
 * 5. `MD_AUTHEN_RESULT(true)` → 回 `HU_MD_AUTH_RESULT_ACK`（通道准入）；
 * 6. `MD_FEATURE_CONFIG_REQUEST` + 证明通过 → 回 `HU_FEATURE_CONFIG_RESPONSE`
 *    （CONTENT_ENCRYPTION=1 + VOICE_MIC=1，后者表示不支持上行麦克风）；
 * 7. `MD_RSA_KEY_REQUEST` → 回 `HU_RSA_PUBLIC_KEY`（base64 DER）；
 * 8. `MD_AES_SEED` → 用私钥解出 seed，回 `HU_AES_ACK`（此后收发内容全部 AES-ECB）；
 * 9. `MD_ENCRYPT_READY` → 三件套（证明/准入/加密）齐了，回 `VIDEO_ENCODER_INIT`；
 * 10. `VIDEO_ENCODER_INIT_DONE` → 记录手机实际采用的分辨率，回 `VIDEO_ENCODER_START`，
 *     置 [started]（此刻起 8240 上的 VIDEO_DATA 才有意义）。
 *
 * 握手完成后不再需要任何主动保活：手机漏帧时由 [CarWithSource.requestKeyframe]
 * 发 `VIDEO_ENCODER_RESET(0x1800B)` 要一帧 IDR，重复发 START 反而可能让手机重建编码器。
 *
 * 并发约定：`receive`/`encrypt`/`decrypt` 可能被 CMD 读线程与 VIDEO 读线程同时调用，
 * 故 `receive` 加锁、`seed` 用 @Volatile 发布（写完即对其他线程可见）。
 *
 * @param width/height 期望画布（设置页的可选分辨率，[canvasFor] 只做偶数对齐与上下限收敛）
 * @param fps 期望帧率（上限 30：CarWith 不接受更高）
 */
internal class CarWithSession(
    width: Int,
    height: Int,
    private val fps: Int,
    private val btAddress: String,
    private val btName: String,
) {

    /** 一条待发出的 CMD：消息 ID + 明文 payload（写线程负责加密与封帧）。 */
    class Action(val type: Int, val payload: ByteArray)

    companion object {
        /** 单边上限：再大手机侧编码器只会降级，白占带宽。 */
        private const val MAX_SIDE = 2560
        private const val MIN_CANVAS_WIDTH = 320
        private const val MIN_CANVAS_HEIGHT = 240

        /**
         * 请求编码尺寸：**用户设置什么就请求什么**，只做偶数对齐与上下限收敛。
         *
         * 这里不做等比缩放，也不做 16 对齐：编码在手机侧（CarWith 的 MediaCodec），
         * 分辨率属于用户可见的设置项（控制台 CarWith 面板的「投屏分辨率」），
         * 静默改尺寸会让“设了 1280×720 实际出 1008×720”这类问题极难排查。
         * cardisplay 现行实现同样把用户设置原样透传给 `VIDEO_ENCODER_INIT`。
         */
        fun canvasFor(width: Int, height: Int): Pair<Int, Int> {
            return width.even(MIN_CANVAS_WIDTH) to height.even(MIN_CANVAS_HEIGHT)
        }

        /** 编码器只接受偶数边长；越界值按上下限收敛。 */
        private fun Int.even(minimum: Int): Int = coerceIn(minimum, MAX_SIDE) and 1.inv()
    }

    /** 发给手机的随机挑战串，格式固定 `2.0;<32 位无横线 uuid>`（证明材料要用 `substring(4)`）。 */
    val challenge: String = "2.0;" + UUID.randomUUID().toString().replace("-", "")

    /** 请求的编码尺寸（`VIDEO_ENCODER_INIT` 里下发的值）。 */
    val requestedCanvas: Pair<Int, Int> = canvasFor(width, height)

    /** 手机实际采用的分辨率（`INIT_DONE` 里回报）；0 表示还没回报。 */
    @Volatile var actualWidth: Int = 0
        private set
    @Volatile var actualHeight: Int = 0
        private set

    /** 是否已发出 `VIDEO_ENCODER_START`：此前到达的 VIDEO_DATA 必须丢弃。 */
    @Volatile var started: Boolean = false
        private set

    /** 是否已拿到 AES seed（内容加密生效）。 */
    @Volatile var encryptionReady: Boolean = false
        private set

    private var info: Triple<String, String, String>? = null
    private var proofOk = false
    private var admitted = false
    private var featuresRequested = false
    private var featuresSent = false
    private var matched = false
    private var keyPair: KeyPair? = null
    private var seed: ByteArray? = null
    private var ready = false
    private var initSent = false

    /** 已发出「重开编码器」的 INIT，等 INIT_DONE 回来时要**无条件**再回一次 START。 */
    private var restartPending = false

    /** 加密（发给手机的内容）；seed 未就绪时原样返回。 */
    fun encrypt(bytes: ByteArray): ByteArray = CarLifeCrypto.crypt(bytes, seed, true)

    /** 解密（手机发来的内容）；seed 未就绪时原样返回。 */
    fun decrypt(bytes: ByteArray): ByteArray = CarLifeCrypto.crypt(bytes, seed, false)

    /**
     * 请手机**重开编码器**：再走一遍 `VIDEO_ENCODER_INIT` → `INIT_DONE` → `VIDEO_ENCODER_START`。
     *
     * 这是唯一实测能让 CarWith 立刻产出一帧的手段。CarWith 的编码器是 damage-driven 的，
     * 静止画面下一帧都不编；而 `VIDEO_ENCODER_RESET(0x1800B)` 实测并不触发新帧。重开编码器
     * 会让它重建 MediaCodec 并重新合成当前画面 —— 拿到的就是"这一刻"的真实现状（实测 START
     * 后约 90ms 到达一帧 IDR）。
     *
     * 刻意**不**动 [started]：万一手机不理会这次 INIT，[started] 保持为真，视频读线程照常
     * 收帧，不会把自己锁死在"等 INIT_DONE"上。
     *
     * @return 要发给手机的 INIT 动作；会话还没走到开编码器那一步时返回 null
     */
    @Synchronized
    fun requestEncoderRestart(): Action? {
        if (!proofOk || !admitted || !ready || !initSent) return null
        restartPending = true
        val (w, h) = requestedCanvas
        return Action(
            CarLifeProtocol.CMD_VIDEO_ENCODER_INIT,
            CarLifeProto.encoderInfoPayload(w, h, fps.coerceIn(1, 30)),
        )
    }

    /**
     * 处理一条 CMD，返回需要回给手机的消息。
     *
     * @throws CarWithProtocolException 协议层面无法继续（版本被拒 / 证明不对 / 准入被拒 /
     *         对端退出）：调用方应关闭七路并按退避重连。
     */
    @Synchronized
    fun receive(kind: Int, body: ByteArray): List<Action> {
        val out = ArrayList<Action>(2)
        when (kind) {
            CarLifeProtocol.CMD_VERSION_MATCH_STATUS -> {
                if (CarLifeProto.firstInt(body, 1) != 1L) {
                    throw CarWithProtocolException("CarWith 拒绝了协议版本 4.1")
                }
                if (!matched) {
                    matched = true
                    out += Action(CarLifeProtocol.CMD_HU_STATISTIC_INFO, CarLifeProto.statisticPayload())
                }
            }

            CarLifeProtocol.CMD_MD_INFO -> {
                if (info == null) {
                    val brand = requireText(body, 4, "MD_INFO.brand")
                    val model = requireText(body, 14, "MD_INFO.model")
                    val sdk = requireText(body, 20, "MD_INFO.sdk")
                    info = Triple(brand, model, sdk)
                    out += Action(
                        CarLifeProtocol.CMD_HU_INFO,
                        CarLifeProto.huInfoPayload(btAddress, btName)
                    )
                    out += Action(
                        CarLifeProtocol.CMD_HU_AUTHEN_REQUEST,
                        CarLifeProto.authenRequestPayload(challenge)
                    )
                }
            }

            CarLifeProtocol.CMD_MD_AUTHEN_RESPONSE -> {
                if (!proofOk) {
                    val (brand, model, sdk) = info ?: throw CarWithProtocolException("证明早于 MD_INFO")
                    val proof = CarLifeProto.firstString(body, 1)
                        ?: throw CarWithProtocolException("缺少挑战证明")
                    val material = "CARLIFE" + brand + model + sdk + challenge.substring(4)
                    val expected = CarLifeCrypto.md5Hex(material)
                    if (!proof.equals(expected, ignoreCase = true)) {
                        throw CarWithProtocolException("CarWith 挑战证明不匹配")
                    }
                    proofOk = true
                    out += Action(
                        CarLifeProtocol.CMD_HU_AUTHEN_RESULT,
                        CarLifeProto.authenResultPayload(true)
                    )
                }
            }

            CarLifeProtocol.CMD_MD_AUTHEN_RESULT -> {
                if (CarLifeProto.firstInt(body, 1) != 1L) {
                    throw CarWithProtocolException("CarWith 拒绝通道准入")
                }
                if (!admitted) {
                    admitted = true
                    out += Action(CarLifeProtocol.CMD_HU_MD_AUTH_RESULT_ACK, ByteArray(0))
                }
            }

            CarLifeProtocol.CMD_MD_FEATURE_CONFIG_REQUEST -> featuresRequested = true

            CarLifeProtocol.CMD_MD_RSA_KEY_REQUEST -> {
                if (keyPair == null) {
                    val pair = CarLifeCrypto.newRsaKeyPair()
                    keyPair = pair
                    out += Action(
                        CarLifeProtocol.CMD_HU_RSA_PUBLIC_KEY,
                        CarLifeProto.string(1, CarLifeCrypto.publicKeyDerBase64(pair))
                    )
                }
            }

            CarLifeProtocol.CMD_MD_AES_SEED -> {
                if (seed == null) {
                    val pair = keyPair ?: throw CarWithProtocolException("seed 早于 RSA 公钥")
                    val encoded = CarLifeProto.firstString(body, 1)
                        ?: throw CarWithProtocolException("缺少 AES seed")
                    val plain = CarLifeCrypto.rsaDecrypt(pair, CarLifeCrypto.base64Decode(encoded))
                    if (plain.size != 16 && plain.size != 24 && plain.size != 32) {
                        throw CarWithProtocolException("AES seed 长度非法: ${plain.size}")
                    }
                    seed = plain
                    encryptionReady = true
                    out += Action(CarLifeProtocol.CMD_HU_AES_ACK, ByteArray(0))
                }
            }

            CarLifeProtocol.CMD_MD_ENCRYPT_READY -> {
                if (seed == null) throw CarWithProtocolException("ENCRYPT_READY 早于 seed")
                ready = true
            }

            CarLifeProtocol.CMD_VIDEO_ENCODER_INIT_DONE -> {
                if (!initSent) throw CarWithProtocolException("INIT_DONE 早于 VIDEO_ENCODER_INIT")
                val (w, h) = CarLifeProto.parseEncoderInfo(body)
                if (w >= 2 && h >= 2) {
                    actualWidth = w
                    actualHeight = h
                }
                // 重开编码器时 [started] 本来就已经是 true，必须靠 [restartPending] 才会再回一次的 START。
                if (!started || restartPending) {
                    started = true
                    restartPending = false
                    out += Action(CarLifeProtocol.CMD_VIDEO_ENCODER_START, ByteArray(0))
                }
            }

            CarLifeProtocol.CMD_FOREGROUND, CarLifeProtocol.CMD_SCREEN_ON -> {
                if (matched) out += Action(CarLifeProtocol.CMD_HU_GO_TO_FOREGROUND, ByteArray(0))
            }

            CarLifeProtocol.CMD_MD_EXIT -> throw CarWithProtocolException("对端退出了 CarWith")
        }

        // 特征协商：手机请求过一次（通常紧跟在证明之后）。
        // 与 Rust 一致：声明内容加密 + 声明「不支持」上行麦克风（VOICE_MIC=1）。
        if (proofOk && featuresRequested && !featuresSent) {
            featuresSent = true
            out += Action(
                CarLifeProtocol.CMD_HU_FEATURE_CONFIG_RESPONSE,
                CarLifeProto.featureConfigPayload()
            )
        }

        // 三个门（证明 / 准入 / 加密）都过了才请求开编码器。
        if (proofOk && admitted && ready && !initSent) {
            initSent = true
            val (w, h) = requestedCanvas
            out += Action(
                CarLifeProtocol.CMD_VIDEO_ENCODER_INIT,
                CarLifeProto.encoderInfoPayload(w, h, fps.coerceIn(1, 30))
            )
        }
        return out
    }

    private fun requireText(body: ByteArray, field: Int, name: String): String {
        val value = CarLifeProto.firstString(body, field)
        if (value.isNullOrBlank() || value.length > 1024) {
            throw CarWithProtocolException("$name 缺失或非法")
        }
        return value
    }
}

/** CarWith 协议层不可恢复错误：触发整轮重连。 */
internal class CarWithProtocolException(message: String) : Exception(message)
