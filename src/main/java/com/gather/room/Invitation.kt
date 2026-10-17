package com.gather.room

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

object Invitation {
    fun encode(room: RoomState): String = "gather://join/" + JSONObject()
        .put("room", room.id)
        .put("secret", room.secret)
        .toString()

    fun decode(raw: String): Pair<String, String>? = runCatching {
        val marker = "gather://join/"
        require(raw.startsWith(marker))
        val data = JSONObject(raw.removePrefix(marker))
        val roomId = data.getString("room")
        val secret = data.getString("secret")
        UUID.fromString(roomId)
        require(secret.matches(Regex("[a-fA-F0-9]{32}")))
        roomId to secret
    }.getOrNull()

    fun qr(text: String, size: Int = 720): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to StandardCharsets.UTF_8.name())
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            for (y in 0 until size) for (x in 0 until size) setPixel(x, y, if (matrix[x, y]) 0xff172b26.toInt() else 0xffffffff.toInt())
        }
    }
}
