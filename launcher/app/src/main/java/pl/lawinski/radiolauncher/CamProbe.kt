package pl.lawinski.radiolauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File

/** Próba wejścia obrazu radia (test przez ADB): jedna klatka z kamery [id] do `files/cam_test_<id>.jpg`. */
object CamProbe {
    private fun log(s: String) = Log.i("KokpitTest", "cam: $s")

    @SuppressLint("MissingPermission")
    fun grab(ctx: Context, id: String, w: Int = 1280, h: Int = 480, skip: Int = 15) {
        val th = HandlerThread("camprobe").apply { start() }
        val hd = Handler(th.looper)
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        log("kamery: ${runCatching { cm.cameraIdList.joinToString() }.getOrElse { it.toString() }}")
        val reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3)
        var n = 0
        var dev: CameraDevice? = null
        fun close() { runCatching { dev?.close() }; runCatching { reader.close() }; th.quitSafely() }
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            n++
            if (n == skip) {
                runCatching {
                    val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
                    val nv21 = ByteArray(w * h * 3 / 2)
                    for (row in 0 until h) { y.buffer.position(row * y.rowStride); y.buffer.get(nv21, row * w, w) }
                    var o = w * h
                    for (row in 0 until h / 2) for (col in 0 until w / 2) {
                        nv21[o++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
                        nv21[o++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
                    }
                    val f = File(ctx.filesDir, "cam_test_$id.jpg")
                    f.outputStream().use { YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 85, it) }
                    log("klatka $n zapisana: ${f.absolutePath} (${f.length()} B)")
                }.onFailure { log("błąd zapisu: $it") }
                img.close(); close(); return@setOnImageAvailableListener
            }
            img.close()
        }, hd)
        runCatching {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    dev = d; log("otwarta $id")
                    runCatching {
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(reader.surface) }
                        @Suppress("DEPRECATION")
                        d.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { runCatching { s.setRepeatingRequest(req.build(), null, hd) }.onFailure { log("błąd podglądu: $it"); close() } }
                            override fun onConfigureFailed(s: CameraCaptureSession) { log("sesja odrzucona"); close() }
                        }, hd)
                    }.onFailure { log("błąd sesji: $it"); close() }
                }
                override fun onDisconnected(d: CameraDevice) { log("rozłączona $id"); close() }
                override fun onError(d: CameraDevice, e: Int) { log("błąd kamery $id: $e"); close() }
            }, hd)
        }.onFailure { log("nie otworzono $id: $it"); close() }
        hd.postDelayed({ if (th.isAlive && n < skip) { log("brak klatek po 8 s (przyszło $n)"); close() } }, 8000)
    }
}
