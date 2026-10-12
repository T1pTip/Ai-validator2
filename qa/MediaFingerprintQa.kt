import com.aiorigin.detector.MediaFingerprint
import java.io.ByteArrayInputStream

fun main() {
    fun checkCase(name: String, condition: Boolean) {
        check(condition) { name }
        println("PASS: $name")
    }
    val empty = MediaFingerprint.sha256(ByteArrayInputStream(byteArrayOf()))
    checkCase("empty SHA-256", empty == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    val abc = MediaFingerprint.sha256(ByteArrayInputStream("abc".toByteArray()))
    checkCase("SHA-256 known vector", abc == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    checkCase("size limit", MediaFingerprint.sha256(ByteArrayInputStream("abc".toByteArray()), 2) == null)
    val large = ByteArray(200_000) { (it % 251).toByte() }
    val expected = java.security.MessageDigest.getInstance("SHA-256").digest(large).joinToString("") { "%02x".format(it.toInt() and 255) }
    checkCase("multi-buffer streaming", MediaFingerprint.sha256(ByteArrayInputStream(large)) == expected)
}
