package com.mostafa.smartvision

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.TextView
import kotlin.concurrent.thread

class WorkActivity : Activity() {
    private var mode = ""
    private var source = ""
    private var extra = ""
    private var token = 0
    private lateinit var status: TextView
    private var camUri: Uri? = null
    private val reqCam = 31
    private val reqGal = 32

    private fun titleOf(m: String): String {
        val t = Prompts.TITLES[m]
        if (t != null) return t
        return when (m) {
            "write" -> "الكتابة بالذكاء الاصطناعي"
            "translate_text" -> "ترجمة نص"
            "ask" -> "اسأل الذكاء الاصطناعي"
            else -> "المساعد البصري الذكي"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Speaker.init(this)
        mode = intent.getStringExtra("mode") ?: "describe"
        source = intent.getStringExtra("source") ?: ""
        extra = intent.getStringExtra("extra") ?: ""
        val p = Page(this, titleOf(mode))
        status = p.text("", 17f)
        Ui.live(status)
        p.bottomButton("إيقاف والرجوع") {
            token++
            finish()
        }
        if (mode == "write" || mode == "translate_text" || mode == "ask") {
            textMode(p)
            return
        }
        if (mode == "chat" && extra.isEmpty()) {
            Ui.input(this, "الدردشة مع صورة", "اكتب سؤالك عن الصورة", "", 3, "التالي") { q ->
                extra = q.trim()
                pickSource()
            }
            return
        }
        if (mode == "find" && extra.isEmpty()) {
            Ui.input(this, "البحث عن شيء", "اكتب اسم الشيء المطلوب، مثل المفتاح أو المحفظة أو الهاتف", "", 2, "التالي") { q ->
                val t = q.trim()
                if (t.isEmpty()) {
                    finish()
                } else {
                    extra = t
                    pickSource()
                }
            }
            return
        }
        pickSource()
    }

    // ---------------- الصور ----------------
    private fun pickSource() {
        val uris = intent.getStringArrayListExtra("uris")
        if (source == "uris" && uris != null) {
            val list = ArrayList<Uri>()
            for (s in uris) list.add(Uri.parse(s))
            analyze(list, null)
            return
        }
        if (source == "camera") { launchCamera(); return }
        if (source == "gallery") { launchGallery(); return }
        Ui.choose(this, "مصدر الصورة", listOf("التقاط صورة بالكاميرا", "اختيار صورة من المعرض")) { i ->
            if (i == 0) launchCamera() else launchGallery()
        }
    }

    private fun launchGallery() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.type = "image/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, reqGal)
        } catch (e: Exception) {
            status.text = "تعذر فتح المعرض"
        }
    }

    private fun launchCamera() {
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        camUri = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val v = ContentValues()
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "SV_" + Files.stamp() + ".jpg")
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartVision")
                val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                if (u != null) {
                    camUri = u
                    i.putExtra(MediaStore.EXTRA_OUTPUT, u)
                    i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: Exception) {
                camUri = null
            }
        }
        try {
            startActivityForResult(i, reqCam)
        } catch (e: Exception) {
            status.text = "الكاميرا غير متاحة على هذا الجهاز"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            finish()
            return
        }
        if (requestCode == reqCam) {
            val u = camUri
            if (u != null) {
                analyze(listOf(u), null)
            } else {
                val bmp = data?.extras?.get("data") as? Bitmap
                if (bmp != null) analyze(ArrayList<Uri>(), bmp) else status.text = "لم تصل صورة من الكاميرا"
            }
        } else if (requestCode == reqGal && data != null) {
            val list = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (k in 0 until clip.itemCount) list.add(clip.getItemAt(k).uri)
            } else {
                val d = data.data
                if (d != null) list.add(d)
            }
            for (u in list) {
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
            }
            if (list.isEmpty()) finish() else analyze(list, null)
        }
    }

    private fun analyze(uris: List<Uri>, bmp: Bitmap?) {
        token++
        val my = token
        status.text = "جاري تجهيز الصورة"
        thread {
            val images = ArrayList<String>()
            if (bmp != null) images.add(Img.bitmapB64(bmp))
            for (u in uris) {
                if (images.size >= 4) break
                val s = Img.loadB64(this, u, 1600)
                if (s != null) images.add(s)
            }
            if (images.isEmpty()) {
                runOnUiThread { if (my == token) status.text = "تعذر تحميل الصورة" }
                return@thread
            }
            val base = Prompts.TASKS[mode] ?: Store.describePrompt()
            val prompt = if (extra.isNotEmpty()) base + "\n\nسؤال المستخدم: " + extra else base
            val h = listOf(Msg("user", prompt, images))
            val r = Ai.ask(h, { my != token }, { m -> runOnUiThread { if (my == token) status.text = m } })
            runOnUiThread { if (my == token) finishWith(r, prompt, images[0]) }
        }
    }

    private fun finishWith(r: AiResult, prompt: String, firstImage: String) {
        val t = r.text
        if (t == null) {
            status.text = "تعذر التحليل: " + (r.error ?: "")
            return
        }
        Session.resultTitle = titleOf(mode)
        Session.resultText = t
        val hist = ArrayList<Msg>()
        hist.add(Msg("user", "الصورة التي تم تحليلها: " + prompt, listOf(firstImage)))
        hist.add(Msg("model", t))
        Session.history = hist
        Session.repeatMode = mode
        Session.repeatSource = if (source == "uris") "" else source
        Session.repeatExtra = if (mode == "chat" || mode == "find") "" else extra
        Session.repeatLabel = "تحليل صورة أخرى"
        startActivity(Intent(this, ResultActivity::class.java))
        finish()
    }

    // ---------------- النصوص ----------------
    private fun textMode(p: Page) {
        val hint = when (mode) {
            "write" -> "اكتب المطلوب: رسالة، مقال، إعلان، صياغة..."
            "translate_text" -> "اكتب النص، ويمكنك تحديد اللغة المطلوبة"
            else -> "اكتب سؤالك"
        }
        val wrap = when (mode) {
            "write" -> "اكتب النص المطلوب وفق التعليمات التالية، وراعِ اللغة والنبرة والطول المذكورين. اطلع بالنص النهائي مباشرة من غير مقدمات.\nالتعليمات:"
            "translate_text" -> "ترجم النص التالي ترجمة دقيقة وطبيعية. لو المستخدم محددش اللغة المطلوبة ترجمه للعربية المصرية. اطلع بالترجمة بس.\nالنص:"
            else -> "جاوب عن السؤال التالي بوضوح وباللغة اللي اتسأل بيها:\n"
        }
        val e = p.edit(hint, "", 6, false)
        p.button("إرسال") {
            val v = e.text.toString().trim()
            if (v.isEmpty()) {
                Ui.toast(this, "اكتب النص أولًا")
            } else {
                sendText(wrap + "\n" + v)
            }
        }
    }

    private fun sendText(full: String) {
        token++
        val my = token
        status.text = "جاري التنفيذ"
        thread {
            val r = Ai.ask(listOf(Msg("user", full)), { my != token }, { m -> runOnUiThread { if (my == token) status.text = m } })
            runOnUiThread {
                if (my == token) {
                    val t = r.text
                    if (t == null) {
                        status.text = "تعذر التنفيذ: " + (r.error ?: "")
                    } else {
                        Session.resultTitle = when (mode) {
                            "write" -> "النص المكتوب"
                            "translate_text" -> "نتيجة الترجمة"
                            else -> "الإجابة"
                        }
                        Session.resultText = t
                        val hist = ArrayList<Msg>()
                        hist.add(Msg("user", full))
                        hist.add(Msg("model", t))
                        Session.history = hist
                        Session.repeatMode = mode
                        Session.repeatSource = ""
                        Session.repeatExtra = ""
                        Session.repeatLabel = "طلب جديد"
                        startActivity(Intent(this, ResultActivity::class.java))
                        finish()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        token++
        super.onDestroy()
    }
}
