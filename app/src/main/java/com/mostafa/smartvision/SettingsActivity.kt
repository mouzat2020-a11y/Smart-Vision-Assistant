package com.mostafa.smartvision

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import java.io.File
import kotlin.concurrent.thread

class SettingsActivity : Activity() {

    private var pageName = "main"
    private var keyField: EditText? = null
    private var modelField: EditText? = null
    private var promptField: EditText? = null
    private var testToken = 0
    private val rates = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Speaker.init(this)
        pageName = intent.getStringExtra("page") ?: "main"
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    override fun onPause() {
        saveFields()
        super.onPause()
    }

    private fun open(name: String) {
        val i = Intent(this, SettingsActivity::class.java)
        i.putExtra("page", name)
        startActivity(i)
    }

    private fun build() {
        keyField = null
        modelField = null
        promptField = null
        when (pageName) {
            "gemini" -> providerPage("gemini")
            "groq" -> providerPage("groq")
            "describe" -> describePage()
            "reading" -> readingPage()
            "tts" -> ttsHubPage()
            "ttsvoice" -> ttsVoicePage()
            "tttrans" -> tttransPage()
            "ttsadv" -> ttsAdvPage()
            "fx" -> fxPage()
            "help" -> helpPage()
            "privacy" -> privacyPage()
            "history" -> historyPage()
            "data" -> dataPage()
            else -> mainPage()
        }
    }

    // ---------------- الصفحة الرئيسية للإعدادات ----------------
    private fun mainPage() {
        val p = Page(this, "الإعدادات")
        p.section("مزودو الذكاء الاصطناعي")
        p.text("المزود الأساسي الحالي: " + Store.pname(Store.provider()), 16f)
        for (pr in Store.PROVIDERS) {
            val st = keyStatusText(pr, false)
            p.button("إعدادات " + Store.pname(pr) + " (" + st + ")") { open(pr) }
        }
        p.check("التبديل التلقائي لمزود آخر عند فشل الأساسي", Store.cross()) { v -> Store.setCross(v) }
        p.section("خيارات التطبيق")
        p.button("تعليمات وصف الصور") { open("describe") }
        p.button("القراءة الصوتية") { open("reading") }
        p.button("تحويل النص إلى صوت: الصوت والانفعال واللهجة") { open("tts") }
        p.button("الخصوصية وإدارة البيانات") { open("privacy") }
        p.button("المساعدة وحول التطبيق") { open("help") }
        p.bottomButton("رجوع") { finish() }
    }

    // ---------------- صفحة مزود واحد ----------------
    private fun providerPage(pr: String) {
        val name = Store.pname(pr)
        val p = Page(this, "إعدادات " + name)
        val st = keyStatusText(pr, true)
        p.section("المفتاح")
        p.label("الحالة: " + st + ". احصل على المفتاح من الموقع " + Store.psite(pr))
        keyField = p.edit("الصق مفتاح " + name + " هنا", "", 1, true)
        p.button("حفظ المفتاح") { saveKeyNow() }
        p.button("حذف المفتاح") {
            Store.removeKey(pr)
            Ui.toast(this, "تم حذف المفتاح")
            build()
        }
        p.section("المزود الأساسي")
        p.check("استخدام " + name + " كمزود أساسي", Store.provider() == pr) { v ->
            if (v) {
                Store.setProvider(pr)
                Ui.toast(this, "أصبح " + name + " هو المزود الأساسي")
            } else {
                val other = Store.PROVIDERS.first { it != pr }
                Store.setProvider(other)
                Ui.toast(this, "أصبح " + Store.pname(other) + " هو المزود الأساسي")
            }
        }
        p.section("النموذج")
        val cur = Store.model(pr)
        p.label("النموذج الحالي: " + (if (cur.isEmpty()) "تلقائي، يجرّب النماذج بالترتيب" else cur))
        p.button("اختيار النموذج من القائمة") { chooseModel(pr) }
        modelField = p.edit("أو اكتب اسم نموذج بنفسك، والفارغ يعني تلقائي. يُحفظ عند الخروج", cur, 1, false)
        p.section("الاختبار")
        p.button("اختبار الاتصال بـ" + name) { testProvider(pr) }
        p.bottomButton("رجوع") { finish() }
    }

    // حالة المفتاح بوضوح: لا يوجد، محفوظ، أو محفوظ لكن تعذّر فتحه
    private fun keyStatusText(pr: String, withEnc: Boolean): String {
        val s = Store.keyState(pr)
        if (s == 1) return if (withEnc) "المفتاح محفوظ ومشفّر" else "المفتاح محفوظ"
        if (s == 2) return "المفتاح محفوظ لكن تعذّر فتحه، احذفه وأدخله من جديد"
        return "لا يوجد مفتاح"
    }

    private fun saveKeyNow() {
        val f = keyField
        if (f == null) return
        val raw = f.text.toString()
        if (raw.isBlank()) {
            Ui.toast(this, "الصق المفتاح أولًا")
            return
        }
        val err = Store.saveKey(pageName, raw)
        if (err == null) {
            f.setText("")
            Ui.toast(this, "تم حفظ المفتاح مشفّرًا على الجهاز")
            build()
        } else {
            Ui.toast(this, err)
        }
    }

    // يحفظ ما كُتب في الحقول تلقائيًا عند الخروج أو قبل الاختبار
    private fun saveFields() {
        val k = keyField
        if (k != null && k.text.toString().isNotBlank()) {
            val err = Store.saveKey(pageName, k.text.toString())
            if (err == null) {
                k.setText("")
                Ui.toast(this, "تم حفظ المفتاح")
            } else if (err.startsWith("تعذّر")) {
                Ui.toast(this, err)
            }
        }
        val m = modelField
        if (m != null) Store.setModel(pageName, m.text.toString())
        val d = promptField
        if (d != null) Store.setDescribePrompt(d.text.toString())
    }

    private fun chooseModel(pr: String) {
        val list = ArrayList<String>()
        list.add("تلقائي (يجرّب النماذج بالترتيب)")
        list.addAll(Ai.pickList(pr))
        Ui.choose(this, "نماذج " + Store.pname(pr), list) { i ->
            val v = if (i == 0) "" else list[i]
            Store.setModel(pr, v)
            Ui.toast(this, if (v.isEmpty()) "تم اختيار التلقائي" else "تم اختيار " + v)
            build()
        }
    }

    private fun testProvider(pr: String) {
        saveFields()
        if (!Store.hasKey(pr)) {
            if (Store.keyState(pr) == 2) {
                Ui.message(this, "اختبار الاتصال", Store.missingKeyMsg(pr))
            } else {
                Ui.message(this, "اختبار الاتصال", "لا يوجد مفتاح محفوظ لـ" + Store.pname(pr) + ". الصق المفتاح واضغط حفظ المفتاح أولًا.")
            }
            return
        }
        testToken++
        val my = testToken
        Ui.toast(this, "جاري اختبار الاتصال")
        thread {
            val r = Ai.askProvider(pr, listOf(Msg("user", "Reply with exactly: OK")), { my != testToken }, { })
            runOnUiThread {
                val t = r.text
                if (t != null) {
                    var extra = ""
                    if (pr == "gemini" && Store.good().isNotEmpty()) extra = " النموذج الذي اشتغل: " + Store.good() + "."
                    Ui.message(this, "نتيجة الاختبار", "الاتصال بـ" + Store.pname(pr) + " يعمل." + extra + " الرد: " + t)
                } else {
                    Ui.message(this, "نتيجة الاختبار", "فشل الاتصال: " + (r.error ?: ""))
                }
            }
        }
    }

    // ---------------- الخصوصية وإدارة البيانات ----------------
    private class OpResult(val name: String, val ok: Boolean, val detail: String)

    private fun confirm(title: String, msg: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // تقرير صادق: لا نقول "تم" لو جزء فشل
    private fun report(results: List<OpResult>) {
        val sb = StringBuilder()
        var bad = 0
        for (r in results) {
            if (r.ok) sb.append("تم: ") else { sb.append("تعذّر: "); bad++ }
            sb.append(r.name).append(". ").append(r.detail).append("\n")
        }
        if (bad > 0) sb.append("\nبعض العناصر لم تُمسح، جرّب مرة أخرى.")
        Ui.message(this, if (bad == 0) "تم المسح" else "اكتمل المسح مع أخطاء", sb.toString().trim())
        build()
    }

    private fun opImages(): OpResult {
        val r = Privacy.delete(Privacy.imageFiles(this))
        return OpResult("الصور المؤقتة", r.second == 0, "حُذف " + r.first + " ملف" + (if (r.second > 0) "، وتعذّر حذف " + r.second else ""))
    }

    private fun opAudio(): OpResult {
        AudioPlayer.stop()
        val r = Privacy.delete(Privacy.audioFiles(this))
        if (r.second == 0) {
            Session.lastAudioPath = ""
            Session.lastDryPath = ""
        }
        return OpResult("ملفات الصوت المؤقتة", r.second == 0, "حُذف " + r.first + " ملف" + (if (r.second > 0) "، وتعذّر حذف " + r.second else ""))
    }

    private fun opSession(): OpResult {
        Privacy.clearSession()
        return OpResult("النتائج والترجمات المؤقتة في الذاكرة", true, "تم مسحها")
    }

    private fun opHistory(): OpResult {
        val n = History.count()
        val ok = History.clear()
        return OpResult("سجل النتائج المحفوظ", ok, if (ok) "حُذف " + n + " نتيجة" else "تعذّر حذف ملف السجل")
    }

    private fun opKeys(): OpResult {
        for (p in listOf("gemini", "groq", "openrouter")) Store.removeKey(p)
        var left = 0
        for (p in Store.PROVIDERS) if (Store.keyState(p) != 0) left++
        return OpResult("مفاتيح API", left == 0, if (left == 0) "حُذفت كلها" else "تعذّر حذف " + left + " مفتاح")
    }

    private fun opSettings(): OpResult {
        val ok = Store.clearSettings()
        return OpResult("إعدادات التطبيق", ok, if (ok) "رجعت للوضع الافتراضي" else "تعذّر المسح")
    }

    private fun savedKeysText(): String {
        val names = ArrayList<String>()
        for (p in Store.PROVIDERS) if (Store.keyState(p) != 0) names.add(Store.pname(p))
        return if (names.isEmpty()) "لا توجد مفاتيح محفوظة" else names.joinToString("، ")
    }

    private fun privacyPage() {
        val p = Page(this, "الخصوصية وإدارة البيانات")
        p.section("ماذا يُحفظ على جهازك")
        p.text("مفاتيح API مشفّرة، وإعدادات التطبيق، وملفات مؤقتة مثل صور الكاميرا وملفات الصوت، وتبقى حتى تمسحها من إدارة البيانات. والنتائج لا تُحفظ إلا لو فعّلت حفظ السجل بنفسك.", 16f)
        p.section("ماذا يُرسَل إلى الإنترنت")
        p.text("عند وصف صورة أو الدردشة أو الكتابة أو الترجمة أو تحويل النص إلى صوت، تُرسَل الصورة أو النص إلى المزود الذي تستخدمه، Gemini أو Groq، ويعالجها المزود حسب سياسته. وعند الدردشة حول نتيجة تُرسَل معها النتيجة السابقة والصورة لأن الدردشة تحتاج السياق. التطبيق لا يرسل السجل القديم ولا صورك السابقة من تلقاء نفسه.", 16f)
        p.section("ماذا يعمل بدون إنترنت")
        p.text("القراءة بصوت النظام، وفتح النتائج المحفوظة في السجل، وإدارة الإعدادات والملفات. أما وصف الصور والدردشة والترجمة وأصوات Gemini فتحتاج إنترنت.", 16f)
        p.text("التطبيق لا يستخدم إعلانات ولا أدوات تتبع ولا إحصاءات خارجية. والملفات التي تحفظها أنت في التنزيلات أو المعرض لا يمسحها التطبيق أبدًا.", 16f)
        p.section("سجل النتائج")
        p.check("حفظ سجل النتائج على الجهاز", Store.historyOn()) { v ->
            Store.setHistoryOn(v)
            Ui.toast(this, if (v) "تم تفعيل حفظ السجل. النتائج الجديدة تُحفظ على جهازك فقط" else "تم إيقاف حفظ السجل. ما حُفظ سابقًا يبقى حتى تحذفه")
        }
        p.label("مقفول افتراضيًا. لو فعّلته تُحفظ نصوص النتائج الجديدة على جهازك فقط، ولا تُرفع لأي مكان، ولا تُحفظ الصور. الدردشة الإضافية لا تُحفظ.")
        p.button("عرض السجل المحفوظ (" + History.count() + " نتيجة)") { open("history") }
        p.section("إدارة البيانات")
        p.button("مسح البيانات وإدارة الملفات") { open("data") }
        p.bottomButton("رجوع") { finish() }
    }

    private fun historyPage() {
        val p = Page(this, "السجل المحفوظ")
        val items = History.list()
        if (items.isEmpty()) {
            p.text("لا توجد نتائج محفوظة.", 17f)
        } else {
            p.label("عدد النتائج المحفوظة: " + items.size + ". اضغط على نتيجة لعرضها أو نسخها أو حذفها.")
            for (h in items) {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(h.id))
                val head = h.text.replace("\n", " ").take(40)
                p.button(date + "، " + h.title + ": " + head) {
                    Ui.choose(this, h.title, listOf("عرض النص كاملًا", "نسخ النص", "حذف هذه النتيجة", "إلغاء")) { i ->
                        if (i == 0) {
                            Ui.message(this, h.title, h.text)
                        } else if (i == 1) {
                            Ui.copy(this, h.text)
                        } else if (i == 2) {
                            confirm("حذف نتيجة", "هل تريد حذف هذه النتيجة من السجل؟", "نعم، احذف") {
                                val ok = History.remove(h.id)
                                Ui.toast(this, if (ok) "تم حذف النتيجة من السجل" else "تعذّر حذف النتيجة")
                                build()
                            }
                        }
                    }
                }
            }
            p.button("حذف السجل كله") {
                confirm("حذف السجل كله", "سيتم حذف " + items.size + " نتيجة محفوظة نهائيًا. لن يتأثر أي ملف حفظته في التنزيلات.", "نعم، احذف الكل") {
                    report(listOf(opHistory()))
                }
            }
        }
        p.bottomButton("رجوع") { finish() }
    }

    private fun dataPage() {
        val imgs = Privacy.imageFiles(this)
        val aud = Privacy.audioFiles(this)
        val hc = History.count()
        val keysTxt = savedKeysText()
        val imgInfo = imgs.size.toString() + " ملف، " + Privacy.sizeText(Privacy.bytes(imgs))
        val audInfo = aud.size.toString() + " ملف، " + Privacy.sizeText(Privacy.bytes(aud))
        val p = Page(this, "مسح البيانات وإدارة الملفات")
        p.label("كل زر يطلب تأكيدًا قبل المسح، وبعده يخبرك بما حُذف وما تعذّر حذفه. الملفات التي حفظتها أنت في التنزيلات أو المعرض لا تُمسح أبدًا.")
        p.section("مسح كل نوع على حدة")
        p.button("مسح الصور المؤقتة (" + imgInfo + ")") {
            confirm("مسح الصور المؤقتة", "سيتم مسح " + imgInfo + " من الصور المؤقتة التي أنشأها التطبيق. صور المعرض لا تتأثر.", "نعم، امسح") {
                report(listOf(opImages()))
            }
        }
        p.button("مسح ملفات الصوت المؤقتة (" + audInfo + ")") {
            confirm("مسح ملفات الصوت المؤقتة", "سيتم مسح " + audInfo + " من ملفات الصوت المؤقتة، وسيتوقف أي تشغيل جارٍ. ما حفظته في التنزيلات لا يتأثر.", "نعم، امسح") {
                report(listOf(opAudio()))
            }
        }
        p.button("مسح النتائج والترجمات المؤقتة من الذاكرة") {
            confirm("مسح النتائج المؤقتة", "سيتم مسح آخر نتيجة وآخر ترجمة ومحادثة الجلسة الحالية من الذاكرة.", "نعم، امسح") {
                report(listOf(opSession()))
            }
        }
        p.button("مسح سجل النتائج المحفوظ (" + hc + " نتيجة)") {
            confirm("مسح السجل", "سيتم حذف " + hc + " نتيجة محفوظة نهائيًا.", "نعم، امسح") {
                report(listOf(opHistory()))
            }
        }
        p.button("حذف مفاتيح API المحفوظة (" + keysTxt + ")") {
            confirm("حذف مفاتيح API", "سيتم حذف المفاتيح التالية: " + keysTxt + ". ستحتاج لإدخالها من جديد لتستخدم التطبيق.", "نعم، احذف المفاتيح") {
                report(listOf(opKeys()))
            }
        }
        p.button("مسح إعدادات التطبيق (النماذج والأصوات والخيارات)") {
            confirm("مسح الإعدادات", "ستعود إعدادات التطبيق للوضع الافتراضي: النماذج والأصوات والموسيقى وباقي الخيارات. المفاتيح لا تتأثر بهذا الزر.", "نعم، امسح") {
                report(listOf(opSettings()))
            }
        }
        p.section("مسح الكل")
        p.button("مسح بيانات التطبيق المحلية كلها") {
            val msg = "سيتم مسح الآتي:\n" +
                "الصور المؤقتة: " + imgInfo + "\n" +
                "ملفات الصوت المؤقتة: " + audInfo + "\n" +
                "النتائج والترجمات المؤقتة في الذاكرة\n" +
                "سجل النتائج المحفوظ: " + hc + " نتيجة\n" +
                "مفاتيح API: " + keysTxt + "\n" +
                "إعدادات التطبيق\n\n" +
                "لن يُمسح: أي ملف حفظته أنت في التنزيلات أو المعرض."
            confirm("مسح بيانات التطبيق المحلية", msg, "نعم، امسح كل ذلك") {
                report(listOf(opImages(), opAudio(), opSession(), opHistory(), opKeys(), opSettings()))
            }
        }
        p.bottomButton("رجوع") { finish() }
    }

    // ---------------- تعليمات الوصف ----------------
    private fun describePage() {
        val p = Page(this, "تعليمات وصف الصور")
        p.label("هذه التعليمات تُستخدم عند وصف الصور. يُحفظ التعديل تلقائيًا عند الخروج.")
        promptField = p.edit("تعليمات الوصف", Store.describePrompt(), 8, false)
        p.button("استعادة التعليمات الافتراضية") {
            Store.setDescribePrompt("")
            promptField?.setText(Prompts.DEFAULT_DESCRIBE)
            Ui.toast(this, "تمت استعادة التعليمات الافتراضية")
        }
        p.bottomButton("رجوع") { finish() }
    }

    // ---------------- القراءة الصوتية ----------------
    private fun readingPage() {
        val p = Page(this, "القراءة الصوتية")
        p.button("اختيار محرك القراءة") { chooseEngine() }
        p.button("سرعة القراءة الحالية: " + Store.rate() + ". اضغط للتغيير") { chooseRate() }
        p.check("قراءة النتيجة صوتيًا تلقائيًا", Store.autoSpeak()) { v -> Store.setAutoSpeak(v) }
        p.button("اختبار النطق") { Speaker.speak("أهلًا بك، هذه تجربة لمحرك النطق في المساعد البصري الذكي.") }
        p.button("فتح إعدادات الصوت في النظام") {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                Ui.toast(this, "تعذر فتح إعدادات الصوت")
            }
        }
        p.bottomButton("رجوع") { finish() }
    }

    private fun chooseEngine() {
        val engines = Speaker.engines()
        val labels = ArrayList<String>()
        labels.add("محرك النظام الافتراضي")
        for (e in engines) labels.add(e.label.toString())
        Ui.choose(this, "محرك القراءة", labels) { i ->
            if (i == 0) {
                Speaker.setEngine("")
                Ui.toast(this, "تم اختيار المحرك الافتراضي")
            } else {
                Speaker.setEngine(engines[i - 1].name)
                Ui.toast(this, "تم اختيار " + engines[i - 1].label.toString())
            }
        }
    }

    private fun chooseRate() {
        val labels = ArrayList<String>()
        for (r in rates) labels.add("سرعة " + r + (if (r == Store.rate()) " (الحالية)" else ""))
        Ui.choose(this, "سرعة القراءة", labels) { i ->
            Speaker.setRate(rates[i])
            build()
            Speaker.speak("هذه تجربة للسرعة الجديدة")
        }
    }

    // ---------------- تحويل النص إلى صوت ----------------
    private fun ttsHubPage() {
        val p = Page(this, "إعدادات تحويل النص إلى صوت")
        val n = Store.nVoices()
        p.section("الصوت والقراءة")
        p.button("المحرك: " + engineLabel()) { chooseTtsEngine() }
        var vd = ""
        for (x in Prompts.VOICES) if (x.first == Store.ttsVoice()) vd = x.second
        p.button("الصوت: " + Store.ttsVoice() + " - " + vd) { chooseVoice() }
        p.button("الانفعال: " + Prompts.EMO[Store.ttsEmotion()].first) { chooseEmotion() }
        p.button("اللهجة: " + Prompts.ACCENTS[Store.ttsAccent()].first) { chooseAccent() }
        p.button("سرعة الكلام: " + Prompts.PACE[Store.ttsPace()].first) { choosePace() }
        p.button("نموذج الصوت: " + ttsModelLabel()) { chooseTtsModel() }
        p.button("تجربة الصوت الحالي") { playSample() }
        p.button("سرعة القراءة بمحرك النظام وإعداداته") { open("reading") }
        p.section("الأصوات المتعددة")
        p.button("عدد الأصوات: " + (if (n <= 1) "صوت واحد" else n.toString())) { chooseNVoices() }
        p.button("توزيع الأصوات: " + mixNames[Store.mixMode()].substringBefore(" (")) { chooseMixMode() }
        if (n > 1) {
            p.button("اختيار الأصوات الإضافية: " + TtsEngine.activeVoices().joinToString("، ")) { chooseExtraVoices(n) }
        }
        p.section("الترجمة")
        p.check("الترجمة قبل التحويل مفعّلة (كل تحويل يترجم النص أولًا)", Store.ttsTranslateOn()) { v -> Store.setTtsTranslateOn(v) }
        p.button("لغة الترجمة قبل التحويل: " + Prompts.LANGS[Store.ttsLang()].first) { chooseLang() }
        p.button("نموذج الترجمة: " + trModelLabel()) { chooseTrModel() }
        p.section("موسيقى ومؤثرات صوتية (مطر، بحر، طيور، أدعية، ومزج على ملف جاهز)")
        p.check("مزج الموسيقى والمؤثرات مع الصوت عند كل تحويل", Store.fxOn()) { v -> Store.setFxOn(v) }
        p.button("الموسيقى: " + Fx.musicLabel(Store.fxMusic())) { chooseFx("music") }
        p.button("مؤثر البيئة الأول: " + Fx.ambLabel(Store.fxAmb1())) { chooseFx("amb1") }
        p.button("مؤثر البيئة الثاني: " + Fx.ambLabel(Store.fxAmb2())) { chooseFx("amb2") }
        p.button("كل إعدادات الموسيقى والمؤثرات (المستويات، المقدمة، الخاتمة، العينات، المزج)") { open("fx") }
        p.section("المفاتيح والملفات")
        val g = if (Store.hasKey("gemini")) "موجود" else "مش موجود"
        val q = if (Store.hasKey("groq")) "موجود" else "مش موجود"
        p.button("المفاتيح: Gemini " + g + "، Groq " + q) { open("main") }
        p.button("إعادة تجربة نماذج Gemini اللي حصتها خلصت") {
            Store.saveBad(HashMap<String, Long>())
            Ui.toast(this, "تم مسح قائمة النماذج المتوقفة، هتتجرب كلها من جديد")
        }
        p.button("فتح الملف بتطبيق تاني") { openFileElsewhere() }
        p.button("نسخ مسار الملف") {
            val path = Session.lastAudioPath
            if (path.isEmpty()) Ui.toast(this, "مفيش ملف صوتي لسه") else Ui.copy(this, path)
        }
        p.button("نسخ الترجمة") {
            if (Session.lastTranslation.isEmpty()) Ui.toast(this, "مفيش ترجمة لسه") else Ui.copy(this, Session.lastTranslation)
        }
        p.button("حذف الملف المؤقت") { deleteTemp() }
        p.bottomButton("رجوع") { finish() }
    }

    private fun engineLabel(): String {
        return if (Store.ttsEngine() == "system") "صوت النظام" else "Gemini"
    }

    private fun ttsModelLabel(): String {
        var mn = Store.ttsModel()
        for (m in Prompts.TTS_MODELS) if (m.first == mn) mn = m.second
        return mn
    }

    private fun ttsVoicePage() {
        val p = Page(this, "إعدادات الصوت والقراءة")
        p.button("المحرك: " + engineLabel()) { chooseTtsEngine() }
        var vd = ""
        for (x in Prompts.VOICES) if (x.first == Store.ttsVoice()) vd = x.second
        p.button("الصوت: " + Store.ttsVoice() + " - " + vd) { chooseVoice() }
        p.button("الانفعال: " + Prompts.EMO[Store.ttsEmotion()].first) { chooseEmotion() }
        p.button("اللهجة: " + Prompts.ACCENTS[Store.ttsAccent()].first) { chooseAccent() }
        p.button("سرعة الكلام: " + Prompts.PACE[Store.ttsPace()].first) { choosePace() }
        p.button("نموذج الصوت: " + ttsModelLabel()) { chooseTtsModel() }
        p.button("لغة الترجمة قبل التحويل: " + Prompts.LANGS[Store.ttsLang()].first) { chooseLang() }
        val nv = Store.nVoices()
        p.button("عدد الأصوات: " + (if (nv <= 1) "صوت واحد" else nv.toString())) { chooseNVoices() }
        p.button("توزيع الأصوات: " + mixNames[Store.mixMode()].substringBefore(" (")) { chooseMixMode() }
        if (nv > 1) {
            p.button("اختيار الأصوات الإضافية: " + TtsEngine.activeVoices().joinToString("، ")) { chooseExtraVoices(nv) }
        }
        p.button("موسيقى ومؤثرات صوتية (مطر، بحر، طيور، أدعية، ومزج على ملف جاهز)") { open("fx") }
        p.button("تجربة الصوت الحالي") { playSample() }
        p.button("سرعة القراءة بمحرك النظام وإعداداته") { open("reading") }
        p.bottomButton("رجوع") { finish() }
    }

    private fun trModelLabel(): String {
        val m = Store.ttsTrModel()
        return if (m.isEmpty()) "تلقائي" else m
    }

    private fun tttransPage() {
        val p = Page(this, "إعدادات الترجمة")
        p.check("الترجمة قبل التحويل مفعّلة (كل تحويل يترجم النص أولًا)", Store.ttsTranslateOn()) { v -> Store.setTtsTranslateOn(v) }
        p.button("لغة الترجمة: " + Prompts.LANGS[Store.ttsLang()].first) { chooseLang() }
        p.button("نموذج الترجمة: " + trModelLabel()) { chooseTrModel() }
        p.label("زر ترجمة وقراءة في شاشة التحويل يترجم دائمًا حتى لو الإعداد معطّل.")
        p.bottomButton("رجوع") { finish() }
    }

    private val mixNames = listOf("يدوي بالأرقام (اكتب في أول السطر رقم الصوت ثم نقطتين، مثل 2: كلام الصوت الثاني)", "كل سطر بصوت", "كل فقرة بصوت (الفقرات بينها سطر فاضي)")

    private fun ttsAdvPage() {
        val p = Page(this, "إعدادات متقدمة")
        p.button("المحرك: " + engineLabel()) { chooseTtsEngine() }
        val n = Store.nVoices()
        p.button("عدد الأصوات: " + (if (n <= 1) "صوت واحد" else n.toString())) { chooseNVoices() }
        p.button("توزيع الأصوات: " + mixNames[Store.mixMode()].substringBefore(" (")) { chooseMixMode() }
        if (n > 1) {
            p.button("اختيار الأصوات الإضافية: " + TtsEngine.activeVoices().joinToString("، ")) { chooseExtraVoices(n) }
        }
        p.button("نموذج Gemini للصوت: " + ttsModelLabel()) { chooseTtsModel() }
        p.button("نموذج الترجمة: " + trModelLabel()) { chooseTrModel() }
        p.button("إعادة تجربة نماذج Gemini اللي حصتها خلصت") {
            Store.saveBad(HashMap<String, Long>())
            Ui.toast(this, "تم مسح قائمة النماذج المتوقفة، هتتجرب كلها من جديد")
        }
        p.button("فتح الملف بتطبيق تاني") { openFileElsewhere() }
        p.button("نسخ مسار الملف") {
            val path = Session.lastAudioPath
            if (path.isEmpty()) Ui.toast(this, "مفيش ملف صوتي لسه") else Ui.copy(this, path)
        }
        p.button("نسخ الترجمة") {
            if (Session.lastTranslation.isEmpty()) Ui.toast(this, "مفيش ترجمة لسه") else Ui.copy(this, Session.lastTranslation)
        }
        p.button("حذف الملف المؤقت") { deleteTemp() }
        p.bottomButton("رجوع") { finish() }
    }

    private fun chooseTtsEngine() {
        Ui.choose(this, "محرك التحويل", listOf("Gemini (أصوات ذكاء اصطناعي)", "صوت النظام (محرك نطق الهاتف)")) { i ->
            Store.setTtsEngine(if (i == 0) "gemini" else "system")
            build()
        }
    }

    private fun chooseNVoices() {
        val labels = ArrayList<String>()
        labels.add("صوت واحد")
        for (k in 2..8) labels.add(k.toString() + " أصوات")
        Ui.choose(this, "عدد الأصوات (الصوت رقم 1 هو اللي في إعدادات الصوت)", labels) { i ->
            Store.setNVoices(i + 1)
            if (i + 1 > 1) chooseExtraVoices(i + 1) else build()
        }
    }

    private fun chooseExtraVoices(n: Int) {
        val extras = ArrayList<String>()
        fun ask(idx: Int) {
            if (idx > n) {
                Store.setExtraVoices(extras)
                Ui.toast(this, "تم اختيار الأصوات")
                build()
                return
            }
            val labels = ArrayList<String>()
            for (x in Prompts.VOICES) labels.add(x.first + " - " + x.second)
            Ui.choose(this, "اختيار الصوت رقم " + idx, labels) { i ->
                extras.add(Prompts.VOICES[i].first)
                ask(idx + 1)
            }
        }
        ask(2)
    }

    private fun chooseMixMode() {
        Ui.choose(this, "توزيع الأصوات", mixNames) { i ->
            Store.setMixMode(i)
            if (Store.nVoices() <= 1) Ui.toast(this, "طريقة التوزيع اتحفظت. اختار عدد الأصوات عشان التوزيع يشتغل")
            build()
        }
    }

    private fun chooseTrModel() {
        val list = ArrayList<String>()
        list.add("تلقائي")
        list.addAll(Ai.GEMINI_PICK)
        Ui.choose(this, "نموذج الترجمة", list) { i ->
            Store.setTtsTrModel(if (i == 0) "" else list[i])
            build()
        }
    }

    private fun openFileElsewhere() {
        val path = Session.lastAudioPath
        if (path.isEmpty() || !File(path).exists()) {
            Ui.toast(this, "مفيش ملف صوتي لسه")
            return
        }
        val uri = Files.insertDownload(this, "SmartVision_" + Files.stamp() + ".wav", "audio/wav", File(path).readBytes())
        if (uri == null) {
            Ui.toast(this, "الفتح بتطبيق تاني يحتاج أندرويد 10 أو أحدث")
            return
        }
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "audio/wav")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(i, "فتح الملف بتطبيق"))
        } catch (e: Exception) {
            Ui.toast(this, "مفيش تطبيق يفتح الملف")
        }
    }

    private fun deleteTemp() {
        AudioPlayer.stop()
        var n = 0
        val files = cacheDir.listFiles()
        if (files != null) {
            for (f in files) {
                if (f.name.startsWith("tts_") || f.name.startsWith("sample") || f.name.startsWith("fx_")) {
                    if (f.delete()) n++
                }
            }
        }
        Session.lastAudioPath = ""
        Session.lastDryPath = ""
        Ui.toast(this, "تم حذف " + n + " ملف مؤقت")
    }

    // ---------------- موسيقى ومؤثرات ----------------
    private fun fxPage() {
        val p = Page(this, "موسيقى ومؤثرات صوتية")
        p.check("مزج الموسيقى والمؤثرات مع الصوت عند كل تحويل", Store.fxOn()) { v -> Store.setFxOn(v) }
        p.button("الموسيقى: " + Fx.musicLabel(Store.fxMusic())) { chooseFx("music") }
        p.button("مؤثر البيئة الأول: " + Fx.ambLabel(Store.fxAmb1())) { chooseFx("amb1") }
        p.button("مؤثر البيئة الثاني: " + Fx.ambLabel(Store.fxAmb2())) { chooseFx("amb2") }
        p.button("مستوى الموسيقى: " + Store.fxVol(0) + "%") { chooseVol(0) }
        p.button("مستوى المؤثر الأول: " + Store.fxVol(1) + "%") { chooseVol(1) }
        p.button("مستوى المؤثر الثاني: " + Store.fxVol(2) + "%") { chooseVol(2) }
        p.button("مقدمة قبل الكلام: " + Store.fxIntro() + " ثانية") { chooseSecs(true) }
        p.button("خاتمة بعد الكلام: " + Store.fxOutro() + " ثانية") { chooseSecs(false) }
        p.button("عينة من الموسيقى المختارة") { fxPreview("music", Store.fxMusic()) }
        p.button("عينة من المؤثر الأول") { fxPreview("amb", Store.fxAmb1()) }
        p.button("عينة من المؤثر الثاني") { fxPreview("amb", Store.fxAmb2()) }
        p.button("مزج الآن على آخر ملف صوتي جاهز") { mixNow() }
        p.label("الموسيقى والمؤثرات بتتولد بالكود جوه التطبيق، من غير إنترنت.")
        p.bottomButton("رجوع") { finish() }
    }

    private fun setFx(kind: String, id: String) {
        if (kind == "music") Store.setFxMusic(id)
        else if (kind == "amb1") Store.setFxAmb1(id)
        else Store.setFxAmb2(id)
    }

    private fun chooseFx(kind: String) {
        val items = if (kind == "music") Fx.MUSIC else Fx.AMBS
        val type = if (kind == "music") "music" else "amb"
        val groups = ArrayList<String>()
        for (x in items) if (!groups.contains(x.group)) groups.add(x.group)
        val top = ArrayList<String>()
        top.add("بدون")
        top.addAll(groups)
        Ui.choose(this, if (kind == "music") "اختيار الموسيقى" else "اختيار المؤثر", top) { gi ->
            if (gi == 0) {
                setFx(kind, "")
                build()
            } else {
                val g = groups[gi - 1]
                val sub = items.filter { x -> x.group == g }
                val labels = ArrayList<String>()
                for (x in sub) labels.add(x.label)
                Ui.choose(this, g, labels) { si ->
                    setFx(kind, sub[si].id)
                    build()
                    fxPreview(type, sub[si].id)
                }
            }
        }
    }

    private fun chooseVol(i: Int) {
        val vals = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 100)
        val labels = ArrayList<String>()
        for (v in vals) labels.add(v.toString() + "%")
        Ui.choose(this, "المستوى", labels) { k ->
            Store.setFxVol(i, vals[k])
            build()
        }
    }

    private fun chooseSecs(intro: Boolean) {
        val labels = ArrayList<String>()
        for (k in 0..12) labels.add(k.toString() + " ثانية")
        Ui.choose(this, if (intro) "مقدمة قبل الكلام" else "خاتمة بعد الكلام", labels) { k ->
            if (intro) Store.setFxIntro(k) else Store.setFxOutro(k)
            build()
        }
    }

    private fun fxPreview(type: String, id: String) {
        if (id.isEmpty()) {
            Ui.toast(this, "اختار الأول")
            return
        }
        Ui.toast(this, "جاري تجهيز العينة")
        testToken++
        val my = testToken
        thread {
            try {
                val pcm = Fx.preview(type, id, 24000)
                val f = File(cacheDir, "fx_sample.wav")
                f.writeBytes(TtsEngine.wav(pcm, 24000))
                runOnUiThread {
                    if (my == testToken) {
                        Speaker.stop()
                        AudioPlayer.play(f.absolutePath) { }
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { Ui.toast(this, "تعذر تجهيز العينة") }
            }
        }
    }

    private fun mixNow() {
        val dry = Session.lastDryPath
        val pcm = if (dry.isEmpty()) null else TtsEngine.readWavPcm(dry)
        if (pcm == null) {
            Ui.toast(this, "حوّل نصًا إلى صوت الأول، وبعدها اعمل المزج")
            return
        }
        Ui.toast(this, "جاري المزج")
        val rate = Session.lastRate
        thread {
            try {
                val mixed = Fx.mix(pcm, rate, Store.fxMusic(), Store.fxAmb1(), Store.fxAmb2(),
                    Store.fxVol(0), Store.fxVol(1), Store.fxVol(2), Store.fxIntro(), Store.fxOutro())
                val f = File(cacheDir, "tts_mix_" + Files.stamp() + ".wav")
                f.writeBytes(TtsEngine.wav(mixed, rate))
                runOnUiThread {
                    Session.lastAudioPath = f.absolutePath
                    Speaker.stop()
                    if (!AudioPlayer.play(f.absolutePath) { }) Ui.toast(this, "تم المزج لكن تعذر التشغيل")
                    else Ui.toast(this, "تم المزج، والملف الجديد هو آخر ملف جاهز")
                }
            } catch (e: Throwable) {
                runOnUiThread { Ui.toast(this, "تعذر المزج") }
            }
        }
    }

    private fun chooseVoice() {
        val labels = ArrayList<String>()
        for (x in Prompts.VOICES) labels.add(x.first + " - " + x.second)
        Ui.choose(this, "اختيار الصوت", labels) { i ->
            Store.setTtsVoice(Prompts.VOICES[i].first)
            build()
            playSample()
        }
    }

    private fun chooseEmotion() {
        val labels = ArrayList<String>()
        for (x in Prompts.EMO) labels.add(x.first)
        Ui.choose(this, "اختيار الانفعال", labels) { i ->
            Store.setTtsEmotion(i)
            build()
        }
    }

    private fun chooseAccent() {
        val labels = ArrayList<String>()
        for (x in Prompts.ACCENTS) labels.add(x.first)
        Ui.choose(this, "اختيار اللهجة", labels) { i ->
            Store.setTtsAccent(i)
            build()
        }
    }

    private fun choosePace() {
        val labels = ArrayList<String>()
        for (x in Prompts.PACE) labels.add(x.first)
        Ui.choose(this, "سرعة الكلام", labels) { i ->
            Store.setTtsPace(i)
            build()
        }
    }

    private fun chooseTtsModel() {
        val labels = ArrayList<String>()
        for (x in Prompts.TTS_MODELS) labels.add(x.second)
        Ui.choose(this, "نموذج الصوت (إذا انتهت حصته يجرّب التالي تلقائيًا)", labels) { i ->
            Store.setTtsModel(Prompts.TTS_MODELS[i].first)
            build()
        }
    }

    private fun chooseLang() {
        val labels = ArrayList<String>()
        for (x in Prompts.LANGS) labels.add(x.first)
        Ui.choose(this, "لغة الترجمة قبل التحويل", labels) { i ->
            Store.setTtsLang(i)
            build()
        }
    }

    private fun playSample() {
        if (!Store.hasKey("gemini")) {
            Ui.toast(this, "تجربة الصوت تحتاج مفتاح Gemini")
            return
        }
        Ui.toast(this, "جاري تجهيز العينة")
        testToken++
        val my = testToken
        thread {
            val out = TtsEngine.synthChunk("أهلًا بك، هذه تجربة بسيطة للصوت الذي سيقرأ لك النص.", Store.ttsVoice(), TtsEngine.styleText(), { my != testToken }, { })
            val pcm = out.pcm
            runOnUiThread {
                if (pcm == null) {
                    Ui.toast(this, "تعذرت العينة: " + (out.error ?: ""))
                } else {
                    try {
                        val f = File(cacheDir, "sample.wav")
                        f.writeBytes(TtsEngine.wav(TtsEngine.pad(pcm, out.rate), out.rate))
                        Speaker.stop()
                        AudioPlayer.play(f.absolutePath) { }
                    } catch (e: Exception) {
                        Ui.toast(this, "تعذر تشغيل العينة")
                    }
                }
            }
        }
    }

    // ---------------- المساعدة ----------------
    private fun helpPage() {
        val p = Page(this, "المساعدة وحول التطبيق")
        val t = p.text(helpText(), 16f)
        t.setTextIsSelectable(true)
        p.bottomButton("رجوع") { finish() }
    }

    private fun helpText(): String {
        return "أولًا: المفاتيح. من الإعدادات افتح إعدادات Gemini أو Groq، والصق المفتاح، واضغط حفظ المفتاح. Gemini من aistudio.google.com/apikey، وGroq من console.groq.com/keys. المفاتيح تُحفظ مشفّرة على جهازك.\n\n" +
            "ثانيًا: لا يوجد زر حفظ للإعدادات. كل اختيار يُطبَّق فورًا، وما تكتبه في الحقول يُحفظ تلقائيًا عند الخروج من الصفحة.\n\n" +
            "ثالثًا: التبويبات في أسفل الشاشة الرئيسية: الصور، المساعدات والكتابة، الصوت.\n\n" +
            "رابعًا: الصور. اختر المهمة ثم الكاميرا أو المعرض. ويمكنك مشاركة صورة أو لقطة شاشة من أي تطبيق إلى هذا التطبيق، فيسألك ماذا تريد منها.\n\n" +
            "خامسًا: بعد النتيجة يمكنك الاستماع والنسخ والمشاركة والحفظ والدردشة وتكرار المهمة.\n\n" +
            "سادسًا: تحويل النص إلى صوت يحتاج مفتاح Gemini. ويمكنك القراءة الفورية بمحرك النظام بدون مفتاح.\n\n" +
            "ملاحظة: نتيجة الكابتشا قد تخطئ في الحروف المتشابهة. ولا تعتمد على وصف الأدوية أو الأطعمة وحده، وراجع الصيدلي أو المختص.\n\n" +
            "المساعد البصري الذكي تطبيق مستقل يعمل بمفاتيحك أنت، ولا يحتاج Jieshuo Plus. كل الطلبات تذهب للمزود الذي تختاره مباشرة."
    }
}
