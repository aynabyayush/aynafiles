package com.ayush.apnafiles.files

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.ayush.apnafiles.data.Prefs
import com.ayush.apnafiles.net.RemoteFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Everything Apna Files is willing to hand to a paired device:
 *
 *  - images / video / audio from MediaStore (permission gated), and
 *  - files placed in `Android/data/<app>/files/Shared`, which is reachable through a
 *    file manager and is used for documents, archives, anything else.
 *
 * Only metadata and bytes are exposed; nothing outside these sources is visible.
 */
class MediaRepo(private val context: Context, private val prefs: Prefs) {

    data class Handle(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mime: String,
        val kind: String
    )

    val sharedDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "Shared").apply { mkdirs() }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun hasPhotosPermission(): Boolean = granted(Manifest.permission.READ_MEDIA_IMAGES, 33)
    fun hasVideosPermission(): Boolean = granted(Manifest.permission.READ_MEDIA_VIDEO, 33)
    fun hasAudioPermission(): Boolean = granted(Manifest.permission.READ_MEDIA_AUDIO, 33)

    fun hasAnyMediaPermission(): Boolean =
        hasPhotosPermission() || hasVideosPermission() || hasAudioPermission()

    private fun granted(permission: String, minSdk: Int): Boolean {
        if (Build.VERSION.SDK_INT < minSdk) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Blocking; call from a background dispatcher. */
    fun list(): List<RemoteFile> {
        val files = ArrayList<RemoteFile>(512)
        if (prefs.sharePhotos.value && hasPhotosPermission()) {
            files += queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, KIND_IMAGE)
        }
        if (prefs.shareVideos.value && hasVideosPermission()) {
            files += queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, KIND_VIDEO)
        }
        if (prefs.shareAudio.value && hasAudioPermission()) {
            files += queryMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, KIND_AUDIO)
        }
        if (prefs.shareFolder.value) {
            files += listSharedFolder()
        }
        return files.sortedByDescending { it.modified }.take(MAX_ENTRIES)
    }

    private fun queryMedia(collection: Uri, kind: String): List<RemoteFile> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val result = ArrayList<RemoteFile>()
        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val folder = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""
                    result += RemoteFile(
                        id = "$kind:$id",
                        name = cursor.getString(nameIndex) ?: "file",
                        size = cursor.getLong(sizeIndex),
                        mime = cursor.getString(mimeIndex).orEmpty(),
                        kind = kind,
                        folder = folder.trimEnd('/'),
                        modified = cursor.getLong(dateIndex) * 1000L
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the query.
        } catch (_: Exception) {
            // A broken provider must not break the whole listing.
        }
        return result
    }

    private fun listSharedFolder(): List<RemoteFile> {
        val root = sharedDir
        if (!root.exists()) return emptyList()
        val result = ArrayList<RemoteFile>()
        root.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && !it.name.startsWith(".") }
            .take(MAX_ENTRIES)
            .forEach { file ->
                val relative = file.relativeTo(root).path
                result += RemoteFile(
                    id = "$KIND_FILE:$relative",
                    name = file.name,
                    size = file.length(),
                    mime = guessMime(file.name),
                    kind = kindOf(file.name),
                    folder = "Shared/" + (file.parentFile?.relativeTo(root)?.path?.takeIf { it.isNotEmpty() } ?: "").trimEnd('/'),
                    modified = file.lastModified()
                )
            }
        return result
    }

    fun describe(fileId: String): Handle? {
        val kind = fileId.substringBefore(':', "")
        val rest = fileId.substringAfter(':', "")
        if (rest.isEmpty()) return null
        return when (kind) {
            KIND_IMAGE, KIND_VIDEO, KIND_AUDIO -> {
                val id = rest.toLongOrNull() ?: return null
                val collection = when (kind) {
                    KIND_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    KIND_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val uri = ContentUris.withAppendedId(collection, id)
                val file = querySingle(uri, kind) ?: return null
                Handle(uri, file.name, file.size, file.mime, kind)
            }
            KIND_FILE -> {
                val file = resolveShared(rest) ?: return null
                if (!file.isFile) return null
                Handle(Uri.fromFile(file), file.name, file.length(), guessMime(file.name), "file")
            }
            else -> null
        }
    }

    private fun querySingle(uri: Uri, kind: String): RemoteFile? {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                RemoteFile(
                    id = uri.lastPathSegment ?: "",
                    name = cursor.getString(0) ?: "file",
                    size = cursor.getLong(1),
                    mime = cursor.getString(2).orEmpty(),
                    kind = kind
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveShared(relative: String): File? {
        val root = sharedDir.canonicalFile
        val target = File(root, relative).canonicalFile
        return if (target.path.startsWith(root.path + File.separator)) target else null
    }

    fun openStream(fileId: String): InputStream? {
        val handle = describe(fileId) ?: return null
        return if (handle.uri.scheme == "file") {
            FileInputStream(File(handle.uri.path!!))
        } else {
            context.contentResolver.openInputStream(handle.uri)
        }
    }

    /** Small JPEG preview, or null when the type has no useful thumbnail. */
    fun thumbnail(fileId: String, maxPx: Int): ByteArray? {
        val handle = describe(fileId) ?: return null
        val bitmap = try {
            if (handle.uri.scheme == "content") {
                context.contentResolver.loadThumbnail(handle.uri, Size(maxPx, maxPx), null)
            } else {
                decodeScaled(File(handle.uri.path!!), maxPx)
            }
        } catch (_: Exception) {
            null
        } ?: return null

        val scaled = scaleDown(bitmap, maxPx)
        val out = ByteArrayOutputStream()
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, 60, out)
        if (!scaled.isRecycled && scaled !== bitmap) scaled.recycle()
        if (bitmap !== scaled && !bitmap.isRecycled) bitmap.recycle()
        return if (ok) out.toByteArray() else null
    }

    private fun decodeScaled(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun scaleDown(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxPx) return bitmap
        val ratio = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    /** Copies a user-picked document into the shared folder so paired devices can see it. */
    fun importIntoShared(uri: Uri, name: String): File? {
        return try {
            val target = File(sharedDir, name.substringAfterLast('/').ifBlank { "file" })
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
            target
        } catch (_: Exception) {
            null
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun kindOf(name: String): String = when (guessMime(name).substringBefore('/')) {
        "image" -> KIND_IMAGE
        "video" -> KIND_VIDEO
        "audio" -> KIND_AUDIO
        else -> "file"
    }

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
        const val KIND_AUDIO = "audio"
        const val KIND_FILE = "file"
        private const val MAX_ENTRIES = 4000
    }
}
