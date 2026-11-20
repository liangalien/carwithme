/*
 * CarWith 协议适配层 —— 派生自 cardisplay2（GNU GPL v3.0），已按本项目需要修改。
 * 许可证全文见模块根 LICENSE，并随 APK 打包在 assets/GPL-3.0.txt。
 * 本模块整体按 GPL-3.0 分发；与宿主（app）之间只通过 :plugin-api 的 AIDL 契约往来。
 */
package com.ikang.vm.carwithme

import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * CarWith 握手/内容加密用到的密码学原语。
 *
 * 与 cardisplay `rust-core/src/session.rs` 的 `crypt` / `md5` / `rsa` 三段一一对应：
 * - 挑战证明：`md5("CARLIFE" + brand + model + sdk + challenge[4..])` 的小写十六进制；
 * - 密钥交换：RSA-2048（PKCS#1 v1.5）下发 base64(X.509 SubjectPublicKeyInfo DER)，
 *   手机用它加密 16/24/32 字节的 AES seed 回传；
 * - 内容加密：AES-ECB 无填充模式 + 手写 PKCS#7（补 `16 - len % 16`，解密校验末字节）。
 *
 * 所有方法都是无状态的纯函数，只有 [KeyPair] 由调用方（[CarWithSession]）持有。
 */
internal object CarLifeCrypto {

    /** MD5 摘要的小写十六进制（32 字符），用作挑战证明。 */
    fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** 生成 2048 位 RSA 密钥对（Rust 侧同样用 2048）。 */
    fun newRsaKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    /**
     * 公钥的 base64（单行无换行）编码。
     *
     * `PublicKey.encoded` 就是 X.509 SubjectPublicKeyInfo DER，和 Rust 的
     * `to_public_key().to_public_key_der()` 输出同一份字节，手机侧按 DER 解析。
     */
    fun publicKeyDerBase64(keyPair: KeyPair): String =
        Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

    /**
     * 用 RSA 私钥解 `MD_AES_SEED` 里的 seed（PKCS#1 v1.5 填充）。
     *
     * @param encrypted base64 解码后的密文
     */
    fun rsaDecrypt(keyPair: KeyPair, encrypted: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private)
        return cipher.doFinal(encrypted)
    }

    /** 标准 base64 解码（手机侧只发标准表，无换行）。 */
    fun base64Decode(text: String): ByteArray = Base64.decode(text, Base64.DEFAULT)

    /**
     * AES-ECB + PKCS#7 的加/解密。
     *
     * 与 Rust `crypt` 完全等价：
     * - `bytes` 为空或还没拿到 seed（加密协商完成前的 CMD）→ **原样返回**，不补 padding，
     *   否则握手前半程的明文消息会被无谓地拉长成 16 的倍数；
     * - 加密：补 `16 - len % 16` 个值为该数的字节；
     * - 解密：要求 16 字节对齐；padding 非法时**不做截断**（原样返回解密结果）——宁可让解码器
     *   遇到几个多余字节，也好过整帧被丢弃导致画面永久卡住。
     */
    fun crypt(bytes: ByteArray, seed: ByteArray?, encrypt: Boolean): ByteArray {
        if (bytes.isEmpty() || seed == null) return bytes
        var data = bytes
        if (encrypt) {
            val pad = 16 - data.size % 16
            val padded = data.copyOf(data.size + pad)
            java.util.Arrays.fill(padded, data.size, padded.size, pad.toByte())
            data = padded
        } else if (data.size % 16 != 0) {
            throw IllegalArgumentException("AES body not block aligned: ${data.size}")
        }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(seed, "AES")
        )
        val out = cipher.doFinal(data)
        if (encrypt) return out
        val pad = out.last().toInt() and 0xFF
        val valid = pad in 1..16 && pad <= out.size && (out.size - pad until out.size).all {
            (out[it].toInt() and 0xFF) == pad
        }
        return if (valid) out.copyOf(out.size - pad) else out
    }
}
