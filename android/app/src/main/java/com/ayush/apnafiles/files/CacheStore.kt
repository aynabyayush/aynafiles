package com.ayush.apnafiles.files

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Two transient caches, both inside the app's cache dir:
 *
 *  - `thumbs/` holds the low-quality previews pulled while scrolling. Cleared as soon
 *    as the app leaves the foreground.
 *  - `open/` holds files downloaded for viewing. Cleared when the app is opened again
 *    or after a grace period once the app leaves the foreground, so the external
 *    viewer has time to read the file.
 *
 * Nothing in here is ever backed up or exposed except through the FileProvider entry
 * declared for `open/`.
 */
class CacheStore(private val context: Context) {

    val root: File = File(context.cacheDir, "apna")
    val thumbDir: File = File(root, "thumbs")
    val openDir: File = File(root, "open")

    init {
        thumbDir.mkdirs()
        openDir.mkdirs()
    }

    fun thumbFile(fileId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(fileId.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(thumbDir, name)
    }

    fun newOpenFile(name: String): File {
        val safe = sanitize(name)
        var candidate = File(openDir, safe)
        var counter = 1
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "")
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(openDir, "$base-$counter$suffix")
            counter++
        }
        return candidate
    }

    fun purgeThumbs() {
        thumbDir.listFiles()?.forEach { it.delete() }
    }

    /** Removes viewed files, keeping anything newer than [graceMs] for open viewers. */
    fun purgeOpened(graceMs: Long = 0L) {
        val cutoff = System.currentTimeMillis() - graceMs
        openDir.listFiles()?.forEach { file ->
            if (file.lastModified() <= cutoff) file.delete()
        }
    }

    fun purgeAll() {
        purgeThumbs()
        purgeOpened(0L)
    }

    fun thumbBytes(): Long = sizeOf(thumbDir)
    fun openBytes(): Long = sizeOf(openDir)
    fun totalBytes(): Long = thumbBytes() + openBytes()

    private fun sizeOf(dir: File): Long {
        val files = dir.listFiles() ?: return 0L
        var total = 0L
        for (file in files) {
            total += if (file.isDirectory) sizeOf(file) else file.length()
        }
        return total
    }

    private fun sanitize(name: String): String {
        val trimmed = name.trim().takeLast(120)
        val cleaned = trimmed.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_'
        }.joinToString("")
        return cleaned.ifBlank { "file" }
    }
}
