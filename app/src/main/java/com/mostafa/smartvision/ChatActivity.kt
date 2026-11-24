package com.mostafa.smartvision

import android.app.Activity
import android.os.Bundle
import kotlin.concurrent.thread

class ChatActivity : Activity() {
    private var sending = false
    private var token = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Speaker.init(this)
        val hist = Session.history
        if (hist == null) {
            finish()
            return
        }
        val p = Page(this, "الدردشة")
        val log = p.text("آخر نتيجة:\n" + Session.resultText, 16f)
        log.setTextIsSelectable(true)
        Ui.live(log)
        val e = p.pinnedEdit("اكتب سؤالك...", "")
        p.pinnedButton("إرسال") {
            val q = e.text.toString().trim()
            if (sending) {
                Ui.toast(this, "انتظر الرد الحالي")
            } else if (q.isEmpty()) {
                Ui.toast(this, "اكتب سؤالًا أولًا")
            } else {
                sending = true
                token++
                val my = token
                e.setText("")
                log.text = "جاري الرد..."
                hist.add(Msg("user", q))
                thread {
                    val r = Ai.ask(hist, { my != token }, { })
                    runOnUiThread {
                        if (my != token) return@runOnUiThread
                        sending = false
                        val t = r.text
                        if (t != null) {
                            hist.add(Msg("model", t))
                            log.text = t
                            if (Store.autoSpeak()) Speaker.speak(t)
                        } else {
                            hist.removeAt(hist.size - 1)
                            log.text = "تعذر الرد: " + (r.error ?: "")
                        }
                    }
                }
            }
        }
        p.button("إيقاف الرد الجاري") {
            if (sending) {
                token++
                Http.cancelAll()
                sending = false
                if (hist.isNotEmpty() && hist[hist.size - 1].role == "user") hist.removeAt(hist.size - 1)
                log.text = "تم إيقاف الرد"
            } else {
                Ui.toast(this, "لا يوجد رد جارٍ")
            }
        }
        p.button("استماع / إيقاف الاستماع") {
            if (Speaker.isSpeaking()) Speaker.stop() else Speaker.speak(log.text.toString())
        }
        p.button("نسخ الرد") { Ui.copy(this, log.text.toString()) }
        p.bottomButton("رجوع") {
            Speaker.stop()
            finish()
        }
    }

    override fun onDestroy() {
        token++
        Http.cancelAll()
        Speaker.stop()
        super.onDestroy()
    }
}
