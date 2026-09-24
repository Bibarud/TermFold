package com.termfold.app.acp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Turns a picked or pasted image into something an agent can take.
 *
 * Camera photos run to 5–12 MB. Sent raw they become a single multi-megabyte JSON-RPC line and
 * exceed what models accept for one image, so anything larger than [MAX_EDGE] on its long side is
 * scaled down. Opaque images are re-encoded as JPEG; images with transparency (screenshots of UI
 * mock-ups, diagrams) stay PNG so the alpha is not flattened to black.
 */
object PastedImages {

    private const val MAX_EDGE = 1600
    private const val JPEG_QUALITY = 88

    data class Encoded(val bytes: ByteArray, val mimeType: String) {
        val extension: String get() = if (mimeType == "image/png") "png" else "jpg"
        fun toBlock(): AcpBlock.Image = AcpBlock.Image(Base64.encodeToString(bytes, Base64.NO_WRAP), mimeType)
    }

    /** True when [uri] resolves to an image the content resolver can open. */
    fun isImage(context: Context, uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("image/") == true

    fun load(context: Context, uri: Uri): Encoded? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        val png = bitmap.hasAlpha()
        val out = ByteArrayOutputStream()
        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        Encoded(out.toByteArray(), if (png) "image/png" else "image/jpeg")
    }.getOrNull()
}
