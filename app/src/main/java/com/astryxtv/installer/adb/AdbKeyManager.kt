package com.astryxtv.installer.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * Generates, persists, and uses an RSA-2048 ADB keypair matching AOSP libcrypto_utils
 * (android_pubkey_encode) and prehashed SHA-1 PKCS#1 v1.5 token signing.
 */
class AdbKeyManager(
    private val keyDir: File? = null,
    private val keyIdentity: String = "astryx@installer"
) {
    val keyPair: KeyPair = loadOrGenerateKeyPair()

    private val rsaPublicKey: RSAPublicKey = keyPair.public as RSAPublicKey
    private val rsaPrivateKey: RSAPrivateKey = keyPair.private as RSAPrivateKey

    /**
     * Encodes the RSA-2048 public key in Android's 524-byte little-endian RSAPublicKey struct,
     * Base64-encoded with the ADB user@host suffix and NUL terminator.
     */
    fun getAdbPublicKeyPayload(): ByteArray {
        val raw = encodeAndroidPubKey(rsaPublicKey)
        val b64 = Base64.getEncoder().encodeToString(raw)
        return "$b64 $keyIdentity\u0000".toByteArray(Charsets.US_ASCII)
    }

    /**
     * Raw 524-byte AOSP RSAPublicKey binary structure (useful for verification & tests).
     */
    fun getEncodedStructBytes(): ByteArray = encodeAndroidPubKey(rsaPublicKey)

    /**
     * Signs ADB's 20-byte AUTH token using PKCS#1 v1.5 over the SHA-1 DigestInfo prefix + raw token
     * (ADB treats the 20-byte token as an already-computed SHA-1 hash).
     */
    fun signToken(token: ByteArray): ByteArray {
        val digestInfo = ByteArray(SHA1_ASN1_PREFIX.size + token.size)
        System.arraycopy(SHA1_ASN1_PREFIX, 0, digestInfo, 0, SHA1_ASN1_PREFIX.size)
        System.arraycopy(token, 0, digestInfo, SHA1_ASN1_PREFIX.size, token.size)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, rsaPrivateKey)
        return cipher.doFinal(digestInfo)
    }

    private fun loadOrGenerateKeyPair(): KeyPair {
        val privFile = keyDir?.let { File(it, "adbkey.pk8") }
        val pubFile = keyDir?.let { File(it, "adbkey.x509") }

        if (privFile != null && pubFile != null && privFile.isFile && pubFile.isFile) {
            runCatching {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                return KeyPair(pub, priv)
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(KEY_BITS)
        val kp = kpg.generateKeyPair()

        val dir = keyDir
        if (dir != null && privFile != null && pubFile != null) {
            runCatching {
                dir.mkdirs()
                privFile.writeBytes(kp.private.encoded)
                pubFile.writeBytes(kp.public.encoded)
                privFile.setReadable(false, false)
                privFile.setReadable(true, true)
                privFile.setWritable(false, false)
                privFile.setWritable(true, true)
            }
        }
        return kp
    }

    companion object {
        private const val KEY_BITS = 2048
        const val MODULUS_BYTES = KEY_BITS / 8            // 256
        const val MODULUS_WORDS = MODULUS_BYTES / 4       // 64
        const val STRUCT_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4 // 524

        // DER DigestInfo prefix for SHA-1 (RFC 3447 / AOSP adbd RSA_verify)
        val SHA1_ASN1_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )

        fun encodeAndroidPubKey(pub: RSAPublicKey): ByteArray {
            val n = pub.modulus
            val e = pub.publicExponent

            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32))

            val r = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8)
            val rr = r.multiply(r).mod(n)

            val buf = ByteBuffer.allocate(STRUCT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(MODULUS_WORDS)
            buf.putInt(n0inv.toInt())
            buf.put(toLittleEndianUnsigned(n, MODULUS_BYTES))
            buf.put(toLittleEndianUnsigned(rr, MODULUS_BYTES))
            buf.putInt(e.toInt())
            return buf.array()
        }

        private fun toLittleEndianUnsigned(value: BigInteger, length: Int): ByteArray {
            val big = value.toByteArray()
            val out = ByteArray(length)
            var outIdx = 0
            for (i in big.size - 1 downTo 0) {
                if (outIdx >= length) break
                out[outIdx++] = big[i]
            }
            return out
        }
    }
}
