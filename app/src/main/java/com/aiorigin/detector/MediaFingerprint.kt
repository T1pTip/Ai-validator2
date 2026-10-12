package com.aiorigin.detector

import java.io.InputStream
import java.security.MessageDigest

/** Streamed SHA-256 of the actual bytes. Does not upload or retain media. */
object MediaFingerprint {
    fun sha256(stream: InputStream, maxBytes: Long = 1_073_741_824L): String? {
        require(maxBytes > 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) return null
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            count += read
            if (count > maxBytes) return null
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
