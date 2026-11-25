package com.astryxtv.installer.adb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

interface AdbSession : Closeable {
    val host: String
    val port: Int
    fun shell(command: String, timeoutMs: Int = 60_000): String
    fun pushFile(
        localFile: File,
        remotePath: String,
        onProgress: (bytesSent: Long, totalBytes: Long, method: String) -> Unit = { _, _, _ -> }
    )
}

class AdbException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Pure Kotlin ADB-over-TCP client supporting:
 * - CNXN / AUTH (RSA-2048 signature + TV approval prompt public key fallback)
 * - Classic shell streams (matching CleanYourTV's adb_shell transport)
 * - Fast binary ADB SYNC push with MD5 verification and automatic b64push fallback
 */
class AdbClient(
    override val host: String,
    override val port: Int = 5555,
    private val keyManager: AdbKeyManager
) : AdbSession {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: BufferedOutputStream? = null
    private var maxData: Int = DEFAULT_MAX_DATA
    private val nextLocalId = AtomicInteger(1)

    @Synchronized
    fun connect(
        connectTimeoutMs: Int = 8_000,
        authTimeoutMs: Int = 90_000,
        onAwaitingTvApproval: () -> Unit = {}
    ): String {
        close()
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = connectTimeoutMs
            socket = s
            input = DataInputStream(BufferedInputStream(s.getInputStream()))
            output = BufferedOutputStream(s.getOutputStream())

            // Send CNXN with classic host:: banner so shell streams return plain raw output
            writeMessage(A_CNXN, A_VERSION, REQUESTED_MAX_DATA, "host::\u0000".toByteArray(Charsets.US_ASCII))

            var sentSignature = false
            var sentPubKey = false

            while (true) {
                val msg = readMessage()
                when (msg.command) {
                    A_CNXN -> {
                        maxData = msg.arg1.coerceIn(4096, REQUESTED_MAX_DATA)
                        s.soTimeout = 60_000
                        return String(msg.data, Charsets.UTF_8).trimEnd('\u0000')
                    }
                    A_AUTH -> {
                        if (msg.arg0 != AUTH_TOKEN) {
                            throw AdbException("Unexpected ADB AUTH type: ${msg.arg0}")
                        }
                        if (!sentSignature) {
                            sentSignature = true
                            val sig = keyManager.signToken(msg.data)
                            writeMessage(A_AUTH, AUTH_SIGNATURE, 0, sig)
                        } else if (!sentPubKey) {
                            sentPubKey = true
                            s.soTimeout = authTimeoutMs
                            onAwaitingTvApproval()
                            writeMessage(A_AUTH, AUTH_RSAPUBLICKEY, 0, keyManager.getAdbPublicKeyPayload())
                        } else {
                            throw AdbException("TV rejected ADB authentication. Check 'Allow USB debugging' on the TV screen.")
                        }
                    }
                    else -> {
                        throw AdbException("Unexpected ADB handshake reply: 0x${msg.command.toString(16)}")
                    }
                }
            }
        } catch (e: Exception) {
            close()
            if (e is AdbException) throw e
            throw AdbException(describeConnectError(e), e)
        }
    }

    @Synchronized
    override fun shell(command: String, timeoutMs: Int): String {
        val s = socket ?: throw AdbException("Not connected to TV.")
        val prevTimeout = s.soTimeout
        s.soTimeout = timeoutMs
        val localId = nextLocalId.getAndIncrement()
        val buf = ByteArrayOutputStream()
        try {
            val dest = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
            writeMessage(A_OPEN, localId, 0, dest)

            var remoteId = 0
            while (true) {
                val msg = readMessage()
                when (msg.command) {
                    A_OKAY -> {
                        if (msg.arg1 == localId) {
                            remoteId = msg.arg0
                        }
                    }
                    A_WRTE -> {
                        if (msg.arg1 == localId) {
                            if (remoteId == 0) remoteId = msg.arg0
                            buf.write(msg.data)
                            writeMessage(A_OKAY, localId, remoteId, EMPTY)
                        }
                    }
                    A_CLSE -> {
                        if (msg.arg1 == localId) {
                            if (remoteId != 0) {
                                runCatching { writeMessage(A_CLSE, localId, remoteId, EMPTY) }
                            }
                            break
                        }
                    }
                    else -> {
                        // Ignore unrelated frames if any
                    }
                }
            }
            return buf.toString(Charsets.UTF_8.name())
        } catch (e: Exception) {
            if (e is AdbException) throw e
            throw AdbException("ADB shell command failed: ${e.message}", e)
        } finally {
            runCatching { s.soTimeout = prevTimeout }
        }
    }

    @Synchronized
    override fun pushFile(
        localFile: File,
        remotePath: String,
        onProgress: (bytesSent: Long, totalBytes: Long, method: String) -> Unit
    ) {
        if (!localFile.isFile) {
            throw AdbException("APK file not found: ${localFile.absolutePath}")
        }
        val expectedMd5 = computeMd5(localFile)

        // Try fast ADB SYNC binary push first (~1-2s for 10 MB on modern TVs), verified via md5sum.
        val syncError = runCatching {
            syncPush(localFile, remotePath) { sent, total ->
                onProgress(sent, total, "ADB Sync")
            }
            val remoteMd5 = shell("md5sum '$remotePath'", timeoutMs = 90_000).trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            if (remoteMd5 != expectedMd5) {
                throw AdbException("MD5 mismatch after ADB sync (remote=$remoteMd5, local=$expectedMd5)")
            }
        }.exceptionOrNull()

        if (syncError == null) return

        // Automatic fallback: CleanYourTV's battle-tested b64push protocol (MD5-verified + TCL SELinux cp fallback).
        b64PushFallback(localFile, remotePath, expectedMd5) { sent, total ->
            onProgress(sent, total, "Base64 Safe Push")
        }
    }

    private fun syncPush(
        localFile: File,
        remotePath: String,
        onProgress: (Long, Long) -> Unit
    ) {
        val s = socket ?: throw AdbException("Not connected to TV.")
        val prevTimeout = s.soTimeout
        // Keep per-packet timeout tight so TVs that stall on ADB sync fall back quickly to b64push.
        s.soTimeout = 12_000
        val localId = nextLocalId.getAndIncrement()
        val total = localFile.length()
        try {
            writeMessage(A_OPEN, localId, 0, "sync:\u0000".toByteArray(Charsets.US_ASCII))
            var remoteId = 0
            while (remoteId == 0) {
                val msg = readMessage()
                when (msg.command) {
                    A_OKAY -> if (msg.arg1 == localId) remoteId = msg.arg0
                    A_CLSE -> if (msg.arg1 == localId) throw AdbException("TV refused sync: stream")
                }
            }

            fun sendSyncPayload(bytes: ByteArray) {
                var off = 0
                while (off < bytes.size) {
                    val len = minOf(maxData, bytes.size - off)
                    val slice = if (off == 0 && len == bytes.size) bytes else bytes.copyOfRange(off, off + len)
                    writeMessage(A_WRTE, localId, remoteId, slice)
                    // Wait for OKAY flow-control ack for this WRTE
                    while (true) {
                        val ack = readMessage()
                        if (ack.command == A_OKAY && ack.arg1 == localId) break
                        if (ack.command == A_CLSE && ack.arg1 == localId) {
                            throw AdbException("TV closed sync stream mid-transfer")
                        }
                    }
                    off += len
                }
            }

            // SEND header: "SEND" + uint32(len) + "<remotePath>,33188" (0100644 regular file)
            val pathSpec = "$remotePath,33188".toByteArray(Charsets.UTF_8)
            val sendHdr = ByteBuffer.allocate(8 + pathSpec.size).order(ByteOrder.LITTLE_ENDIAN)
            sendHdr.put("SEND".toByteArray(Charsets.US_ASCII))
            sendHdr.putInt(pathSpec.size)
            sendHdr.put(pathSpec)
            sendSyncPayload(sendHdr.array())

            // DATA chunks (max 64 KB per sync DATA packet)
            val chunkBuf = ByteArray(SYNC_DATA_MAX)
            var sent = 0L
            FileInputStream(localFile).use { fis ->
                while (true) {
                    val n = fis.read(chunkBuf)
                    if (n <= 0) break
                    val pkt = ByteBuffer.allocate(8 + n).order(ByteOrder.LITTLE_ENDIAN)
                    pkt.put("DATA".toByteArray(Charsets.US_ASCII))
                    pkt.putInt(n)
                    pkt.put(chunkBuf, 0, n)
                    sendSyncPayload(pkt.array())
                    sent += n
                    onProgress(sent, total)
                }
            }

            // DONE header: "DONE" + uint32(mtime seconds)
            val doneHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            doneHdr.put("DONE".toByteArray(Charsets.US_ASCII))
            doneHdr.putInt((localFile.lastModified() / 1000L).toInt())
            sendSyncPayload(doneHdr.array())

            // Read sync response ("OKAY" + 4 bytes, or "FAIL" + len + message)
            val reply = ByteArrayOutputStream()
            while (reply.size() < 8) {
                val msg = readMessage()
                if (msg.command == A_WRTE && msg.arg1 == localId) {
                    reply.write(msg.data)
                    writeMessage(A_OKAY, localId, remoteId, EMPTY)
                } else if (msg.command == A_CLSE && msg.arg1 == localId) {
                    break
                }
            }
            runCatching { writeMessage(A_CLSE, localId, remoteId, EMPTY) }
            // Drain possible CLSE echo if already queued without blocking long
            val resp = reply.toByteArray()
            if (resp.size < 4) throw AdbException("Empty sync response from TV")
            val tag = String(resp, 0, 4, Charsets.US_ASCII)
            if (tag != "OKAY") {
                val errMsg = if (resp.size > 8) String(resp, 8, resp.size - 8, Charsets.UTF_8) else tag
                throw AdbException("ADB sync rejected upload: $errMsg")
            }
        } finally {
            runCatching { s.soTimeout = prevTimeout }
        }
    }

    private fun b64PushFallback(
        localFile: File,
        remotePath: String,
        expectedMd5: String,
        onProgress: (Long, Long) -> Unit
    ) {
        val b64Remote = "$remotePath.b64.part"
        val partRemote = "$remotePath.part"
        val total = localFile.length()
        // 24 KB raw -> ~32 KB base64 (multiple of 3 so chunks never add mid-stream '=' padding; CleanYourTV #3871)
        val rawChunkSize = 24 * 1024
        val encoder = Base64.getEncoder()

        try {
            shell("rm -f '$b64Remote' '$partRemote' && : > '$b64Remote'")
            val buf = ByteArray(rawChunkSize)
            var sent = 0L
            var idx = 0
            FileInputStream(localFile).use { fis ->
                while (true) {
                    val n = readFully(fis, buf)
                    if (n <= 0) break
                    val slice = if (n == buf.size) buf else buf.copyOf(n)
                    val encoded = encoder.encodeToString(slice)
                    val out = shell("echo '$encoded' >> '$b64Remote' && echo ok$idx", timeoutMs = 60_000)
                    if (!out.contains("ok$idx")) {
                        throw AdbException("Base64 upload chunk $idx failed: ${out.trim()}")
                    }
                    sent += n
                    idx++
                    onProgress(sent, total)
                }
            }
            val verifyOut = shell(
                "base64 -d < '$b64Remote' > '$partRemote' && md5sum '$partRemote'; rm -f '$b64Remote'",
                timeoutMs = 120_000
            )
            val gotMd5 = verifyOut.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            if (gotMd5 != expectedMd5) {
                throw AdbException("Uploaded APK failed checksum (TV=$gotMd5, local=$expectedMd5).")
            }
            val mvOut = shell("mv -f '$partRemote' '$remotePath' && echo moved", timeoutMs = 60_000)
            if (mvOut.lines().none { it.trim() == "moved" }) {
                // TCL SELinux fallback (CleanYourTV #1588): cp when rename(2) is refused in /data/local/tmp
                val cpOut = shell("cp -f '$partRemote' '$remotePath' && md5sum '$remotePath'", timeoutMs = 120_000)
                val cpMd5 = cpOut.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                if (cpMd5 != expectedMd5) {
                    throw AdbException("Failed to move or copy verified APK on TV: ${cpOut.trim()}")
                }
            }
        } finally {
            runCatching { shell("rm -f '$b64Remote' '$partRemote'", timeoutMs = 15_000) }
        }
    }

    @Synchronized
    override fun close() {
        runCatching { output?.close() }
        runCatching { input?.close() }
        runCatching { socket?.close() }
        output = null
        input = null
        socket = null
    }

    private fun writeMessage(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val out = output ?: throw AdbException("ADB stream closed")
        val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(command)
        hdr.putInt(arg0)
        hdr.putInt(arg1)
        hdr.putInt(data.size)
        hdr.putInt(checksum(data))
        hdr.putInt(command xor -1)
        out.write(hdr.array())
        if (data.isNotEmpty()) {
            out.write(data)
        }
        out.flush()
    }

    private fun readMessage(): AdbMessage {
        val inp = input ?: throw AdbException("ADB stream closed")
        val hdrBytes = ByteArray(24)
        inp.readFully(hdrBytes)
        val hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = hdr.int
        val arg0 = hdr.int
        val arg1 = hdr.int
        val dataLen = hdr.int
        @Suppress("UNUSED_VARIABLE")
        val dataCrc = hdr.int
        val magic = hdr.int
        if (magic != (command xor -1)) {
            throw AdbException("Corrupt ADB header magic (cmd=0x${command.toString(16)})")
        }
        if (dataLen < 0 || dataLen > MAX_ALLOWED_PACKET) {
            throw AdbException("Invalid ADB payload size: $dataLen")
        }
        val payload = ByteArray(dataLen)
        if (dataLen > 0) {
            inp.readFully(payload)
        }
        return AdbMessage(command, arg0, arg1, payload)
    }

    private fun describeConnectError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("ECONNREFUSED", ignoreCase = true) || msg.contains("Connection refused", ignoreCase = true) ->
                "Connection refused at $host:$port. Enable USB / Network Debugging in your TV's Developer options."
            msg.contains("timed out", ignoreCase = true) || msg.contains("Timeout", ignoreCase = true) ->
                "Timed out reaching $host:$port. Make sure your phone and TV are on the same Wi-Fi and accept any debugging prompt on the TV."
            msg.contains("EHOSTUNREACH", ignoreCase = true) || msg.contains("ENETUNREACH", ignoreCase = true) ->
                "Cannot reach $host on this network. Verify the TV's IP address in TV Settings → Network."
            else -> "Could not connect to $host:$port: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private data class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray
    )

    companion object {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257

        const val A_VERSION = 0x01000000
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        private const val DEFAULT_MAX_DATA = 4096
        private const val REQUESTED_MAX_DATA = 256 * 1024
        private const val MAX_ALLOWED_PACKET = 1024 * 1024
        private const val SYNC_DATA_MAX = 64 * 1024
        private val EMPTY = ByteArray(0)

        fun checksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) {
                sum += b.toInt() and 0xFF
            }
            return sum
        }

        fun computeMd5(file: File): String {
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(65536)
            FileInputStream(file).use { fis ->
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun readFully(fis: FileInputStream, buf: ByteArray): Int {
            var off = 0
            while (off < buf.size) {
                val n = fis.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            return off
        }
    }
}
