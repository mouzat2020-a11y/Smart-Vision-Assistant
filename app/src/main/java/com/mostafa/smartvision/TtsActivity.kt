package com.mostafa.smartvision

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

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
        input = p.edit("اكتب النص هنا", "", 7, false)
        status = p.text("", 16f)
        Ui.live(status)
        p.button("تحويل إلى ملف صوتي وتشغيله") { convert(false) }
        p.button("ترجمة النص ثم تحويله إلى صوت") { convert(true) }
        p.button("قراءة النص فورًا بصوت النظام") {
            val t = input.text.toString()
            if (t.isBlank()) Ui.toast(this, "اكتب النص أولًا") else Speaker.speak(t)
        }
        p.button("إيقاف") {
            token++
            Speaker.stop()
            AudioPlayer.stop()
            status.text = "تم الإيقاف"
        }
        p.button("تشغيل آخر ملف صوتي") {
            val path = Session.lastAudioPath
            if (path.isEmpty() || !File(path).exists()) {
                Ui.toast(this, "لا يوجد ملف صوتي بعد")
            } else {
                Speaker.stop()
                if (!AudioPlayer.play(path) { status.text = "انتهى التشغيل" }) status.text = "تعذر التشغيل"
            }
        }
        p.button("مشاركة آخر ملف صوتي") { shareAudio() }
        p.button("حفظ آخر ملف في التنزيلات") { saveAudio() }
        p.button("لصق من الحافظة") { input.setText(Ui.pasteText(this)) }
        p.button("مسح النص") { input.setText("") }
        p.button("الإعدادات: الصوت والانفعال واللهجة والسرعة") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        p.bottomButton("رجوع") { finish() }
    }

    override fun onResume() {
        super.onResume()
        var vd = ""
        for (x in Prompts.VOICES) if (x.first == Store.ttsVoice()) vd = x.second
        summary.text = "الصوت: " + Store.ttsVoice() + " (" + vd + ")، الانفعال: " + Prompts.EMO[Store.ttsEmotion()].first +
            "، اللهجة: " + Prompts.ACCENTS[Store.ttsAccent()].first + "، السرعة: " + Prompts.PACE[Store.ttsPace()].first +
            "، لغة الترجمة: " + Prompts.LANGS[Store.ttsLang()].first
    }

    private fun post(my: Int, m: String) {
        runOnUiThread { if (my == token) status.text = m }
    }

    private fun convert(translateFirst: Boolean) {
        val txt = input.text.toString().trim()
        if (txt.isEmpty()) {
            Ui.toast(this, "اكتب النص أولًا")
            return
        }
        if (!Store.hasKey("gemini")) {
            Ui.message(this, "مفتاح Gemini", "التحويل بأصوات Gemini يحتاج مفتاح Gemini، أضفه من الإعدادات. ويمكنك استخدام «قراءة النص فورًا بصوت النظام» بدون مفتاح.")
            return
        }
        token++
        val my = token
        status.text = "جاري التحويل"
        Speaker.stop()
        AudioPlayer.stop()
        thread {
            var text = txt
            if (translateFirst) {
                val lang = Prompts.LANGS[Store.ttsLang()]
                val prompt = "Translate the following text into " + lang.second + ". Return only the translated text, with no explanations or notes. Keep the line breaks.\n\n" + txt
                val tr = Ai.ask(listOf(Msg("user", prompt)), { my != token }, { m -> post(my, m) })
                val t = tr.text
                if (t == null) {
                    post(my, "تعذرت الترجمة: " + (tr.error ?: ""))
                    return@thread
                }
                text = t
                post(my, "تمت الترجمة، جاري التحويل إلى صوت")
            }
            val out = TtsEngine.synthAll(text, Store.ttsVoice(), TtsEngine.styleText(), { my != token }, { m -> post(my, m) })
            val pcm = out.pcm
            if (pcm == null) {
                post(my, "تعذر التحويل: " + (out.error ?: ""))
                return@thread
            }
            try {
                val f = File(cacheDir, "tts_" + Files.stamp() + ".wav")
                f.writeBytes(TtsEngine.wav(pcm, out.rate))
                runOnUiThread {
                    if (my == token) {
                        Session.lastAudioPath = f.absolutePath
                        status.text = "تم التحويل، جاري التشغيل"
                        val ok = AudioPlayer.play(f.absolutePath) { status.text = "انتهى التشغيل" }
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
        if (!f.exists()) return null
        return f.readBytes()
    }

    private fun saveAudio() {
        val b = audioBytes()
        if (b == null) {
            Ui.toast(this, "لا يوجد ملف صوتي بعد")
            return
        }
        val r = Files.save(this, "SmartVision_" + Files.stamp() + ".wav", "audio/wav", b)
        if (r != null) Ui.toast(this, "تم الحفظ في " + r) else Ui.toast(this, "تعذر الحفظ")
    }

    private fun shareAudio() {
        val b = audioBytes()
        if (b == null) {
            Ui.toast(this, "لا يوجد ملف صوتي بعد")
            return
        }
        val uri = Files.insertDownload(this, "SmartVision_" + Files.stamp() + ".wav", "audio/wav", b)
        if (uri == null) {
            Ui.toast(this, "المشاركة تحتاج أندرويد 10 أو أحدث. استخدم حفظ الملف")
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
        Speaker.stop()
        super.onDestroy()
    }
}
