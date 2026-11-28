package com.mostafa.smartvision

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

class MainActivity : Activity() {
    private lateinit var page: Page
    private val tabButtons = ArrayList<Button>()
    private val tabNames = listOf("الصور", "المساعدات والكتابة", "الصوت", "الأخبار")

    private val imageTools = listOf(
        Pair("currency", "التعرف على العملات الورقية"),
        Pair("ocr", "قراءة النصوص من صورة (OCR)"),
        Pair("color", "التعرف على الألوان"),
        Pair("objects", "التعرف على الأشياء والمنتجات"),
        Pair("captcha", "قراءة الكابتشا (صورة التحقق)"),
        Pair("people", "وصف الأشخاص في الصورة"),
        Pair("nature", "التعرف على النباتات والحيوانات والفواكه"),
        Pair("subtitle", "قراءة الترجمة الظاهرة على فيديو مصوّر"),
        Pair("translate", "ترجمة النص الموجود داخل صورة")
    )

    // أيقونة لكل تبويب (الصور، المساعدات والكتابة، الصوت، الأخبار)
    private val tabIcons = listOf(R.drawable.tab_images, R.drawable.tab_help, R.drawable.tab_audio, R.drawable.tab_news)

    private val dailyTools = listOf(
        Pair("find", "ابحث عن شيء في المكان"),
        Pair("medicine", "اقرأ عبوة دواء أو منتج صحي"),
        Pair("document", "اقرأ مستندًا أو فاتورة أو خطابًا"),
        Pair("signs", "اقرأ اللافتات وأرقام الغرف والأبواب"),
        Pair("clothing", "تعرّف على الملابس والألوان واقترح تنسيقًا"),
        Pair("shopping", "اقرأ أسعار المنتجات وقارن الظاهر"),
        Pair("appliance", "تعرّف على الأجهزة ولوحات الأزرار"),
        Pair("seating", "ابحث عن الكراسي والمقاعد")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        // نغمة قصيرة عند فتح التطبيق من أيقونته فقط (لا عند الرجوع للرئيسية من داخل التطبيق ولا عند تدوير الشاشة)
        if (savedInstanceState == null && intent != null && Intent.ACTION_MAIN == intent.action && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            Chime.play()
        }
        Speaker.init(this)
        build()
        handleShare(intent)
        if (savedInstanceState == null && !Store.hasAny()) {
            Ui.message(this, "مرحبًا بك", "لتبدأ، افتح الإعدادات وأضف مفتاح API لمزود واحد على الأقل: Gemini أو Groq. المفاتيح تُحفظ مشفّرة على جهازك.")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleShare(intent)
    }

    private fun build() {
        page = Page(this, "المساعد البصري الذكي")
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = View.LAYOUT_DIRECTION_RTL
        for (i in tabNames.indices) {
            val b = Button(this)
            b.text = tabNames[i]
            b.isAllCaps = false
            b.textSize = 15f
            // الأيقونة فوق اسم التبويب للعين فقط، والنص والوصف الصوتي زي ما هم
            val icon = resources.getDrawable(tabIcons[i], theme)
            val sz = (36 * resources.displayMetrics.density).toInt()
            icon.setBounds(0, 0, sz, sz)
            b.setCompoundDrawablesRelative(null, icon, null, null)
            b.compoundDrawablePadding = (4 * resources.displayMetrics.density).toInt()
            b.setOnClickListener { showTab(i) }
            row.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            tabButtons.add(b)
        }
        page.bottom.addView(row)
        page.bottomButton("الإعدادات") { startActivity(Intent(this, SettingsActivity::class.java)) }
        showTab(0)
    }

    private var curTab = -1

    private fun showTab(i: Int) {
        // نغمة إخبارية لما ننتقل لتبويب الأخبار (مش كل مرة بنضغط عليه وإحنا فيه)
        if (i == 3 && curTab != 3) NewsJingle.play()
        curTab = i
        for (k in tabButtons.indices) {
            val sel = (k == i)
            tabButtons[k].isSelected = sel
            tabButtons[k].contentDescription = "تبويب " + tabNames[k] + (if (sel) "، التبويب الحالي" else "")
            tabButtons[k].alpha = if (sel) 1.0f else 0.65f
        }
        page.content.removeAllViews()
        if (i == 0) {
            page.section("وصف الصور")
            page.button("وصف صورة من المعرض") { launch("describe", "gallery") }
            page.button("وصف المشهد بالكاميرا") { launch("describe", "camera") }
            page.section("أدوات الصور المتخصصة")
            for (t in imageTools) {
                page.button(t.second) { launch(t.first, "") }
            }
            page.button("الدردشة مع صورة (اسأل عن صورة)") { launch("chat", "") }
        } else if (i == 1) {
            page.section("المستندات")
            page.button("قراءة ملف PDF") { openPdfReader() }
            page.section("مساعدات الحياة اليومية")
            for (t in dailyTools) {
                page.button(t.second) { launch(t.first, "") }
            }
            page.section("الكتابة والترجمة والأسئلة")
            page.button("الكتابة بالذكاء الاصطناعي") { launch("write", "") }
            page.button("ترجمة نص") { launch("translate_text", "") }
            page.button("اسأل الذكاء الاصطناعي") { launch("ask", "") }
        } else if (i == 2) {
            page.section("الصوت")
            page.button("تحويل النص إلى صوت") { startActivity(Intent(this, TtsActivity::class.java)) }
        } else {
            page.section("الأخبار (" + (NewsData.NAMES[NewsData.activeCountry(this)] ?: "") + ")")
            val secs = NewsSources.buildSections(NewsData.activeCountry(this))
            for (k in secs.indices) {
                page.button(secs[k].name) {
                    startActivity(Intent(this, NewsActivity::class.java).putExtra("section", k))
                }
            }
            page.button("إعدادات الأخبار") {
                startActivity(Intent(this, NewsActivity::class.java).putExtra("screen", "settings"))
            }
            page.button("مساعدة: شرح قارئ الأخبار") {
                startActivity(Intent(this, NewsActivity::class.java).putExtra("screen", "help"))
            }
        }
    }

    private fun openPdfReader() {
        if (!Store.hasAny()) {
            Ui.message(this, "مفتاح الذكاء الاصطناعي", "أضف مفتاحًا لمزود واحد على الأقل من الإعدادات أولًا.")
            return
        }
        startActivity(Intent(this, PdfActivity::class.java))
    }

    private fun launch(mode: String, source: String) {
        if (!Store.hasAny()) {
            Ui.message(this, "مفتاح الذكاء الاصطناعي", "أضف مفتاحًا لمزود واحد على الأقل من الإعدادات أولًا.")
            return
        }
        val i = Intent(this, WorkActivity::class.java)
        i.putExtra("mode", mode)
        i.putExtra("source", source)
        i.putExtra("extra", "")
        startActivity(i)
    }

    // صور مُشارَكة من تطبيقات أخرى (بديل أدوات الشاشة)
    private fun handleShare(i: Intent) {
        val action = i.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = ArrayList<String>()
        if (action == Intent.ACTION_SEND) {
            val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (u != null) uris.add(u.toString())
        } else {
            val list = i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (list != null) {
                for (u in list) uris.add(u.toString())
            }
        }
        if (uris.isEmpty()) return
        i.action = null
        if (!Store.hasAny()) {
            Ui.message(this, "مفتاح الذكاء الاصطناعي", "أضف مفتاحًا لمزود واحد على الأقل من الإعدادات أولًا.")
            return
        }
        val keys = ArrayList<String>()
        val labels = ArrayList<String>()
        keys.add("describe")
        labels.add("وصف الصورة")
        for (t in imageTools) {
            keys.add(t.first)
            labels.add(t.second)
        }
        for (t in dailyTools) {
            if (t.first != "find") {
                keys.add(t.first)
                labels.add(t.second)
            }
        }
        keys.add("chat")
        labels.add("الدردشة مع الصورة")
        Ui.choose(this, "ماذا تريد من الصورة المُشارَكة؟", labels) { idx ->
            val w = Intent(this, WorkActivity::class.java)
            w.putExtra("mode", keys[idx])
            w.putExtra("source", "uris")
            w.putExtra("extra", "")
            w.putStringArrayListExtra("uris", uris)
            startActivity(w)
        }
    }
}
