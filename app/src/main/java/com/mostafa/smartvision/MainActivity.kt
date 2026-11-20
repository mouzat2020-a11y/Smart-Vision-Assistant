package com.mostafa.smartvision

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var output: TextView
    private lateinit var prompt: EditText
    private lateinit var apiKey: EditText
    private lateinit var modelName: EditText
    private lateinit var provider: Spinner
    private lateinit var autoSpeak: CheckBox
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var selectedImage: Bitmap? = null
    private var currentProvider = 0
    private val pickImage = 21
    private val takePhoto = 22
    private val providerNames = listOf("Gemini", "OpenRouter", "Groq")
    private val defaultModels = listOf("gemini-3.8-flash", "openrouter/auto", "qwen/qwen3.8-27b")
    private val modelChoices = listOf(
        listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash"),
        listOf("openrouter/auto"),
        listOf("qwen/qwen3.8-27b", "qwen/qwen3.6-27b")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        initTts(prefs.getString("engine", null))
        currentProvider = prefs.getInt("provider", 0).coerceIn(0, 2)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        content.addView(TextView(this).apply { text = "المساعد البصري الذكي"; textSize = 25f; gravity = Gravity.CENTER; contentDescription = "المساعد البصري الذكي"; setPadding(8, 18, 8, 22) })

        addLabel(content, "مزود الذكاء الاصطناعي")
        provider = Spinner(this)
        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providerNames)
        provider.setSelection(currentProvider)
        content.addView(provider)

        addLabel(content, "مفتاح API")
        apiKey = EditText(this).apply { hint = "مفتاح API"; setSingleLine(true); inputType = 129; setText(loadKey(currentProvider)) }
        content.addView(apiKey)

        addLabel(content, "اسم النموذج (يمكنك تعديله بالكتابة)")
        modelName = EditText(this).apply { hint = "اسم النموذج"; setSingleLine(true); setText(prefs.getString("model_$currentProvider", defaultModels[currentProvider])) }
        content.addView(modelName)
        addButton(content, "اختيار النموذج من القائمة") { chooseModel() }

        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != currentProvider) {
                    prefs.edit()
                        .putString("model_$currentProvider", modelName.text.toString().trim())
                        .putString("key_$currentProvider", apiKey.text.toString().trim())
                        .apply()
                    currentProvider = position
                    modelName.setText(prefs.getString("model_$position", defaultModels[position]))
                    apiKey.setText(loadKey(position))
                }
                prefs.edit().putInt("provider", position).apply()
            }
        }

        addLabel(content, "اكتب طلبك")
        prompt = EditText(this).apply { hint = "اكتب سؤالك أو النص المطلوب"; minLines = 3; gravity = Gravity.TOP or Gravity.START }
        content.addView(prompt)

        addButton(content, "اختيار صورة من الجهاز") { openGallery() }
        addButton(content, "التقاط صورة بالكاميرا") { takePicture() }
        addButton(content, "وصف الصورة أو إرسال السؤال") { sendRequest() }
        addButton(content, "قراءة آخر نتيجة صوتيًا") { speak(output.text.toString()) }
        addButton(content, "مسح الصورة المختارة") { selectedImage = null; announce("تم مسح الصورة المختارة") }

        addLabel(content, "إعدادات القراءة الصوتية")
        autoSpeak = CheckBox(this).apply { text = "قراءة النتيجة صوتيًا تلقائيًا"; textSize = 16f; isChecked = prefs.getBoolean("auto", true) }
        content.addView(autoSpeak)
        addButton(content, "اختيار محرك القراءة") { chooseEngine() }
        addButton(content, "سرعة القراءة") { chooseRate() }
        addButton(content, "إعدادات الصوت في النظام") { openSystemTts() }

        addLabel(content, "النتيجة")
        output = TextView(this).apply { text = "أهلًا بك. احفظ مفتاح API، ثم اكتب سؤالك أو اختر صورة."; textSize = 18f; setTextIsSelectable(true); setPadding(12, 16, 12, 24) }
        content.addView(output)

        addButton(root, "حفظ الإعدادات") { saveAll(); announce("تم حفظ الإعدادات على الجهاز") }
    }

    private fun loadKey(index: Int): String {
        val saved = prefs.getString("key_$index", null)
        if (saved != null) return saved
        return if (index == 0) prefs.getString("key", "") ?: "" else ""
    }

    private fun saveAll() {
        prefs.edit()
            .putInt("provider", provider.selectedItemPosition)
            .putString("key_$currentProvider", apiKey.text.toString().trim())
            .putString("model_$currentProvider", modelName.text.toString().trim())
            .putBoolean("auto", autoSpeak.isChecked)
            .apply()
    }

    private fun addLabel(parent: LinearLayout, value: String) {
        parent.addView(TextView(this).apply { text = value; textSize = 17f; setPadding(4, 14, 4, 5) })
    }

    private fun addButton(parent: LinearLayout, value: String, action: () -> Unit) {
        parent.addView(Button(this).apply { text = value; textSize = 16f; isAllCaps = false; contentDescription = value; setOnClickListener { action() } })
    }

    private fun announce(value: String) {
        output.text = value
        output.contentDescription = value
    }

    private fun initTts(engine: String?) {
        tts?.stop()
        tts?.shutdown()
        tts = if (engine.isNullOrEmpty()) TextToSpeech(this, this) else TextToSpeech(this, this, engine)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("ar"))
            tts?.setSpeechRate(prefs.getFloat("rate", 1.0f))
        }
    }

    private fun speak(text: String) {
        if (text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "result")
    }

    private fun chooseModel() {
        val items = modelChoices[currentProvider].toTypedArray()
        AlertDialog.Builder(this).setTitle("اختيار النموذج").setItems(items) { _, which ->
            modelName.setText(items[which])
            announce("تم اختيار النموذج ${items[which]}. اضغط حفظ الإعدادات")
        }.show()
    }

    private fun chooseEngine() {
        val engines = tts?.engines
        if (engines == null || engines.isEmpty()) { announce("لا توجد محركات قراءة متاحة"); return }
        val labels = engines.map { it.label.toString() }.toTypedArray()
        AlertDialog.Builder(this).setTitle("اختيار محرك القراءة").setItems(labels) { _, which ->
            val name = engines[which].name
            prefs.edit().putString("engine", name).apply()
            initTts(name)
            announce("تم اختيار محرك القراءة ${labels[which]}")
        }.show()
    }

    private fun chooseRate() {
        val labels = arrayOf("بطيئة", "عادية", "سريعة", "سريعة جدًا")
        val rates = floatArrayOf(0.7f, 1.0f, 1.4f, 1.8f)
        AlertDialog.Builder(this).setTitle("سرعة القراءة").setItems(labels) { _, which ->
            prefs.edit().putFloat("rate", rates[which]).apply()
            tts?.setSpeechRate(rates[which])
            announce("تم ضبط سرعة القراءة: ${labels[which]}")
        }.show()
    }

    private fun openSystemTts() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            announce("تعذر فتح إعدادات الصوت. افتحها من إعدادات الهاتف ثم إمكانية الوصول")
        }
    }

    private fun openGallery() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(i, pickImage)
    }

    private fun takePicture() {
        try {
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), takePhoto)
        } catch (e: Exception) {
            announce("الكاميرا غير متاحة على هذا الجهاز")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        try {
            if (requestCode == pickImage) {
                val uri: Uri = data.data ?: return
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
                selectedImage = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                announce("تم اختيار الصورة. اكتب ما تريد معرفته ثم اضغط إرسال")
            } else if (requestCode == takePhoto) {
                selectedImage = data.extras?.get("data") as? Bitmap
                announce(if (selectedImage != null) "تم التقاط الصورة" else "لم تصل صورة من الكاميرا")
            }
        } catch (e: Exception) {
            announce("تعذر فتح الصورة: ${e.message}")
        }
    }

    private fun sendRequest() {
        saveAll()
        val key = apiKey.text.toString().trim()
        val question = prompt.text.toString().trim()
        val model = modelName.text.toString().trim().removePrefix("models/")
        if (key.isEmpty()) { announce("أدخل مفتاح API أولًا"); return }
        if (model.isEmpty()) { announce("أدخل اسم النموذج أو اخترها من القائمة"); return }
        if (question.isEmpty() && selectedImage == null) { announce("اكتب سؤالًا أو اختر صورة أولًا"); return }
        announce("جارٍ الاتصال بالذكاء الاصطناعي. انتظر قليلًا")
        val mode = provider.selectedItemPosition
        val image = selectedImage
        val speakAuto = autoSpeak.isChecked
        thread {
            try {
                val answer = when (mode) {
                    0 -> callGemini(key, model, question.ifEmpty { "صف هذه الصورة بالتفصيل وبأسلوب واضح لمستخدم كفيف." }, image)
                    1 -> callOpenAICompatible("https://openrouter.ai/api/v1/chat/completions", key, model, question, image)
                    else -> callOpenAICompatible("https://api.groq.com/openai/v1/chat/completions", key, model, question, image)
                }
                runOnUiThread {
                    announce(answer)
                    if (speakAuto) speak(answer)
                }
            } catch (e: Exception) {
                runOnUiThread { announce("حدث خطأ أثناء الاتصال: ${e.message ?: "خطأ غير معروف"}") }
            }
        }
    }

    private fun shrink(bitmap: Bitmap): Bitmap {
        val biggest = maxOf(bitmap.width, bitmap.height)
        if (biggest <= 1600) return bitmap
        val ratio = 1600f / biggest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    }

    private fun bitmapData(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        shrink(bitmap).compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun callGemini(key: String, model: String, question: String, image: Bitmap?): String {
        val parts = JSONArray().put(JSONObject().put("text", "أجب بلغة واضحة ومناسبة لقارئ الشاشة، دون زخارف أو تنسيق Markdown. $question"))
        if (image != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", bitmapData(image))))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).toString()
        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 30000; readTimeout = 90000
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val raw = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        if (conn.responseCode !in 200..299) throw Exception(raw.take(500))
        val candidates = JSONObject(raw).optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) throw Exception("لم يصل رد من النموذج: " + raw.take(300))
        val resultParts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until resultParts.length()) {
            val p = resultParts.getJSONObject(i)
            if (p.has("text") && !p.optBoolean("thought", false)) sb.append(p.getString("text"))
        }
        if (sb.isEmpty()) throw Exception("الرد فارغ")
        return sb.toString()
    }

    private fun callOpenAICompatible(endpoint: String, key: String, model: String, question: String, image: Bitmap?): String {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "أجب بلغة واضحة ومناسبة لقارئ الشاشة دون زخارف. ${question.ifEmpty { "صف الصورة بالتفصيل." }}"))
        if (image != null) content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${bitmapData(image)}")))
        val body = JSONObject().put("model", model).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content))).toString()
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 30000; readTimeout = 90000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val raw = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        if (conn.responseCode !in 200..299) throw Exception(raw.take(500))
        return JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
