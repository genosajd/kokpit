package pl.lawinski.radiolauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/*
 * Pierwsza klatka pliku wideo dekoderem programowym.
 *
 * Dekoder sprzętowy radia kończy się na 1920×1088, a nagrania z karty kamery mają 2560×1440 – systemowy odczyt klatki
 * (MediaMetadataRetriever) zwraca wtedy nic. Tu dekodujemy sami dekoderem programowym Androida (c2.android.*),
 * który nie ma tego ograniczenia; jedna klatka trwa ułamek sekundy do kilku sekund.
 */
object SoftFrame {
    private const val TAG = "KokpitFrame"

    /** do prób przez ADB: pomiń odczyt systemowy i zawsze dekoduj programowo */
    @Volatile
    var force = false

    fun first(f: File, maxSide: Int = 1600): Bitmap? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            ex.selectTrack(track)
            val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { c -> !c.isEncoder && c.supportedTypes.any { it.equals(mime, true) } }
            val soft = all.filter { it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.") }
                .ifEmpty { if (Build.VERSION.SDK_INT >= 29) all.filter { it.isSoftwareOnly } else emptyList() }
            for (info in soft) {
                ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val t0 = System.currentTimeMillis()
                val bmp = runCatching { decode(ex, fmt, info, maxSide) }.onFailure { Log.w(TAG, "${info.name}: $it") }.getOrNull()
                if (bmp != null) {
                    Log.i(TAG, "klatka ${fmt.getInteger(MediaFormat.KEY_WIDTH)}×${fmt.getInteger(MediaFormat.KEY_HEIGHT)} dekoderem ${info.name} w ${System.currentTimeMillis() - t0} ms")
                    return bmp
                }
            }
            if (soft.isEmpty()) Log.w(TAG, "brak dekodera programowego dla $mime")
        } catch (e: Exception) {
            Log.w(TAG, "plik: $e")
        } finally {
            ex.release()
        }
        return null
    }

    private fun decode(ex: MediaExtractor, fmt: MediaFormat, info: MediaCodecInfo, maxSide: Int): Bitmap? {
        val codec = MediaCodec.createByCodecName(info.name)
        try {
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val bi = MediaCodec.BufferInfo()
            var fed = false
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until) {
                if (!fed) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); fed = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(bi, 10_000)
                if (o >= 0) {
                    val bmp = if (bi.size > 0) codec.getOutputImage(o)?.let { img -> try { bitmap(img, maxSide) } finally { img.close() } } else null
                    codec.releaseOutputBuffer(o, false)
                    if (bmp != null) return bmp
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                }
            }
            return null
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /** Obraz YUV z dekodera → bitmapa (przez NV21 i JPEG, od razu pomniejszona do [maxSide]). */
    private fun bitmap(img: Image, maxSide: Int): Bitmap? {
        val crop = img.cropRect
        val w = crop.width() and 1.inv()
        val h = crop.height() and 1.inv()
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]
        val yBuf = y.buffer
        for (row in 0 until h) {
            yBuf.position((crop.top + row) * y.rowStride + crop.left * y.pixelStride)
            if (y.pixelStride == 1) yBuf.get(nv21, row * w, w)
            else for (col in 0 until w) nv21[row * w + col] = yBuf.get(yBuf.position() + col * y.pixelStride)
        }
        val u = img.planes[1]
        val v = img.planes[2]
        val uBuf = u.buffer
        val vBuf = v.buffer
        var out = w * h
        for (row in 0 until h / 2) {
            val uRow = (crop.top / 2 + row) * u.rowStride + (crop.left / 2) * u.pixelStride
            val vRow = (crop.top / 2 + row) * v.rowStride + (crop.left / 2) * v.pixelStride
            for (col in 0 until w / 2) {
                nv21[out++] = vBuf.get(vRow + col * v.pixelStride)
                nv21[out++] = uBuf.get(uRow + col * u.pixelStride)
            }
        }
        val jpeg = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 90, jpeg)
        val opts = BitmapFactory.Options().apply { inSampleSize = if (maxOf(w, h) > maxSide * 2) 4 else if (maxOf(w, h) > maxSide) 2 else 1 }
        val bytes = jpeg.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
