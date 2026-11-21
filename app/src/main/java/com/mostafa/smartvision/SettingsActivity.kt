package com.mostafa.smartvision

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import java.io.File
import kotlin.concurrent.thread

class SettingsActivity : Activity() {
    private val keyFields = HashMap<String, EditText>()
    private val modelFields = HashMap<String, EditText>()
    private lateinit var promptField: EditText
    private lateinit var provBtn: Button
    private lateinit var voiceBtn: Button
    private lateinit var emoBtn: Button
    private lateinit var accBtn: Button
    private lateinit var paceBtn: Button
    private lateinit var modelBtn: Button
    private lateinit var langBtn: Button
    private lateinit var rateBtn: Button
    private var testToken = 0

    private val rates = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Speaker.init(this)
        val p = Page(this, "الإعدادات")

        // ---------- المزود ----------
        p.section("مزود الذكاء الاصطناعي")
        provBtn = p.button("المزود الأساسي: " + Store.pname(Store.provider())) { chooseProvider() }
        p.check("التبديل التلقائي لمزود آخر إذا فشل الأساسي", Store.cross()) { v -> Store.setCross(v) }
        p.button("اختبار الاتصال بالمزود الأساسي") { testConnection() }

        // ---------- المفاتيح ----------
        p.section("مفاتيح المزودين")
        for (pr in Store.PROVIDERS) {
            val saved = if (Store.hasKey(pr)) "محفوظ" else "غير محفوظ"
            p.label("مفتاح " + Store.pname(pr) + " من الموقع " + Store.psite(pr) + ". الحالة: " + saved)
            val f = p.edit("الصق مفتاح " + Store.pname(pr), "", 1, true)
            keyFields[pr] = f
            p.button("حفظ مفتاح " + Store.pname(pr)) { saveKey(pr, f) }
            p.button("حذف مفتاح " + Store.pname(pr)) {
                Store.removeKey(pr)
                f.setText("")
                Ui.toast(this, "تم حذف المفتاح")
            }
        }

        // ---------- النماذج ----------
        p.section("النموذج المفضل لكل مزود (الفارغ يعني تلقائي)")
        for (pr in Store.PROVIDERS) {
            p.label("النموذج في " + Store.pname(pr))
            val f = p.edit("اسم النموذج، أو اتركه فارغًا للتلقائي", Store.model(pr), 1, false)
            modelFields[pr] = f
            p.button("اختيار نموذج " + Store.pname(pr) + " من القائمة") {
                val list = ArrayList<String>()
                list.add("تلقائي (يجرّب النماذج بالترتيب)")
                list.addAll(Ai.pickList(pr))
                Ui.choose(this, "نماذج " + Store.pname(pr), list) { i ->
                    val v = if (i == 0) "" else list[i]
                    f.setText(v)
                    Store.setModel(pr, v)
                    Ui.toast(this, if (v.isEmpty()) "تم اختيار التلقائي" else "تم اختيار " + v)
                }
            }
        }

        // ---------- تعليمات الوصف ----------
        p.section("تعليمات وصف الصور")
        promptField = p.edit("تعليمات الوصف", Store.describePrompt(), 6, false)
        p.button("حفظ تعليمات الوصف") {
            Store.setDescribePrompt(promptField.text.toString())
            Ui.toast(this, "تم حفظ التعليمات")
        }
        p.button("استعادة التعليمات الافتراضية") {
            Store.setDescribePrompt("")
            promptField.setText(Prompts.DEFAULT_DESCRIBE)
            Ui.toast(this, "تمت استعادة التعليمات الافتراضية")
        }

        // ---------- القراءة ----------
        p.section("القراءة الصوتية بمحرك النظام")
        p.button("اختيار محرك القراءة") { chooseEngine() }
        rateBtn = p.button("سرعة القراءة: " + Store.rate()) { chooseRate() }
        p.check("قراءة النتيجة صوتيًا تلقائيًا", Store.autoSpeak()) { v -> Store.setAutoSpeak(v) }
        p.button("اختبار النطق") { Speaker.speak("أهلًا بك، هذه تجربة لمحرك النطق في المساعد البصري الذكي.") }
        p.button("فتح إعدادات الصوت في النظام") {
            try {
                startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                Ui.toast(this, "تعذر فتح إعدادات الصوت")
            }
        }

        // ---------- تحويل النص لصوت ----------
        p.section("إعدادات تحويل النص إلى صوت (Gemini)")
        voiceBtn = p.button("") { chooseVoice() }
        emoBtn = p.button("") { chooseEmotion() }
        accBtn = p.button("") { chooseAccent() }
        paceBtn = p.button("") { choosePace() }
        modelBtn = p.button("") { chooseTtsModel() }
        langBtn = p.button("") { chooseLang() }
        p.button("تجربة الصوت الحالي") { playSample() }
        refreshTts()

        // ---------- مساعدة ----------
        p.section("المساعدة")
        p.button("المساعدة وطريقة الاستخدام") { Ui.message(this, "المساعدة", helpText()) }
        p.button("حول التطبيق") {
            Ui.message(this, "حول", "المساعد البصري الذكي. تطبيق مستقل يعمل بمفاتيحك أنت، ولا يحتاج Jieshuo Plus. كل الطلبات تذهب للمزود الذي تختاره مباشرة.")
        }

        p.bottomButton("حفظ الإعدادات") {
            saveFields()
            Ui.toast(this, "تم حفظ الإعدادات")
        }
        p.bottomButton("رجوع") {
            saveFields()
            finish()
        }
    }

    override fun onPause() {
        saveFieldsQuiet()
        super.onPause()
    }

    private fun saveFieldsQuiet() {
        for (pr in Store.PROVIDERS) {
            val f = modelFields[pr]
            if (f != null) Store.setModel(pr, f.text.toString())
        }
        Store.setDescribePrompt(promptField.text.toString())
    }

    private fun saveFields() {
        saveFieldsQuiet()
        for (pr in Store.PROVIDERS) {
            val f = keyFields[pr]
            if (f != null && f.text.toString().isNotBlank()) saveKey(pr, f)
        }
    }

    private fun saveKey(pr: String, f: EditText) {
        val err = Store.saveKey(pr, f.text.toString())
        if (err == null) {
            f.setText("")
            Ui.toast(this, "تم حفظ مفتاح " + Store.pname(pr) + " مشفّرًا على الجهاز")
        } else {
            Ui.toast(this, err)
        }
    }

    private fun chooseProvider() {
        val names = ArrayList<String>()
        for (pr in Store.PROVIDERS) names.add(Store.pname(pr) + (if (Store.hasKey(pr)) " (لديه مفتاح)" else " (بدون مفتاح)"))
        Ui.choose(this, "المزود الأساسي", names) { i ->
            Store.setProvider(Store.PROVIDERS[i])
            provBtn.text = "المزود الأساسي: " + Store.pname(Store.PROVIDERS[i])
            Ui.toast(this, "تم اختيار " + Store.pname(Store.PROVIDERS[i]))
        }
    }

    private fun testConnection() {
        saveFields()
        testToken++
        val my = testToken
        Ui.toast(this, "جاري اختبار الاتصال")
        thread {
            val r = Ai.ask(listOf(Msg("user", "Reply with exactly: OK")), { my != testToken }, { })
            runOnUiThread {
                val t = r.text
                if (t != null) {
                    Ui.message(this, "نتيجة الاختبار", "الاتصال يعمل. الرد: " + t)
                } else {
                    Ui.message(this, "نتيجة الاختبار", "فشل الاتصال: " + (r.error ?: ""))
                }
            }
        }
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
            rateBtn.text = "سرعة القراءة: " + rates[i]
            Speaker.speak("هذه تجربة للسرعة الجديدة")
        }
    }

    private fun refreshTts() {
        val v = Store.ttsVoice()
        var vd = ""
        for (x in Prompts.VOICES) if (x.first == v) vd = x.second
        voiceBtn.text = "الصوت: " + v + " - " + vd
        emoBtn.text = "الانفعال: " + Prompts.EMO[Store.ttsEmotion()].first
        accBtn.text = "اللهجة: " + Prompts.ACCENTS[Store.ttsAccent()].first
        paceBtn.text = "سرعة الكلام: " + Prompts.PACE[Store.ttsPace()].first
        var mn = Store.ttsModel()
        for (m in Prompts.TTS_MODELS) if (m.first == mn) mn = m.second
        modelBtn.text = "نموذج الصوت: " + mn
        langBtn.text = "لغة الترجمة قبل التحويل: " + Prompts.LANGS[Store.ttsLang()].first
    }

    private fun chooseVoice() {
        val labels = ArrayList<String>()
        for (x in Prompts.VOICES) labels.add(x.first + " - " + x.second)
        Ui.choose(this, "اختيار الصوت", labels) { i ->
            Store.setTtsVoice(Prompts.VOICES[i].first)
            refreshTts()
            playSample()
        }
    }

    private fun chooseEmotion() {
        val labels = ArrayList<String>()
        for (x in Prompts.EMO) labels.add(x.first)
        Ui.choose(this, "اختيار الانفعال", labels) { i ->
            Store.setTtsEmotion(i)
            refreshTts()
        }
    }

    private fun chooseAccent() {
        val labels = ArrayList<String>()
        for (x in Prompts.ACCENTS) labels.add(x.first)
        Ui.choose(this, "اختيار اللهجة", labels) { i ->
            Store.setTtsAccent(i)
            refreshTts()
        }
    }

    private fun choosePace() {
        val labels = ArrayList<String>()
        for (x in Prompts.PACE) labels.add(x.first)
        Ui.choose(this, "سرعة الكلام", labels) { i ->
            Store.setTtsPace(i)
            refreshTts()
        }
    }

    private fun chooseTtsModel() {
        val labels = ArrayList<String>()
        for (x in Prompts.TTS_MODELS) labels.add(x.second)
        Ui.choose(this, "نموذج الصوت (إذا انتهت حصته يجرّب التالي تلقائيًا)", labels) { i ->
            Store.setTtsModel(Prompts.TTS_MODELS[i].first)
            refreshTts()
        }
    }

    private fun chooseLang() {
        val labels = ArrayList<String>()
        for (x in Prompts.LANGS) labels.add(x.first)
        Ui.choose(this, "لغة الترجمة قبل التحويل", labels) { i ->
            Store.setTtsLang(i)
            refreshTts()
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

    private fun helpText(): String {
        return "أولًا: المفاتيح. أضف مفتاحًا لمزود واحد على الأقل: Gemini من aistudio.google.com/apikey، أو Groq من console.groq.com/keys، أو OpenRouter من openrouter.ai/keys. المفاتيح تُحفظ مشفّرة على جهازك.\n\n" +
            "ثانيًا: التبويبات في أسفل الشاشة الرئيسية: الصور، المساعدات والكتابة، الصوت.\n\n" +
            "ثالثًا: الصور. اختر المهمة ثم الكاميرا أو المعرض. يمكنك أيضًا مشاركة صورة أو لقطة شاشة من أي تطبيق إلى هذا التطبيق، فيسألك ماذا تريد منها.\n\n" +
            "رابعًا: بعد النتيجة يمكنك الاستماع والنسخ والمشاركة والحفظ والدردشة وتكرار المهمة.\n\n" +
            "خامسًا: تحويل النص إلى صوت يحتاج مفتاح Gemini. ويمكنك القراءة الفورية بمحرك النظام بدون مفتاح.\n\n" +
            "ملاحظة: نتيجة الكابتشا قد تخطئ في الحروف المتشابهة. ولا تعتمد على وصف الأدوية أو الأطعمة وحده، وراجع الصيدلي أو المختص."
    }
}
