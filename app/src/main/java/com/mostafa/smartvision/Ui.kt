package com.mostafa.smartvision

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// صفحة بسيطة: عنوان + محتوى قابل للتمرير + أزرار ثابتة بالأسفل (اختياري)
class Page(val act: Activity, title: String) {
    val root = LinearLayout(act)
    val content = LinearLayout(act)
    val bottom = LinearLayout(act)
    val pinned = LinearLayout(act)

    init {
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(24, 24, 24, 24)
        content.orientation = LinearLayout.VERTICAL
        content.layoutDirection = View.LAYOUT_DIRECTION_RTL
        bottom.orientation = LinearLayout.VERTICAL
        bottom.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val sv = ScrollView(act)
        sv.addView(content)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        pinned.orientation = LinearLayout.VERTICAL
        pinned.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.addView(pinned, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        act.setContentView(root)
        act.title = title
        heading(title, 24f)
    }

    fun heading(text: String, size: Float): TextView {
        val t = TextView(act)
        t.text = text
        t.textSize = size
        t.gravity = Gravity.CENTER
        t.setPadding(8, 18, 8, 14)
        if (Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true)
        content.addView(t)
        return t
    }

    fun section(text: String): TextView { return heading(text, 20f) }

    fun label(text: String): TextView {
        val t = TextView(act)
        t.text = text
        t.textSize = 17f
        t.setPadding(4, 14, 4, 5)
        content.addView(t)
        return t
    }

    fun text(text: String, size: Float): TextView {
        val t = TextView(act)
        t.text = text
        t.textSize = size
        t.setPadding(4, 8, 4, 8)
        content.addView(t)
        return t
    }

    fun edit(hint: String, initial: String, lines: Int, secret: Boolean): EditText {
        val e = EditText(act)
        e.hint = hint
        e.contentDescription = hint
        e.setText(initial)
        if (lines > 1) {
            e.minLines = lines
            e.gravity = Gravity.TOP or Gravity.START
        } else {
            e.setSingleLine(true)
        }
        if (secret) e.inputType = 129
        content.addView(e)
        return e
    }

    // مربع كتابة ثابت أسفل الشاشة فوق أزرار التنقل (مثل تطبيقات الدردشة)
    fun pinnedEdit(hint: String, initial: String): EditText {
        val e = EditText(act)
        e.hint = hint
        e.contentDescription = hint
        e.setText(initial)
        e.minLines = 2
        e.maxLines = 5
        e.gravity = Gravity.TOP or Gravity.START
        e.isVerticalScrollBarEnabled = true
        act.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        pinned.addView(e)
        return e
    }

    // زر ثابت أسفل الشاشة تحت مربع الكتابة مباشرة
    fun pinnedButton(label: String, action: () -> Unit): Button {
        val b = Button(act)
        b.text = label
        b.textSize = 16f
        b.isAllCaps = false
        b.setOnClickListener { action() }
        pinned.addView(b)
        return b
    }

    fun button(label: String, action: () -> Unit): Button {
        val b = Button(act)
        b.text = label
        b.textSize = 16f
        b.isAllCaps = false
        b.setOnClickListener { action() }
        content.addView(b)
        return b
    }

    fun bottomButton(label: String, action: () -> Unit): Button {
        val b = Button(act)
        b.text = label
        b.textSize = 16f
        b.isAllCaps = false
        b.setOnClickListener { action() }
        bottom.addView(b)
        return b
    }

    fun check(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox {
        val c = CheckBox(act)
        c.text = label
        c.textSize = 16f
        c.isChecked = checked
        c.setOnCheckedChangeListener { _, v -> onChange(v) }
        content.addView(c)
        return c
    }
}

object Ui {
    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun live(t: TextView) {
        t.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun choose(act: Activity, title: String, items: List<String>, onPick: (Int) -> Unit) {
        val arr = items.toTypedArray()
        AlertDialog.Builder(act).setTitle(title).setItems(arr) { _, which -> onPick(which) }.show()
    }

    fun message(act: Activity, title: String, text: String) {
        AlertDialog.Builder(act).setTitle(title).setMessage(text).setPositiveButton("حسنًا", null).show()
    }

    fun input(act: Activity, title: String, hint: String, initial: String, lines: Int, okLabel: String, onOk: (String) -> Unit) {
        val e = EditText(act)
        e.hint = hint
        e.contentDescription = hint
        e.setText(initial)
        if (lines > 1) {
            e.minLines = lines
            e.gravity = Gravity.TOP or Gravity.START
        }
        AlertDialog.Builder(act).setTitle(title).setView(e)
            .setPositiveButton(okLabel) { _, _ -> onOk(e.text.toString()) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        toast(ctx, "تم النسخ")
    }

    fun share(act: Activity, text: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, text)
        act.startActivity(Intent.createChooser(i, "مشاركة"))
    }

    fun pasteText(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(ctx).toString()
    }

    fun home(act: Activity) {
        val i = Intent(act, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        act.startActivity(i)
        act.finish()
    }
}
