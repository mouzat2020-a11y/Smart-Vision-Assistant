package com.mostafa.smartvision

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Speaker.init(this)
        val p = Page(this, Session.resultTitle)
        val e = p.edit("النتيجة هنا، يمكنك تعديلها", Session.resultText, 8, false)
        p.button("استماع / إيقاف الاستماع") {
            if (Speaker.isSpeaking()) Speaker.stop() else Speaker.speak(e.text.toString())
        }
        p.button("نسخ") { Ui.copy(this, e.text.toString()) }
        p.button("مشاركة") { Ui.share(this, e.text.toString()) }
        p.button("حفظ في ملف") {
            val r = Files.save(this, "SmartVision_" + Files.stamp() + ".txt", "text/plain", e.text.toString().toByteArray(Charsets.UTF_8))
            if (r != null) Ui.toast(this, "تم الحفظ في " + r) else Ui.toast(this, "تعذر الحفظ")
        }
        if (Session.history != null) {
            p.button("الدردشة حول النتيجة (أسئلة إضافية)") {
                Speaker.stop()
                startActivity(Intent(this, ChatActivity::class.java))
            }
        }
        if (Session.repeatMode.isNotEmpty()) {
            p.button(Session.repeatLabel) {
                Speaker.stop()
                val i = Intent(this, WorkActivity::class.java)
                i.putExtra("mode", Session.repeatMode)
                i.putExtra("source", Session.repeatSource)
                i.putExtra("extra", Session.repeatExtra)
                startActivity(i)
                finish()
            }
        }
        p.bottomButton("رجوع للقائمة الرئيسية") {
            Speaker.stop()
            Ui.home(this)
        }
        if (Store.autoSpeak()) Speaker.speak(Session.resultText)
    }

    override fun onDestroy() {
        Speaker.stop()
        super.onDestroy()
    }
}
