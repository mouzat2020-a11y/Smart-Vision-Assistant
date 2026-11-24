package com.mostafa.smartvision

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

// قراءة ملفات PDF: كل صفحة تتحوّل لصورة وتُرسَل للذكاء الاصطناعي ليقرأ نصها
class PdfActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var body: TextView
    private lateinit var pageBox: EditText
    private var token = 0
    private var pdf: File? = null
    private var fileName = ""
    private var count = 0
    private var cur = 0
    private val texts = ConcurrentHashMap<Int, String>()
    private val reqPick = 51

    private val PROMPT = "أنت تقرأ صفحة من ملف PDF لمستخدم مكفوف. اكتب النص الموجود في الصفحة كاملًا وبالترتيب الصحيح للقراءة كما هو، بدون تلخيص وبدون أي مقدمة أو تعليق قبل النص أو بعده. حافظ على ترتيب العناوين والفقرات. لو فيها جدول فاقرأه صفًا صفًا. لو فيها صورة أو رسم أو شعار مهم فاذكره بين قوسين بجملة قصيرة مثل [صورة: وصف قصير]. لو الصفحة فاضية أو بدون نص فاكتب: الصفحة فاضية. اكتب النص العربي كما هو بالعربية الصحيحة، واترك أي لغة أخرى بلغتها."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Speaker.init(this)
        val p = Page(this, "قراءة ملف PDF")
        status = p.text("اختر ملف PDF لأبدأ القراءة.", 17f)
        Ui.live(status)
        body = p.text("", 18f)
        body.setTextIsSelectable(true)
        p.button("اختيار ملف PDF") { pick() }
        p.button("قراءة الصفحة الحالية") { requireOpen { readPage(cur, true) } }
        p.button("الصفحة التالية") { requireOpen { go(cur + 1) } }
        p.button("الصفحة السابقة") { requireOpen { go(cur - 1) } }
        p.button("قراءة كل الصفحات من الصفحة الحالية") { requireOpen { readAll() } }
        p.button("استماع / إيقاف الاستماع") { toggleSpeak() }
        p.button("إلغاء القراءة الجارية") { cancelRead() }
        p.button("نسخ النص") { copyText() }
        p.button("اسأل عن هذه الصفحة") { requireOpen { askAbout() } }
        pageBox = p.pinnedEdit("رقم الصفحة", "")
        pageBox.inputType = InputType.TYPE_CLASS_NUMBER
        p.pinnedButton("اذهب إلى هذه الصفحة") { requireOpen { gotoBox() } }
        p.bottomButton("رجوع") { finish() }
        val u = incomingUri(intent)
        if (u != null) openPdf(u)
    }

    private fun incomingUri(i: Intent?): Uri? {
        if (i == null) return null
        if (i.action == Intent.ACTION_VIEW) return i.data
        if (i.action == Intent.ACTION_SEND) return i.getParcelableExtra(Intent.EXTRA_STREAM)
        return null
    }

    private fun requireOpen(action: () -> Unit) {
        if (count == 0) status.text = "اختر ملف PDF أولًا" else action()
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.type = "application/pdf"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(i, reqPick)
        } catch (e: Exception) {
            status.text = "تعذر فتح منتقي الملفات"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val d = data?.data
        if (requestCode == reqPick && resultCode == RESULT_OK && d != null) openPdf(d)
    }

    private fun <T> withRenderer(f: File, block: (PdfRenderer) -> T): T {
        val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val r = PdfRenderer(fd)
            try {
                return block(r)
            } finally {
                r.close()
            }
        } finally {
            fd.close()
        }
    }

    private fun openPdf(uri: Uri) {
        token++
        Http.cancelAll()
        Speaker.stop()
        status.text = "جاري فتح الملف"
        body.text = ""
        texts.clear()
        count = 0
        cur = 0
        val my = token
        thread {
            var name = "ملف PDF"
            try {
                val c = contentResolver.query(uri, null, null, null, null)
                if (c != null) {
                    c.use {
                        if (it.moveToFirst()) {
                            val ix = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (ix >= 0) name = it.getString(ix) ?: name
                        }
                    }
                }
            } catch (e: Exception) {
            }
            var err: String? = null
            var n = 0
            val f = File(cacheDir, "pdf_src.pdf")
            try {
                val ins = contentResolver.openInputStream(uri)
                if (ins == null) {
                    err = "تعذر قراءة الملف"
                } else {
                    ins.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    n = withRenderer(f) { r -> r.pageCount }
                }
            } catch (e: SecurityException) {
                err = "الملف محمي بكلمة سر ولا يمكن فتحه"
            } catch (e: Throwable) {
                err = "تعذر فتح الملف، قد يكون تالفًا أو غير مدعوم"
            }
            val fe = err
            val fn = n
            val nm = name
            runOnUiThread {
                if (my != token) return@runOnUiThread
                if (fe != null) {
                    status.text = fe
                    return@runOnUiThread
                }
                if (fn <= 0) {
                    status.text = "الملف فاضي"
                    return@runOnUiThread
                }
                pdf = f
                count = fn
                fileName = nm
                cur = 0
                status.text = "تم فتح الملف " + nm + "، عدد صفحاته " + fn + ". جاري قراءة الصفحة الأولى"
                readPage(0, false)
            }
        }
    }

    // يحوّل صفحة لصورة JPEG بترميز base64 بخلفية بيضاء
    private fun renderB64(index: Int): String? {
        val f = pdf ?: return null
        return try {
            synchronized(this) {
                withRenderer(f) { r ->
                    val page = r.openPage(index)
                    try {
                        val big = maxOf(page.width, page.height).toFloat()
                        val s = 1600f / big
                        val w = maxOf(1, (page.width * s).toInt())
                        val h = maxOf(1, (page.height * s).toInt())
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = Img.bitmapB64(bmp)
                        bmp.recycle()
                        out
                    } finally {
                        page.close()
                    }
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun show(index: Int, t: String) {
        status.text = "الصفحة " + (index + 1) + " من " + count
        body.text = t
        if (Store.autoSpeak()) Speaker.speak(t)
    }

    private fun readPage(index: Int, force: Boolean) {
        token++
        Http.cancelAll()
        Speaker.stop()
        val my = token
        cur = index
        val have = texts[index]
        if (have != null && !force) {
            show(index, have)
            return
        }
        status.text = "جاري قراءة الصفحة " + (index + 1) + " من " + count
        body.text = ""
        thread {
            val b64 = renderB64(index)
            if (b64 == null) {
                runOnUiThread { if (my == token) status.text = "تعذر تجهيز الصفحة " + (index + 1) }
                return@thread
            }
            val h = listOf(Msg("user", PROMPT, listOf(b64)))
            val r = Ai.ask(h, { my != token }, { m -> runOnUiThread { if (my == token) status.text = m } })
            runOnUiThread {
                if (my != token) return@runOnUiThread
                val t = r.text
                if (t == null) {
                    status.text = "تعذرت قراءة الصفحة " + (index + 1) + ": " + (r.error ?: "")
                } else {
                    texts[index] = t
                    show(index, t)
                }
            }
        }
    }

    private fun go(i: Int) {
        if (i < 0) {
            status.text = "هذه أول صفحة"
        } else if (i >= count) {
            status.text = "هذه آخر صفحة"
        } else {
            readPage(i, false)
        }
    }

    private fun gotoBox() {
        val n = pageBox.text.toString().trim().toIntOrNull()
        if (n == null || n < 1 || n > count) {
            status.text = "اكتب رقم صفحة من 1 إلى " + count
        } else {
            pageBox.setText("")
            readPage(n - 1, false)
        }
    }

    // يقرأ من الصفحة الحالية حتى 30 صفحة في المرة، ويوقف عند أول خطأ
    private fun readAll() {
        token++
        Http.cancelAll()
        Speaker.stop()
        val my = token
        val start = cur
        val end = minOf(count, start + 30)
        body.text = ""
        thread {
            val sb = StringBuilder()
            for (i in start until end) {
                if (my != token) return@thread
                runOnUiThread {
                    if (my == token) status.text = "جاري قراءة الصفحة " + (i + 1) + " من " + count + "، الدفعة من " + (start + 1) + " إلى " + end
                }
                var t = texts[i]
                if (t == null) {
                    val b64 = renderB64(i)
                    if (b64 == null) {
                        runOnUiThread { if (my == token) status.text = "تعذر تجهيز الصفحة " + (i + 1) }
                        return@thread
                    }
                    val r = Ai.ask(listOf(Msg("user", PROMPT, listOf(b64))), { my != token }, { })
                    val tt = r.text
                    if (tt == null) {
                        if (my != token) return@thread
                        val done = sb.toString().trim()
                        runOnUiThread {
                            if (my == token) {
                                body.text = done
                                status.text = "توقفت عند الصفحة " + (i + 1) + ": " + (r.error ?: "") + ". الصفحات السابقة تمت قراءتها"
                            }
                        }
                        return@thread
                    }
                    texts[i] = tt
                    t = tt
                }
                sb.append("الصفحة ").append(i + 1).append("\n").append(t).append("\n\n")
                val snap = sb.toString().trim()
                runOnUiThread { if (my == token) body.text = snap }
            }
            val all = sb.toString().trim()
            runOnUiThread {
                if (my != token) return@runOnUiThread
                cur = end - 1
                var s = "تمت قراءة الصفحات من " + (start + 1) + " إلى " + end + " من " + count
                if (end < count) s += ". لمتابعة الباقي اذهب إلى الصفحة " + (end + 1) + " واضغط قراءة كل الصفحات"
                status.text = s
                if (Store.autoSpeak()) Speaker.speak(all)
            }
        }
    }

    private fun toggleSpeak() {
        if (Speaker.isSpeaking()) {
            Speaker.stop()
            return
        }
        val t = body.text.toString()
        if (t.isBlank()) Ui.toast(this, "لا يوجد نص للاستماع إليه") else Speaker.speak(t)
    }

    private fun cancelRead() {
        token++
        Http.cancelAll()
        Speaker.stop()
        status.text = "تم إيقاف القراءة"
    }

    private fun copyText() {
        val t = body.text.toString()
        if (t.isBlank()) Ui.toast(this, "لا يوجد نص للنسخ") else Ui.copy(this, t)
    }

    // يفتح الدردشة حول الصفحة الحالية: الصورة والنص المقروء يكونان سياق الأسئلة
    private fun askAbout() {
        val idx = cur
        val t = texts[idx]
        if (t == null) {
            status.text = "اقرأ الصفحة أولًا ثم اسأل عنها"
            return
        }
        status.text = "جاري تجهيز الصفحة للدردشة"
        thread {
            val b64 = renderB64(idx)
            runOnUiThread {
                if (b64 == null) {
                    status.text = "تعذر تجهيز الصفحة"
                    return@runOnUiThread
                }
                val hist = ArrayList<Msg>()
                hist.add(Msg("user", "هذه الصفحة " + (idx + 1) + " من ملف PDF اسمه " + fileName + ". النص المقروء منها فيما يلي، وسأسألك عنها.\n\n" + t, listOf(b64)))
                hist.add(Msg("model", t))
                Session.history = hist
                Session.resultTitle = "الصفحة " + (idx + 1) + " من " + fileName
                Session.resultText = t
                Session.repeatMode = ""
                Speaker.stop()
                status.text = "الصفحة " + (idx + 1) + " من " + count
                startActivity(Intent(this, ChatActivity::class.java))
            }
        }
    }

    override fun onDestroy() {
        token++
        Http.cancelAll()
        Speaker.stop()
        // نمسح نسخة الملف المؤقتة من الجهاز عند الخروج
        try { File(cacheDir, "pdf_src.pdf").delete() } catch (e: Exception) { }
        super.onDestroy()
    }
}
