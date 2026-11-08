package app.yomu.data.backup

import java.io.File
import java.io.IOException
import java.nio.file.StandardCopyOption

/**
 * Crash-safe-ish publish for local base files: write to a sibling temp file, verify the length,
 * then replace the target with a move. Unlike [android.util.AtomicFile] this also works on hosts
 * (Windows) where `File.renameTo` refuses to replace an existing file, so host test runs exercise
 * the same code paths as devices. Sync bases are re-derivable from the shared documents, so the
 * weaker guarantees compared to AtomicFile's backup dance are acceptable.
 */
internal object AtomicPublish {
    fun publish(file: File, bytes: ByteArray) {
        val temp = File.createTempFile(file.nameWithoutExtension + "-", ".new", file.parentFile)
        try {
            temp.writeBytes(bytes)
            if (temp.readBytes().size != bytes.size) throw IOException("Base file was not written: ${file.name}")
            java.nio.file.Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            temp.delete()
            throw IOException("Cannot publish the base file: ${file.name}", e)
        }
    }
}
