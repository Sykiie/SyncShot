package com.gather.room

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object PhotoCatalog {
    const val MAX_PREVIEW_BYTES = 12_000

    fun hasAutomaticAccess(context: Context): Boolean {
        val permission = when {
            Build.VERSION.SDK_INT >= 33 -> android.Manifest.permission.READ_MEDIA_IMAGES
            else -> android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasLimitedAccess(context: Context): Boolean = Build.VERSION.SDK_INT >= 34 &&
        context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED &&
        !hasAutomaticAccess(context)

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Reads only date-taken metadata and content URIs; no candidate bytes leave this device. */
    fun scan(context: Context, room: RoomState): List<Candidate> {
        val resolver = context.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.RELATIVE_PATH)
        val candidates = mutableListOf<Candidate>()
        resolver.query(
            uri,
            projection,
            "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ?",
            arrayOf(room.startedAt.toString(), System.currentTimeMillis().toString()),
            "${MediaStore.Images.Media.DATE_TAKEN} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val dateIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                if (idIndex < 0 || dateIndex < 0) continue
                val path = if (pathIndex >= 0 && !cursor.isNull(pathIndex)) cursor.getString(pathIndex).orEmpty() else ""
                if (path.trimEnd('/').equals("Pictures/Gather", ignoreCase = true)) continue
                val id = cursor.getLong(idIndex)
                val capturedAt = cursor.getLong(dateIndex)
                if (capturedAt < room.startedAt || capturedAt >= System.currentTimeMillis()) continue
                val photoUri = Uri.withAppendedPath(uri, id.toString())
                val existing = room.candidates.firstOrNull { it.id == "media:$id" }
                candidates += Candidate("media:$id", photoUri.toString(), capturedAt, existing?.shared ?: false, existing?.previewPath, existing?.sharedPhotoId)
            }
        }
        val retained = room.candidates.filter { candidate -> (candidate.id.startsWith("picked:") || candidate.shared) && candidates.none { it.id == candidate.id } }
        return (candidates + retained).sortedBy { it.capturedAt }
    }

    fun addPicked(context: Context, room: RoomState, uri: Uri): Candidate? {
        val date = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
            }
        }.getOrNull() ?: exifCaptureTime(context, uri) ?: return null
        if (date < room.startedAt || date >= System.currentTimeMillis()) return null
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val id = "picked:" + java.util.UUID.nameUUIDFromBytes(uri.toString().toByteArray(Charsets.UTF_8)).toString()
        return room.candidates.firstOrNull { it.id == id } ?: Candidate(id, uri.toString(), date)
    }

    /** Resizes and repeatedly lowers JPEG quality until the preview fits a small Nearby byte payload. */
    fun createPreview(context: Context, uri: Uri, output: File): ByteArray? {
        val source = decodeThumbnail(context, uri, 900) ?: return null
        var image: Bitmap = source
        try {
            var maxSide = 480
            while (maxSide >= 120) {
                val scale = minOf(1f, maxSide.toFloat() / maxOf(image.width, image.height))
                val resized = if (scale < 1f) Bitmap.createScaledBitmap(image, (image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1), true) else image
                for (quality in listOf(64, 52, 40, 30)) {
                    val outputBytes = ByteArrayOutputStream()
                    resized.compress(Bitmap.CompressFormat.JPEG, quality, outputBytes)
                    val bytes = outputBytes.toByteArray()
                    if (bytes.size <= MAX_PREVIEW_BYTES) {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { it.write(bytes) }
                        if (resized !== image) resized.recycle()
                        return bytes
                    }
                }
                if (resized !== image) resized.recycle()
                maxSide /= 2
            }
            return null
        } finally {
            image.recycle()
        }
    }

    fun decodeThumbnail(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val sampled = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@runCatching null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(sampled.width, sampled.height))
        if (scale >= 1f) sampled else Bitmap.createScaledBitmap(sampled, (sampled.width * scale).toInt().coerceAtLeast(1), (sampled.height * scale).toInt().coerceAtLeast(1), true).also { sampled.recycle() }
    }.getOrNull()

    private fun exifCaptureTime(context: Context, uri: Uri): Long? = runCatching {
        val exif = context.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it) } ?: return@runCatching null
        val date = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_DIGITIZED)
            ?: return@runCatching null
        val offset = exif.getAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        if (!offset.isNullOrBlank()) {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ssXXX", Locale.US).parse("$date$offset")?.time
        } else {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }.parse(date)?.time
        }
    }.getOrNull()

    fun saveReceivedCopy(context: Context, file: File, displayName: String, mimeType: String = "image/jpeg"): Uri? {
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\').take(80).ifBlank { "photo" }
        val extension = when (mimeType.lowercase()) {
            "image/png" -> ".png"
            "image/heic", "image/heif" -> ".heic"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val finalName = if (safeName.contains('.')) "Gather_$safeName" else "Gather_$safeName$extension"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, finalName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gather")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val output = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            context.contentResolver.openOutputStream(output)?.use { sink -> file.inputStream().use { it.copyTo(sink) } }
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.update(output, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            output
        } catch (_: Exception) {
            context.contentResolver.delete(output, null, null)
            null
        }
    }

    fun previewData(file: File): String = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
}
