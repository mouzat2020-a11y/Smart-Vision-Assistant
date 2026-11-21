package com.mostafa.smartvision

import android.app.Activity
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
            "tts" -> ttsPage()
            "help" -> helpPage()
            else -> mainPage()
        }
    }

    // ---------------- الصفحة الرئيسية للإعدادات ----------------
    private fun mainPage() {
        val p = Page(this, "الإعدادات")
        p.section("مزودو الذكاء الاصطناعي")
        p.text("المزود الأساسي الحالي: " + Store.pname(Store.provider()), 16f)
        for (pr in Store.PROVIDERS) {
            val st = if (Store.hasKey(pr)) "المفتاح محفوظ" else "لا يوجد مفتاح"
            p.button("إعدادات " + Store.pname(pr) + " (" + st + ")") { open(pr) }
        }
        p.check("التبديل التلقائي لمزود آخر عند فشل الأساسي", Store.cross()) { v -> Store.setCross(v) }
        p.section("خيارات التطبيق")
        p.button("تعليمات وصف الصور") { open("describe") }
        p.button("القراءة الصوتية") { open("reading") }
        p.button("تحويل النص إلى صوت: الصوت والانفعال واللهجة") { open("tts") }
        p.button("المساعدة وحول التطبيق") { open("help") }
        p.bottomButton("رجوع") { finish() }
    }

    // ---------------- صفحة مزود واحد ----------------
    private fun providerPage(pr: String) {
        val name = Store.pname(pr)
        val p = Page(this, "إعدادات " + name)
        val st = if (Store.hasKey(pr)) "المفتاح محفوظ ومشفّر" else "لا يوجد مفتاح"
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
            Ui.message(this, "اختبار الاتصال", "لا يوجد مفتاح محفوظ لـ" + Store.pname(pr) + ". الصق المفتاح واضغط حفظ المفتاح أولًا.")
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
    private fun ttsPage() {
        val p = Page(this, "إعدادات تحويل النص إلى صوت")
        var vd = ""
        for (x in Prompts.VOICES) if (x.first == Store.ttsVoice()) vd = x.second
        p.button("الصوت: " + Store.ttsVoice() + " - " + vd) { chooseVoice() }
        p.button("الانفعال: " + Prompts.EMO[Store.ttsEmotion()].first) { chooseEmotion() }
        p.button("اللهجة: " + Prompts.ACCENTS[Store.ttsAccent()].first) { chooseAccent() }
        p.button("سرعة الكلام: " + Prompts.PACE[Store.ttsPace()].first) { choosePace() }
        var mn = Store.ttsModel()
        for (m in Prompts.TTS_MODELS) if (m.first == mn) mn = m.second
        p.button("نموذج الصوت: " + mn) { chooseTtsModel() }
        p.button("لغة الترجمة قبل التحويل: " + Prompts.LANGS[Store.ttsLang()].first) { chooseLang() }
        p.button("تجربة الصوت الحالي") { playSample() }
        p.bottomButton("رجوع") { finish() }
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
                        f.writeBytes(TtsEngine.wav(pcm, out.rate))
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
