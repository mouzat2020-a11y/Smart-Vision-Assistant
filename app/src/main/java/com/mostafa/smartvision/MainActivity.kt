package com.mostafa.smartvision

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Base64
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
    private lateinit var provider: Spinner
    private var tts: TextToSpeech? = null
    private var selectedImage: Bitmap? = null
    private val pickImage = 21
    private val takePhoto = 22

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        content.addView(TextView(this).apply { text = "المساعد البصري الذكي"; textSize = 25f; gravity = Gravity.CENTER; contentDescription = text; setPadding(8, 18, 8, 22) })
        content.addView(TextView(this).apply { text = "نسخة أولية مستقلة تدعم قارئات الشاشة"; textSize = 16f; gravity = Gravity.CENTER; setPadding(8, 4, 8, 18) })
        provider = Spinner(this)
        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Gemini", "OpenRouter", "Groq"))
        provider.setSelection(prefs.getInt("provider", 0))
        addLabel(content, "مزود الذكاء الاصطناعي")
        content.addView(provider)
        apiKey = EditText(this).apply { hint = "مفتاح API"; setSingleLine(true); inputType = 129; setText(prefs.getString("key", "")) }
        addLabel(content, "مفتاح API")
        content.addView(apiKey)
        prompt = EditText(this).apply { hint = "اكتب سؤالك أو النص المطلوب"; minLines = 3; gravity = Gravity.TOP or Gravity.START }
        addLabel(content, "اكتب طلبك")
        content.addView(prompt)
        addButton(content, "اختيار صورة من الجهاز") { openGallery() }
        addButton(content, "التقاط صورة بالكاميرا") { takePicture() }
        addButton(content, "وصف الصورة أو إرسال السؤال") { sendRequest() }
        addButton(content, "قراءة آخر نتيجة صوتيًا") { val s = output.text.toString(); if (s.isNotBlank()) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "result") }
        addButton(content, "مسح الصورة المختارة") { selectedImage = null; announce("تم مسح الصورة المختارة") }
        addLabel(content, "النتيجة")
        output = TextView(this).apply { text = "أهلًا بك. احفظ مفتاح API، ثم اكتب سؤالك أو اختر صورة."; textSize = 18f; setTextIsSelectable(true); setPadding(12, 16, 12, 24); contentDescription = text }
        content.addView(output)
        addButton(root, "حفظ الإعدادات") { prefs.edit().putInt("provider", provider.selectedItemPosition).putString("key", apiKey.text.toString().trim()).apply(); announce("تم حفظ الإعدادات على الجهاز") }
        provider.setOnItemSelectedListener(object: AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putInt("provider", position).apply() }
        })
    }

    private fun addLabel(parent: LinearLayout, value: String) { parent.addView(TextView(this).apply { text = value; textSize = 17f; setPadding(4, 14, 4, 5) }) }
    private fun addButton(parent: LinearLayout, value: String, action: () -> Unit) { parent.addView(Button(this).apply { text = value; textSize = 16f; isAllCaps = false; contentDescription = value; setOnClickListener { action() } }) }
    private fun announce(value: String) { output.text = value; output.contentDescription = value }
    private fun openGallery() { val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }; startActivityForResult(i, pickImage) }
    private fun takePicture() { try { startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), takePhoto) } catch (e: Exception) { announce("الكاميرا غير متاحة على هذا الجهاز") } }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (resultCode != RESULT_OK || data == null) return
        try { if (requestCode == pickImage) { val uri: Uri = data.data ?: return; contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); selectedImage = MediaStore.Images.Media.getBitmap(contentResolver, uri); announce("تم اختيار الصورة. اكتب ما تريد معرفته ثم اضغط إرسال") }
            else if (requestCode == takePhoto) { selectedImage = data.extras?.get("data") as? Bitmap; announce(if (selectedImage != null) "تم التقاط الصورة" else "لم تصل صورة من الكاميرا") } } catch (e: Exception) { announce("تعذر فتح الصورة: ${e.message}") }
    }
    private fun sendRequest() {
        val key = apiKey.text.toString().trim(); val question = prompt.text.toString().trim()
        if (key.isEmpty()) { announce("أدخل مفتاح API أولًا ثم احفظ الإعدادات"); return }
        if (question.isEmpty() && selectedImage == null) { announce("اكتب سؤالًا أو اختر صورة أولًا"); return }
        announce("جارٍ الاتصال بالذكاء الاصطناعي. انتظر قليلًا")
        val mode = provider.selectedItemPosition
        val image = selectedImage
        thread {
            try {
                val answer = when (mode) {
                    0 -> callGemini(key, question.ifEmpty { "صف هذه الصورة بالتفصيل وبأسلوب واضح لمستخدم كفيف." }, image)
                    1 -> callOpenAICompatible("https://openrouter.ai/api/v1/chat/completions", key, "openrouter/auto", question, image)
                    else -> callOpenAICompatible("https://api.groq.com/openai/v1/chat/completions", key, "llama-3.2-11b-vision-preview", question, image)
                }
                runOnUiThread { announce(answer); tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "answer") }
            } catch (e: Exception) { runOnUiThread { announce("حدث خطأ أثناء الاتصال: ${e.message ?: "خطأ غير معروف"}") } }
        }
    }
    private fun bitmapData(bitmap: Bitmap): String { val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out); return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) }
    private fun callGemini(key: String, question: String, image: Bitmap?): String {
        val parts = JSONArray().put(JSONObject().put("text", "أجب بلغة واضحة ومناسبة لقارئ الشاشة، دون زخارف أو تنسيق Markdown. $question"))
        if (image != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", bitmapData(image))))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).toString()
        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; connectTimeout = 30000; readTimeout = 60000; setRequestProperty("Content-Type", "application/json") }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val raw = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        if (conn.responseCode !in 200..299) throw Exception(raw.take(500))
        return JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }
    private fun callOpenAICompatible(endpoint: String, key: String, model: String, question: String, image: Bitmap?): String {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "أجب بلغة واضحة ومناسبة لقارئ الشاشة دون زخارف. ${question.ifEmpty { "صف الصورة بالتفصيل." }}"))
        if (image != null) content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${bitmapData(image)}")))
        val body = JSONObject().put("model", model).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content))).toString()
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; connectTimeout = 30000; readTimeout = 60000; setRequestProperty("Authorization", "Bearer $key"); setRequestProperty("Content-Type", "application/json") }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val raw = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        if (conn.responseCode !in 200..299) throw Exception(raw.take(500))
        return JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ar") }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }
}
