/*
 * CarWith 协议适配层 —— 派生自 cardisplay2（GNU GPL v3.0），已按本项目需要修改。
 * 许可证全文见模块根 LICENSE，并随 APK 打包在 assets/GPL-3.0.txt。
 * 本模块整体按 GPL-3.0 分发；与宿主（app）之间只通过 :plugin-api 的 AIDL 契约往来。
 */
package com.ikang.vm.carwithme

import java.io.ByteArrayOutputStream

/**
 * CarLife 握手用到的极小 protobuf 读写器。
 *
 * CarLife 的 CMD payload 都是 protobuf，但只用到 varint / length-delimited 两种线型，
 * 字段集合又完全固定，因此不引入 protobuf 运行时（与 cardisplay 的 `proto` 模块一致，
 * 见 `rust-core/src/carlife.rs`）。
 */
internal object CarLifeProto {

    // ---------------------------------------------------------------- 写

    private fun putVarint(out: ByteArrayOutputStream, valueIn: Long) {
        var value = valueIn
        while (true) {
            var b = (value and 0x7F).toInt()
            value = value ushr 7
            if (value != 0L) b = b or 0x80
            out.write(b)
            if (value == 0L) break
        }
    }

    fun varint(field: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream(16)
        putVarint(out, (field.toLong() shl 3) or 0L)
        putVarint(out, value)
        return out.toByteArray()
    }

    fun string(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size + 8)
        putVarint(out, (field.toLong() shl 3) or 2L)
        putVarint(out, bytes.size.toLong())
        out.write(bytes)
        return out.toByteArray()
    }

    fun bytes(field: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(value.size + 8)
        putVarint(out, (field.toLong() shl 3) or 2L)
        putVarint(out, value.size.toLong())
        out.write(value)
        return out.toByteArray()
    }

    fun boolean(field: Int, value: Boolean): ByteArray = varint(field, if (value) 1L else 0L)

    fun concat(vararg chunks: ByteArray): ByteArray {
        var size = 0
        for (c in chunks) size += c.size
        val out = ByteArray(size)
        var offset = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, offset, c.size)
            offset += c.size
        }
        return out
    }

    // ---------------------------------------------------------------- 读

    private class Reader(private val buf: ByteArray) {
        var pos = 0

        fun varint(): Long? {
            var result = 0L
            var shift = 0
            while (pos < buf.size && shift < 64) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null
        }

        fun skip(wire: Int): Boolean {
            when (wire) {
                0 -> return varint() != null
                1 -> pos += 8
                5 -> pos += 4
                2 -> {
                    val len = varint() ?: return false
                    if (len < 0 || pos + len > buf.size) return false
                    pos += len.toInt()
                }
                else -> return false
            }
            return pos <= buf.size
        }
    }

    /** 取第一个指定 field 的 length-delimited 字符串（与 Rust `pb_first_string` 等价）。 */
    fun firstString(payload: ByteArray, wantField: Int): String? {
        val r = Reader(payload)
        while (r.pos < payload.size) {
            val key = r.varint() ?: return null
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            if (wire == 2) {
                val len = r.varint() ?: return null
                if (len < 0 || r.pos + len > payload.size) return null
                val end = r.pos + len.toInt()
                if (field == wantField) {
                    return String(payload, r.pos, end - r.pos, Charsets.UTF_8)
                }
                r.pos = end
            } else if (!r.skip(wire)) {
                return null
            }
        }
        return null
    }

    /** 取第一个指定 field 的 varint 值。 */
    fun firstInt(payload: ByteArray, wantField: Int): Long? {
        val r = Reader(payload)
        while (r.pos < payload.size) {
            val key = r.varint() ?: return null
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            if (wire == 0) {
                val value = r.varint() ?: return null
                if (field == wantField) return value
            } else if (!r.skip(wire)) {
                return null
            }
        }
        return null
    }

    /**
     * 解析 `CarlifeVideoEncoderInfo{1:width, 2:height, 3:frameRate}`。
     *
     * 手机在 `VIDEO_ENCODER_INIT_DONE` 里用同一结构回报**实际**采用的尺寸；
     * 缺失字段按 0 返回（调用方沿用请求值）。
     */
    fun parseEncoderInfo(payload: ByteArray): IntArray {
        var width = 0
        var height = 0
        val r = Reader(payload)
        while (r.pos < payload.size) {
            val key = r.varint() ?: break
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            if (wire == 0) {
                val value = r.varint() ?: break
                when (field) {
                    1 -> width = value.toInt()
                    2 -> height = value.toInt()
                }
            } else if (!r.skip(wire)) {
                break
            }
        }
        return intArrayOf(width, height)
    }

    // ---------------------------------------------------------------- 具体消息体

    /** `CarlifeProtocolVersion{1:major, 2:minor}`。 */
    fun versionPayload(major: Int, minor: Int): ByteArray =
        concat(varint(1, major.toLong()), varint(2, minor.toLong()))

    /** `CarlifeStatisticsInfo{1:cuid, 2:version, 3:code, 4:channel, 5..7:计数}`。 */
    fun statisticPayload(): ByteArray = concat(
        string(1, "hypercar"),
        string(2, "4.0.0"),
        varint(3, 16),
        string(4, "20352101"),
        varint(5, 0),
        varint(6, 0),
        varint(7, 0),
    )

    /**
     * `CarlifeDeviceInfo`（HU 方向）：1=os 13=manufacturer 14=model 19=release
     * 23=btaddress（required） 24=btname（required）。
     *
     * 23/24 是 required：缺了手机侧解析必然异常，握手会停在这里。
     */
    fun huInfoPayload(btAddress: String, btName: String): ByteArray = concat(
        string(1, "Android"),
        string(13, "HyperCar"),
        string(14, "HyperCar HU"),
        string(19, "14"),
        string(23, btAddress),
        string(24, btName),
    )

    fun authenResultPayload(ok: Boolean): ByteArray = boolean(1, ok)

    fun authenRequestPayload(challenge: String): ByteArray = string(1, challenge)

    fun encoderInfoPayload(width: Int, height: Int, fps: Int): ByteArray = concat(
        varint(1, width.toLong()),
        varint(2, height.toLong()),
        varint(3, fps.toLong()),
    )

    /**
     * 触摸点：`CarlifeTouchPoint{1:x, 2:y}`（参照 cardisplay 的 `TouchGesture::accept`）。
     *
     * 两个字段都是 **uint32 varint 的绝对像素**，坐标空间是手机回报的实际编码画布；
     * 动作由 CTRL 消息 ID 表达（0x68002 按下 / 0x68004 移动 / 0x68003 抬起），
     * 所以正文里没有 action 字段。
     */
    fun touchPayload(x: Int, y: Int): ByteArray = concat(
        varint(1, x.toLong()),
        varint(2, y.toLong()),
    )

    /**
     * `CarlifeFeatureConfigList{1:cnt, 2:repeated{1:key, 2:value}}`。
     *
     * 与 Rust 侧 `session::receive` 的回复逐字节一致：
     * - `CONTENT_ENCRYPTION=1`：内容加密（视频帧 AES-ECB）；
     * - `VOICE_MIC=1`：**声明不支持**上行麦克风（值为 0 才是支持，Rust 有麦克风时才发 0）。
     *   这一项不能省：不声明时手机侧行为与 Rust 实测路径不一致，且 cnt 要跟着变。
     */
    fun featureConfigPayload(): ByteArray {
        val encryption = concat(string(1, "CONTENT_ENCRYPTION"), varint(2, 1))
        val mic = concat(string(1, "VOICE_MIC"), varint(2, 1))
        return concat(varint(1, 2), bytes(2, encryption), bytes(2, mic))
    }
}
