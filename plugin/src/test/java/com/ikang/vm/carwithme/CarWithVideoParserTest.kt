package com.ikang.vm.carwithme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarWith 视频组帧器的回归测试。
 *
 * 重点是「IDR 需要补缓存参数集」这条路径：CarWith 的 IDR 不一定每帧都带 SPS/PPS，
 * 组帧器会从缓存里补进去；补齐项的字节同样要计入 AU 长度。
 * 早期把补齐项只按前缀（4 字节/个）计长，漏了负载 → arraycopy 越界 → 整轮会话被拆掉、
 * 15456 一个视频帧都发不出去（现场表现就是"仪表侧没有画面"）。
 *
 * 本测试随 `com.ikang.vm.carwithme` 一起从宿主迁入 `:plugin` 模块（P1），断言未改动。
 */
class CarWithVideoParserTest {

    private val prefix = byteArrayOf(0, 0, 0, 1)

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (nal in nals) {
            out.write(prefix)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /**
     * SPS/PPS 先到达（只进缓存），之后 IDR 所在的一幅图里没有参数集 → 必须补缓存参数集。
     *
     * 时序按 CarWith 的真实包边界构造：
     * 1. SPS + PPS + 一个 VCL 片 → 参数集落缓存，片留在缓冲里；
     * 2. SEI + 下一片的头 → SEI 触发上一幅收尾（顺带把 nals 里的参数集清掉，只剩缓存）；
     * 3. IDR + 再下一片的头 → IDR 收尾时 AU 里没有参数集，走补齐分支（曾经在这里越界）。
     */
    @Test
    fun idrWithCachedParameterSets_isAssembledWithoutOverflow() {
        val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E, 0x8C.toByte(), 0x8D.toByte())
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val sliceA = byteArrayOf(0x41, 0x80.toByte(), 0x01, 0x02)
        val sei = byteArrayOf(0x06, 0x05, 0x01)
        val sliceB = byteArrayOf(0x41, 0x80.toByte(), 0x03)
        // 大一点的 IDR 负载：让"漏算负载"的旧实现必然越界，而不是靠运气逃过。
        val idr = byteArrayOf(0x65, 0x80.toByte()) + ByteArray(4_000) { 0x7F.toByte() }
        val sliceC = byteArrayOf(0x41, 0x80.toByte(), 0x04)

        val parser = CarWithVideoParser()

        parser.push(annexB(sps, pps, sliceA))

        val second = parser.push(annexB(sei, sliceB))
        assertEquals(1, second.size)
        assertArrayEquals(annexB(sps, pps, sliceA), second[0].data)
        assertTrue("首幅不是 IDR", !second[0].keyFrame)

        val third = parser.push(annexB(idr, sliceC))
        assertEquals(2, third.size)
        // 第二首（idr 收尾那幅）必须自带参数集，且总长度 = 参数集 + IDR 负载
        val idrUnit = third[1]
        assertTrue("IDR 应标记为关键帧", idrUnit.keyFrame)
        assertEquals(VideoCodec.H264, idrUnit.codec)
        assertArrayEquals(annexB(sps, pps, idr), idrUnit.data)
    }

    /** IDR 自己带 SPS/PPS 时不能重复补（否则接收端会多一份参数集）。 */
    @Test
    fun idrWithInBandParameterSets_isNotPrefixedTwice() {
        val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E, 0x8C.toByte(), 0x8D.toByte())
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val idr = byteArrayOf(0x65, 0x80.toByte()) + ByteArray(64) { 0x11.toByte() }
        val next = byteArrayOf(0x41, 0x80.toByte(), 0x09)

        val parser = CarWithVideoParser()
        // 同一包里 SPS → PPS → IDR（首片）+ 下一片头：IDR 处 vcl 仍为 false，不会先收尾，
        // 因此参数集与 IDR 落在同一幅 AU 里。
        val units = parser.push(annexB(sps, pps, idr, next))
        assertEquals(1, units.size)
        assertTrue(units[0].keyFrame)
        assertArrayEquals(annexB(sps, pps, idr), units[0].data)
    }
}
