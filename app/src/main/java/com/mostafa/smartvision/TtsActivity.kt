package com.mostafa.smartvision

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

// شاشة تحويل النص إلى صوت (القائمة على نمط إضافة تحويل النص لصوت)
class TtsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var input: EditText
    private var token = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Speaker.init(this)
        val p = Page(this, "تحويل النص إلى صوت")
        summary = p.text("", 15f)
        input = p.pinnedEdit("اكتب النص هنا", "")
        p.pinnedButton("تحويل النص إلى صوت وتشغيله") { convert(false) }
        status = p.text("", 16f)
        Ui.live(status)

        p.button("إلغاء التحويل الجاري") { cancelConvert() }
        p.button("تشغيل / إيقاف الصوت") { togglePlay() }
        p.button("ترجمة وقراءة") { convert(true) }
        p.button("موسيقى ومؤثرات صوتية (مطر، بحر، طيور، أدعية، ومزج على ملف جاهز)") { openSettings("fx") }
        p.button("أصوات المشاعر (شهقة، صرخة، بكاء...)") { soundsMenu() }
        p.button("تنزيل الملف") { saveAudio() }
        p.button("مشاركة الملف") { shareAudio() }
        p.button("إعدادات الصوت والقراءة (الصوت، الانفعال، اللهجة، السرعة، تجربة الصوت، المحرك)") { openSettings("ttsvoice") }
        p.button("إعدادات الترجمة") { openSettings("tttrans") }
        p.button("المفاتيح") { openSettings("main") }
        p.button("إعدادات متقدمة") { openSettings("ttsadv") }
        p.button("لصق من الحافظة") { input.setText(Ui.pasteText(this)) }
        p.button("مسح النص") { input.setText("") }
        p.bottomButton("رجوع") { finish() }
    }

    override fun onResume() {
        super.onResume()
        var vd = ""
        for (x in Prompts.VOICES) if (x.first == Store.ttsVoice()) vd = x.second
        val eng = if (Store.ttsEngine() == "system") "صوت النظام" else "Gemini"
        val tr = if (Store.ttsTranslateOn()) "مفعّلة إلى " + Prompts.LANGS[Store.ttsLang()].first else "معطّلة"
        val fx = if (Store.fxOn()) "مفعّل" else "معطّل"
        summary.text = "المحرك: " + eng + ". الصوت: " + Store.ttsVoice() + " (" + vd + "). عدد الأصوات: " + Store.nVoices() +
            ". الانفعال: " + Prompts.EMO[Store.ttsEmotion()].first + ". اللهجة: " + Prompts.ACCENTS[Store.ttsAccent()].first +
            ". السرعة: " + Prompts.PACE[Store.ttsPace()].first + ". الترجمة: " + tr + ". المزج مع الموسيقى: " + fx
    }

    // إلغاء حقيقي: يقطع الاتصال الجاري ولا يكمل أي محاولة بعده
    private fun cancelConvert() {
        token++
        Http.cancelAll()
        status.text = "تم إلغاء التحويل"
    }

    private fun openSettings(pg: String) {
        val i = Intent(this, SettingsActivity::class.java)
        i.putExtra("page", pg)
        startActivity(i)
    }

    private fun post(my: Int, m: String) {
        runOnUiThread { if (my == token) status.text = m }
    }

    // ---------- أصوات المشاعر: إدراج علامة عند المؤشر ----------
    private fun soundsMenu() {
        if (Store.ttsEngine() != "gemini") {
            Ui.toast(this, "أصوات المشاعر شغالة مع Gemini بس")
            return
        }
        val cats = ArrayList<String>()
        for (c in SoundTags.CATS) cats.add(c.first)
        Ui.choose(this, "أصوات المشاعر", cats) { ci ->
            val items = SoundTags.CATS[ci].second
            val labels = ArrayList<String>()
            for (t in items) labels.add(t.first)
            Ui.choose(this, SoundTags.CATS[ci].first, labels) { ti ->
                val tag = items[ti].second
                val ed = input.text
                var pos = input.selectionStart
                if (pos < 0 || pos > ed.length) pos = ed.length
                ed.insert(pos, " " + tag + " ")
                Ui.toast(this, "تمت إضافة " + items[ti].first)
            }
        }
    }

    // ---------- تشغيل / إيقاف ----------
    private fun togglePlay() {
        if (AudioPlayer.isPlaying() || Speaker.isSpeaking()) {
            AudioPlayer.stop()
            Speaker.stop()
            status.text = "تم إيقاف الصوت"
            return
        }
        val path = Session.lastAudioPath
        if (path.isNotEmpty() && File(path).exists()) {
            if (!AudioPlayer.play(path) { status.text = "انتهى التشغيل" }) status.text = "تعذر التشغيل"
            else status.text = "جاري التشغيل"
        } else {
            convert(false)
        }
    }

    // ---------- التحويل ----------
    private fun convert(translateNow: Boolean) {
        val txt = input.text.toString().trim()
        if (txt.isEmpty()) {
            Ui.toast(this, "اكتب النص أولًا")
            return
        }
        val doTranslate = translateNow || Store.ttsTranslateOn()
        val isSystem = Store.ttsEngine() == "system"
        if (!isSystem && !Store.hasKey("gemini")) {
            Ui.message(this, "مفتاح Gemini", "التحويل بأصوات Gemini يحتاج مفتاح Gemini، أضفه من الإعدادات. أو اختر محرك صوت النظام من الإعدادات المتقدمة.")
            return
        }
        token++
        val my = token
        status.text = "جاري التحويل"
        Speaker.stop()
        AudioPlayer.stop()
        val appCtx = applicationContext
        thread {
            var text = txt
            if (doTranslate) {
                val lang = Prompts.LANGS[Store.ttsLang()]
                val prompt = "Translate the following text into " + lang.second + ". Return only the translated text, with no explanations or notes. Keep the line breaks and any angle-bracket tags exactly as they are.\n\n" + txt
                val tr = Ai.askGeminiModel(Store.ttsTrModel(), listOf(Msg("user", prompt)), { my != token }, { m -> post(my, m) })
                val t = tr.text
                if (t == null) {
                    post(my, "تعذرت الترجمة: " + (tr.error ?: ""))
                    return@thread
                }
                text = t
                Session.lastTranslation = t
                post(my, "تمت الترجمة، جاري التحويل إلى صوت")
            }
            // محرك النظام: يولّد ملفًا ويحوّله لنفس شكل ناتج Gemini، ثم يكمل نفس المسار (فحص، مزج، حفظ، تشغيل)
            val out = if (isSystem) {
                SystemTts.synth(appCtx, text, { my != token }, { m -> post(my, m) })
            } else {
                TtsEngine.synthMulti(text, TtsEngine.activeVoices(), Store.mixMode(), TtsEngine.styleText(), { my != token }, { m -> post(my, m) })
            }
            val pcm = out.pcm
            if (pcm == null) {
                post(my, "تعذر التحويل: " + (out.error ?: ""))
                return@thread
            }
            val pcmBad = AudioCheck.pcm(pcm, out.rate)
            if (pcmBad != null) {
                post(my, "تعذر التحويل: " + pcmBad)
                return@thread
            }
            try {
                val stamp = Files.stamp()
                val dry = File(cacheDir, "tts_dry_" + stamp + ".wav")
                dry.writeBytes(TtsEngine.wav(pcm, out.rate))
                var finalPath = dry.absolutePath
                var mixFailed = false
                if (Store.fxOn()) {
                    post(my, "جاري مزج الموسيقى والمؤثرات")
                    try {
                        val mixed = Fx.mix(pcm, out.rate, Store.fxMusic(), Store.fxAmb1(), Store.fxAmb2(),
                            Store.fxVol(0), Store.fxVol(1), Store.fxVol(2), Store.fxIntro(), Store.fxOutro())
                        val mf = File(cacheDir, "tts_mix_" + stamp + ".wav")
                        mf.writeBytes(TtsEngine.wav(mixed, out.rate))
                        finalPath = mf.absolutePath
                    } catch (e: Throwable) {
                        // فشل المزج: نكمل بالملف الجاف بدل ما نخسر الصوت
                        mixFailed = true
                    }
                }
                val failedMix = mixFailed
                val fileBad = AudioCheck.wavFile(File(finalPath))
                if (fileBad != null) {
                    post(my, fileBad)
                    return@thread
                }
                val fp = finalPath
                runOnUiThread {
                    if (my == token) {
                        Session.lastDryPath = dry.absolutePath
                        Session.lastRate = out.rate
                        Session.lastAudioPath = fp
                        status.text = if (failedMix) "تعذر مزج الموسيقى، جاري تشغيل الصوت بدونها" else "تم التحويل، جاري التشغيل"
                        val ok = AudioPlayer.play(fp) { status.text = "انتهى التشغيل" }
                        if (!ok) status.text = "تم إنشاء الملف لكن تعذر تشغيله"
                    }
                }
            } catch (e: Exception) {
                post(my, "تعذر حفظ الملف الصوتي")
            }
        }
    }

    private fun audioBytes(): ByteArray? {
        val path = Session.lastAudioPath
        if (path.isEmpty()) return null
        val f = File(path)
        if (AudioCheck.wavFile(f) != null) return null
        return f.readBytes()
    }

    private fun saveAudio() {
        val b = audioBytes()
        if (b == null) {
            Ui.toast(this, "لا يوجد ملف صوتي سليم بعد، أعد التحويل")
            return
        }
        val name = "SmartVision_" + Files.stamp() + ".wav"
        val r = Files.save(this, name, "audio/wav", b)
        if (r != null) {
            Session.lastSavedDesc = r
            Ui.toast(this, "تم الحفظ في " + r)
        } else {
            Ui.toast(this, "تعذر الحفظ")
        }
    }

    private fun shareAudio() {
        val b = audioBytes()
        if (b == null) {
            Ui.toast(this, "لا يوجد ملف صوتي سليم بعد، أعد التحويل")
            return
        }
        val uri = Files.insertDownload(this, "SmartVision_" + Files.stamp() + ".wav", "audio/wav", b)
        if (uri == null) {
            Ui.toast(this, "المشاركة تحتاج أندرويد 10 أو أحدث. استخدم تنزيل الملف")
            return
        }
        val i = Intent(Intent.ACTION_SEND)
        i.type = "audio/wav"
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "مشاركة الملف الصوتي"))
    }

    override fun onDestroy() {
        token++
        Http.cancelAll()
        Speaker.stop()
        super.onDestroy()
    }
}
