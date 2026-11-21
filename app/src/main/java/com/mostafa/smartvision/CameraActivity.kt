package com.mostafa.smartvision

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

// كاميرا داخلية للتطبيق: معاينة + التقاط + تحكم بالفلاش، من غير الخروج لتطبيق الكاميرا
class CameraActivity : Activity(), SurfaceHolder.Callback {
    private var camera: Camera? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var surfaceReady = false
    private var permOk = false
    private var previewing = false
    private var busy = false
    private var hasFlash = false
    private var flashMode = 0 // 0 مطفأ، 1 مضيء باستمرار، 2 تلقائي عند التصوير
    private var camId = -1
    private lateinit var surface: SurfaceView
    private lateinit var flashBtn: Button
    private lateinit var status: TextView
    private val reqPerm = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(16, 16, 16, 16)

        status = TextView(this)
        status.textSize = 17f
        status.setPadding(8, 8, 8, 8)
        status.text = "جاري تشغيل الكاميرا"
        Ui.live(status)
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        surface = SurfaceView(this)
        surface.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))

        flashBtn = Button(this)
        flashBtn.textSize = 16f
        flashBtn.isAllCaps = false
        flashBtn.text = "الفلاش: مطفأ. اضغط للتغيير"
        flashBtn.setOnClickListener { toggleFlash() }
        root.addView(flashBtn, LinearLayout.LayoutParams(-1, -2))

        val shootBtn = Button(this)
        shootBtn.textSize = 18f
        shootBtn.isAllCaps = false
        shootBtn.text = "التقاط الصورة"
        shootBtn.setOnClickListener { shoot() }
        root.addView(shootBtn, LinearLayout.LayoutParams(-1, -2))

        val cancelBtn = Button(this)
        cancelBtn.textSize = 16f
        cancelBtn.isAllCaps = false
        cancelBtn.text = "إلغاء والرجوع"
        cancelBtn.setOnClickListener { finish() }
        root.addView(cancelBtn, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        surface.holder.addCallback(this)

        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            status.text = "التطبيق يطلب إذن استخدام الكاميرا. اضغط سماح"
            requestPermissions(arrayOf(Manifest.permission.CAMERA), reqPerm)
        } else {
            permOk = true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqPerm) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permOk = true
                if (surfaceReady) openCamera()
            } else {
                fail("لم يتم السماح باستخدام الكاميرا")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (permOk && surfaceReady) openCamera()
    }

    override fun onPause() {
        releaseCamera()
        super.onPause()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
        surfaceReady = true
        if (permOk) openCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (camera != null) startPreview(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        releaseCamera()
    }

    private fun fail(reason: String) {
        val r = Intent()
        r.putExtra("reason", reason)
        setResult(RESULT_FIRST_USER, r)
        finish()
    }

    private fun backId(): Int {
        val n = Camera.getNumberOfCameras()
        for (i in 0 until n) {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i
        }
        return if (n > 0) 0 else -1
    }

    private fun openCamera() {
        if (camera != null) return
        try {
            camId = backId()
            if (camId < 0) {
                fail("لا توجد كاميرا في هذا الجهاز")
                return
            }
            camera = Camera.open(camId)
            startPreview(surface.width, surface.height)
        } catch (e: Exception) {
            releaseCamera()
            fail("تعذر فتح الكاميرا")
        }
    }

    private fun releaseCamera() {
        try { camera?.stopPreview() } catch (e: Exception) {}
        try { camera?.release() } catch (e: Exception) {}
        camera = null
        previewing = false
    }

    private fun bestSize(list: List<Camera.Size>, vw: Int, vh: Int, maxW: Int): Camera.Size {
        var target = 4.0 / 3.0
        if (vw > 0 && vh > 0) target = maxOf(vw, vh).toDouble() / minOf(vw, vh).toDouble()
        var best: Camera.Size? = null
        var bestDiff = 1000.0
        for (s in list) {
            if (s.width > maxW) continue
            val d = Math.abs(s.width.toDouble() / s.height.toDouble() - target)
            val b = best
            if (b == null || d < bestDiff - 0.01 || (Math.abs(d - bestDiff) <= 0.01 && s.width * s.height > b.width * b.height)) {
                best = s
                bestDiff = d
            }
        }
        return best ?: list[0]
    }

    private fun bestPicture(list: List<Camera.Size>, ps: Camera.Size): Camera.Size {
        val ratio = ps.width.toDouble() / ps.height.toDouble()
        var best: Camera.Size? = null
        for (s in list) {
            val d = Math.abs(s.width.toDouble() / s.height.toDouble() - ratio)
            if (d > 0.02 || s.width * s.height > 12000000) continue
            val b = best
            if (b == null || s.width * s.height > b.width * b.height) best = s
        }
        return best ?: ps
    }

    private fun displayRotation(): Int {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(camId, info)
        val rot = windowManager.defaultDisplay.rotation
        var degrees = 0
        if (rot == Surface.ROTATION_90) degrees = 90
        else if (rot == Surface.ROTATION_180) degrees = 180
        else if (rot == Surface.ROTATION_270) degrees = 270
        return (info.orientation - degrees + 360) % 360
    }

    private fun startPreview(vw: Int, vh: Int) {
        val cam = camera ?: return
        val holder = surfaceHolder ?: return
        try {
            if (previewing) {
                cam.stopPreview()
                previewing = false
            }
            val params = cam.parameters
            val ps = bestSize(params.supportedPreviewSizes, vw, vh, 1920)
            params.setPreviewSize(ps.width, ps.height)
            val pic = bestPicture(params.supportedPictureSizes, ps)
            params.setPictureSize(pic.width, pic.height)
            params.jpegQuality = 90
            val fm = params.supportedFocusModes
            if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            val rot = displayRotation()
            cam.setDisplayOrientation(rot)
            params.setRotation(rot)
            cam.parameters = params
            val flashes = cam.parameters.supportedFlashModes
            hasFlash = flashes != null && flashes.contains(Camera.Parameters.FLASH_MODE_TORCH)
            applyFlash()
            cam.setPreviewDisplay(holder)
            cam.startPreview()
            previewing = true
            status.text = "الكاميرا جاهزة. وجّه الهاتف ثم اضغط التقاط الصورة"
            if (!hasFlash) flashBtn.text = "الفلاش غير متاح في هذا الجهاز"
        } catch (e: Exception) {
            releaseCamera()
            fail("تعذر تشغيل المعاينة")
        }
    }

    private fun applyFlash() {
        val cam = camera ?: return
        try {
            val p = cam.parameters
            val modes = p.supportedFlashModes
            if (modes == null) return
            val want = if (flashMode == 1) Camera.Parameters.FLASH_MODE_TORCH
            else if (flashMode == 2) Camera.Parameters.FLASH_MODE_AUTO
            else Camera.Parameters.FLASH_MODE_OFF
            if (modes.contains(want)) {
                p.flashMode = want
                cam.parameters = p
            }
        } catch (e: Exception) {
        }
    }

    private fun toggleFlash() {
        if (!hasFlash) {
            Ui.toast(this, "الفلاش غير متاح في هذا الجهاز")
            return
        }
        flashMode = (flashMode + 1) % 3
        applyFlash()
        val label = when (flashMode) {
            1 -> "الفلاش: مضيء باستمرار"
            2 -> "الفلاش: تلقائي عند التصوير"
            else -> "الفلاش: مطفأ"
        }
        flashBtn.text = label + ". اضغط للتغيير"
        flashBtn.announceForAccessibility(label)
    }

    private fun shoot() {
        val cam = camera
        if (cam == null || !previewing) {
            Ui.toast(this, "الكاميرا لم تجهز بعد")
            return
        }
        if (busy) return
        busy = true
        status.text = "جاري التصوير، ثبّت الهاتف"
        try {
            cam.autoFocus(Camera.AutoFocusCallback { _, _ -> takeNow() })
        } catch (e: Exception) {
            takeNow()
        }
    }

    private fun takeNow() {
        val cam = camera
        if (cam == null) {
            busy = false
            return
        }
        try {
            cam.takePicture(null, null, Camera.PictureCallback { data, _ -> onJpeg(data) })
        } catch (e: Exception) {
            busy = false
            status.text = "تعذر التصوير، حاول مرة أخرى"
        }
    }

    private fun onJpeg(data: ByteArray?) {
        if (data == null) {
            busy = false
            status.text = "لم تصل صورة، حاول مرة أخرى"
            return
        }
        try {
            val f = File(cacheDir, "cam_" + Files.stamp() + ".jpg")
            f.writeBytes(data)
            val r = Intent()
            r.putExtra("path", f.absolutePath)
            setResult(RESULT_OK, r)
            finish()
        } catch (e: Exception) {
            busy = false
            status.text = "تعذر حفظ الصورة، حاول مرة أخرى"
            try { camera?.startPreview() } catch (x: Exception) {}
        }
    }
}
