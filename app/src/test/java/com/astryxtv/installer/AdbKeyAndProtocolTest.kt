package com.astryxtv.installer.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import kotlin.concurrent.thread

class AdbKeyAndProtocolTest {

    @Test
    fun androidPublicKeyStructMatchesAospSpec() {
        val mgr = AdbKeyManager(keyDir = null)
        val raw = mgr.getEncodedStructBytes()
        assertEquals(AdbKeyManager.STRUCT_SIZE, raw.size)

        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val words = buf.int
        val n0inv = buf.int.toLong() and 0xFFFFFFFFL
        val modLe = ByteArray(256).also { buf.get(it) }
        val rrLe = ByteArray(256).also { buf.get(it) }
        val exp = buf.int.toLong() and 0xFFFFFFFFL

        assertEquals(64, words)
        val pub = mgr.keyPair.public as RSAPublicKey
        assertEquals(pub.publicExponent.toLong(), exp)

        // Verify little-endian modulus matches RSAPublicKey.modulus
        val decodedN = BigInteger(1, modLe.reversedArray())
        assertEquals(pub.modulus, decodedN)

        // Verify Montgomery n0inv: (n0inv * N) mod 2^32 == 2^32 - 1 (-1 mod 2^32)
        val r32 = BigInteger.ONE.shiftLeft(32)
        val check = BigInteger.valueOf(n0inv).multiply(decodedN).mod(r32)
        assertEquals(r32.subtract(BigInteger.ONE), check)

        // Verify rr == (2^2048)^2 mod N
        val expectedRr = BigInteger.ONE.shiftLeft(2048).pow(2).mod(decodedN)
        assertEquals(expectedRr, BigInteger(1, rrLe.reversedArray()))

        // Verify Base64 payload format
        val payloadStr = String(mgr.getAdbPublicKeyPayload(), Charsets.US_ASCII)
        assertTrue(payloadStr.endsWith(" astryx@installer\u0000"))
        val b64 = payloadStr.substringBefore(' ')
        assertArrayEquals(raw, Base64.getDecoder().decode(b64))
    }

    @Test
    fun rsaTokenSignatureMatchesPrehashedSha1Pkcs1v15() {
        val mgr = AdbKeyManager(keyDir = null)
        val token = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val sig = mgr.signToken(token)
        assertEquals(256, sig.size)

        // Verify signature using RSA public key decrypt (matching AOSP RSA_verify)
        val verifier = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        verifier.init(Cipher.DECRYPT_MODE, mgr.keyPair.public)
        val recovered = verifier.doFinal(sig)
        assertArrayEquals(AdbKeyManager.SHA1_ASN1_PREFIX + token, recovered)
    }

    @Test
    fun adbClientHandshakeShellAndSyncPushOverRealTcp() {
        val mgr = AdbKeyManager(keyDir = null)
        val tempApk = File.createTempFile("astryx-test", ".apk").apply {
            deleteOnExit()
            // 150 KB binary file spanning multiple ADB SYNC DATA + WRTE packets
            val content = ByteArray(150_000) { i -> ((i * 31 + 7) and 0xFF).toByte() }
            content[0] = 0x50; content[1] = 0x4B // PK magic
            writeBytes(content)
        }
        val expectedMd5 = AdbClient.computeMd5(tempApk)
        val receivedFileBytes = ByteArrayOutputStream()
        var approvalCallbackFired = false

        ServerSocket(0).use { server ->
            val port = server.localPort
            val serverThread = thread(start = true) {
                server.accept().use { sock ->
                    val inp = DataInputStream(BufferedInputStream(sock.getInputStream()))
                    val out = BufferedOutputStream(sock.getOutputStream())

                    fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
                        val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                        hdr.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size)
                            .putInt(AdbClient.checksum(data)).putInt(cmd xor -1)
                        out.write(hdr.array())
                        if (data.isNotEmpty()) out.write(data)
                        out.flush()
                    }

                    fun recv(): Triple<Int, Pair<Int, Int>, ByteArray> {
                        val h = ByteArray(24)
                        inp.readFully(h)
                        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
                        b.int; b.int
                        val payload = ByteArray(len)
                        if (len > 0) inp.readFully(payload)
                        return Triple(cmd, a0 to a1, payload)
                    }

                    // 1. Expect CNXN
                    val (c1, _, _) = recv()
                    assertEquals(AdbClient.A_CNXN, c1)

                    // 2. Challenge with AUTH TOKEN twice to exercise signature + pubkey approval path
                    val tok = ByteArray(20) { it.toByte() }
                    send(AdbClient.A_AUTH, AdbClient.AUTH_TOKEN, 0, tok)
                    val (c2, a2, sigBytes) = recv()
                    assertEquals(AdbClient.A_AUTH, c2)
                    assertEquals(AdbClient.AUTH_SIGNATURE, a2.first)
                    assertEquals(256, sigBytes.size)

                    // Simulate unrecognized key -> ask for pubkey
                    send(AdbClient.A_AUTH, AdbClient.AUTH_TOKEN, 0, tok)
                    val (c3, a3, pubBytes) = recv()
                    assertEquals(AdbClient.A_AUTH, c3)
                    assertEquals(AdbClient.AUTH_RSAPUBLICKEY, a3.first)
                    assertTrue(String(pubBytes, Charsets.US_ASCII).endsWith(" astryx@installer\u0000"))

                    // Accept connection with 16 KB maxdata to test multi-packet splitting
                    send(AdbClient.A_CNXN, AdbClient.A_VERSION, 16384, "device::ro.product.model=MockTV\u0000".toByteArray())

                    // 3. Handle streams until client disconnects
                    val syncStreamBuf = ByteArrayOutputStream()
                    while (true) {
                        val msg = runCatching { recv() }.getOrNull() ?: break
                        val (cmd, args, data) = msg
                        if (cmd == AdbClient.A_OPEN) {
                            val localId = args.first
                            val dest = String(data, Charsets.UTF_8).trimEnd('\u0000')
                            val remoteId = 900 + localId
                            send(AdbClient.A_OKAY, remoteId, localId)
                            when {
                                dest.startsWith("shell:echo hello") -> {
                                    send(AdbClient.A_WRTE, remoteId, localId, "hello world\n".toByteArray())
                                    recv() // OKAY
                                    send(AdbClient.A_CLSE, remoteId, localId)
                                }
                                dest.startsWith("shell:md5sum") -> {
                                    send(AdbClient.A_WRTE, remoteId, localId, "$expectedMd5  /data/local/tmp/test.apk\n".toByteArray())
                                    recv() // OKAY
                                    send(AdbClient.A_CLSE, remoteId, localId)
                                }
                                dest == "sync:" -> {
                                    syncStreamBuf.reset()
                                    // Read WRTE frames until we parse DONE
                                    var done = false
                                    while (!done) {
                                        val (scmd, _, sdata) = recv()
                                        if (scmd == AdbClient.A_WRTE) {
                                            syncStreamBuf.write(sdata)
                                            send(AdbClient.A_OKAY, remoteId, localId)
                                            // Try parsing accumulated sync frames
                                            val all = syncStreamBuf.toByteArray()
                                            var p = 0
                                            receivedFileBytes.reset()
                                            while (p + 8 <= all.size) {
                                                val tag = String(all, p, 4, Charsets.US_ASCII)
                                                val sz = ByteBuffer.wrap(all, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                                                p += 8
                                                if (tag == "SEND" || tag == "DATA") {
                                                    if (p + sz > all.size) break
                                                    if (tag == "DATA") receivedFileBytes.write(all, p, sz)
                                                    p += sz
                                                } else if (tag == "DONE") {
                                                    done = true
                                                    break
                                                }
                                            }
                                        }
                                    }
                                    send(AdbClient.A_WRTE, remoteId, localId, "OKAY\u0000\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII))
                                    recv() // OKAY
                                    recv() // CLSE
                                }
                            }
                        }
                    }
                }
            }

            AdbClient("127.0.0.1", port, mgr).use { client ->
                val banner = client.connect(
                    onAwaitingTvApproval = { approvalCallbackFired = true }
                )
                assertTrue(approvalCallbackFired)
                assertTrue(banner.contains("MockTV"))

                val out = client.shell("echo hello")
                assertEquals("hello world\n", out)

                var lastSent = 0L
                client.pushFile(tempApk, "/data/local/tmp/test.apk") { sent, _, _ ->
                    lastSent = sent
                }
                assertEquals(tempApk.length(), lastSent)
                assertArrayEquals(tempApk.readBytes(), receivedFileBytes.toByteArray())
            }
            serverThread.join(5000)
        }
    }
}
