package com.mostafa.smartvision

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.ExifInterface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

// ================== حالة مشتركة بين الشاشات ==================
object Session {
    var resultTitle: String = ""
    var resultText: String = ""
    var history: MutableList<Msg>? = null
    var repeatMode: String = ""
    var repeatSource: String = ""
    var repeatExtra: String = ""
    var repeatLabel: String = ""
    var lastAudioPath: String = ""
    var lastDryPath: String = ""
    var lastRate: Int = 24000
    var lastTranslation: String = ""
    var lastSavedDesc: String = ""
}

// ================== الصور ==================
object Img {
    fun loadB64(ctx: Context, uri: Uri, maxSide: Int): String? {
        try {
            val bmp = decode(ctx, uri, maxSide) ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            return null
        }
    }

    fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        val s1 = cr.openInputStream(uri) ?: return null
        s1.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        val s2 = cr.openInputStream(uri) ?: return null
        val bmp = s2.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        var res: Bitmap = bmp
        val rot = orientation(ctx, uri)
        if (rot != 0) {
            val m = Matrix()
            m.postRotate(rot.toFloat())
            res = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val big = maxOf(res.width, res.height)
        if (big > maxSide) {
            val r = maxSide.toFloat() / big.toFloat()
            res = Bitmap.createScaledBitmap(res, (res.width * r).toInt(), (res.height * r).toInt(), true)
        }
        return res
    }

    private fun orientation(ctx: Context, uri: Uri): Int {
        try {
            val ins = ctx.contentResolver.openInputStream(uri) ?: return 0
            ins.use {
                val ex = ExifInterface(it)
                val o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90
                if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180
                if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270
            }
        } catch (e: Throwable) {
        }
        return 0
    }

    fun bitmapB64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

// ================== الملفات ==================
object Files {
    // يحفظ في التنزيلات/SmartVision ويرجع Uri (من أندرويد 10 فصاعدًا) أو null
    fun insertDownload(ctx: Context, name: String, mime: String, data: ByteArray): Uri? {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime)
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SmartVision")
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
                val os = ctx.contentResolver.openOutputStream(uri) ?: return null
                os.use { it.write(data) }
                return uri
            }
        } catch (e: Exception) {
        }
        return null
    }

    // يرجع وصف مكان الحفظ أو null لو فشل
    fun save(ctx: Context, name: String, mime: String, data: ByteArray): String? {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val uri = insertDownload(ctx, name, mime, data)
                return if (uri != null) "التنزيلات، مجلد SmartVision، الملف " + name else null
            }
            val dir = File(ctx.getExternalFilesDir(null), "SmartVision")
            dir.mkdirs()
            val f = File(dir, name)
            f.writeBytes(data)
            return f.absolutePath
        } catch (e: Exception) {
            return null
        }
    }

    fun stamp(): String {
        return java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
    }
}

// ================== النطق بمحرك النظام ==================
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var app: Context? = null

    fun init(ctx: Context) {
        if (tts != null) return
        app = ctx.applicationContext
        start(Store.engine())
    }

    private fun start(engine: String) {
        val a = app ?: return
        tts = if (engine.isEmpty()) TextToSpeech(a, this) else TextToSpeech(a, this, engine)
    }

    fun setEngine(name: String) {
        Store.setEngine(name)
        try { tts?.stop() } catch (e: Exception) {}
        try { tts?.shutdown() } catch (e: Exception) {}
        tts = null
        start(name)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("ar"))
            tts?.setSpeechRate(Store.rate())
        }
    }

    fun setRate(r: Float) {
        Store.setRate(r)
        tts?.setSpeechRate(r)
    }

    fun engines(): List<TextToSpeech.EngineInfo> {
        val t = tts ?: return emptyList()
        return t.engines
    }

    fun split(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.length > max) {
            var cut = rest.lastIndexOf('\n', max)
            if (cut < max / 2) cut = rest.lastIndexOf('.', max)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
            val end = if (cut < max / 2) max else cut + 1
            out.add(rest.substring(0, end))
            rest = rest.substring(end).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    fun speak(text: String) {
        val t = tts ?: return
        if (text.isBlank()) return
        t.stop()
        val parts = split(text, 3000)
        for (i in parts.indices) {
            t.speak(parts[i], TextToSpeech.QUEUE_ADD, null, "sv" + i)
        }
    }

    fun stop() {
        try { tts?.stop() } catch (e: Exception) {}
    }

    fun isSpeaking(): Boolean {
        val t = tts ?: return false
        return t.isSpeaking
    }
}

// ================== تشغيل ملف صوتي ==================
// علاج الفرقعة: عند انتهاء الملف لا نقطع المشغّل ولا نستدعي onDone فورًا،
// بل ننتظر لحظة حتى يُغلق مسار الصوت بهدوء، ثم نحرره، وبعدها فقط نبلّغ بالانتهاء
// (حتى لا يبدأ قارئ الشاشة بنطق "انتهى التشغيل" في نفس لحظة إغلاق الصوت).
// وعند الإيقاف اليدوي نخفض الصوت إلى صفر أولًا ثم نحرر المشغّل بعد جزء من الثانية.
object AudioPlayer {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun release(mp: MediaPlayer) {
        try { mp.stop() } catch (e: Exception) {}
        try { mp.release() } catch (e: Exception) {}
    }

    fun play(path: String, onDone: () -> Unit): Boolean {
        stop()
        try {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } catch (e: Exception) {
            }
            mp.setDataSource(path)
            mp.setOnCompletionListener {
                handler.postDelayed({
                    if (player === mp) {
                        player = null
                        release(mp)
                        onDone()
                    }
                }, 450)
            }
            mp.prepare()
            mp.start()
            player = mp
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun stop() {
        val mp = player ?: return
        player = null
        try { mp.setVolume(0f, 0f) } catch (e: Exception) {}
        handler.postDelayed({ release(mp) }, 90)
    }

    fun isPlaying(): Boolean {
        return try { player?.isPlaying ?: false } catch (e: Exception) { false }
    }
}

// ================== تحويل النص لصوت بنماذج Gemini ==================
class TtsOut(val pcm: ByteArray?, val rate: Int, val error: String?)

object TtsEngine {
    private fun isNew(id: String): Boolean { return id.startsWith("gemini-3.8") }

    fun styleText(): String {
        val parts = ArrayList<String>()
        val acc = Prompts.ACCENTS[Store.ttsAccent()].second
        if (acc.isNotEmpty()) parts.add(acc)
        if (Store.ttsEmotion() != 0) parts.add(Prompts.EMO[Store.ttsEmotion()].second)
        if (Store.ttsPace() != 2) parts.add(Prompts.PACE[Store.ttsPace()].second)
        return parts.joinToString(", ")
    }

    private fun friendly(code: Int, body: String): String {
        var msg = body
        try {
            val d = JSONObject(body)
            val e = d.optJSONObject("error")
            if (e != null) msg = e.optString("message", body)
        } catch (ex: Exception) {
        }
        if (code == 429) return "حصة نماذج الصوت انتهت الآن. جرّب بعد قليل أو غيّر نموذج الصوت من الإعدادات."
        if (code == 403 || code == 401) return "مفتاح Gemini مرفوض. راجعه من الإعدادات. (" + msg.take(100) + ")"
        if (code == -1) return "تعذر الاتصال بالإنترنت: " + msg.take(120)
        return "خطأ " + code + ": " + msg.take(200)
    }

    private fun extract(body: String): TtsOut? {
        try {
            val d = JSONObject(body)
            val c = d.optJSONArray("candidates") ?: return null
            if (c.length() == 0) return null
            val content = c.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val id = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
                if (id != null && id.has("data")) {
                    val mime = id.optString("mimeType", id.optString("mime_type", ""))
                    var rate = 24000
                    val m = Regex("rate=(\\d+)").find(mime)
                    if (m != null) rate = m.groupValues[1].toInt()
                    var b = Base64.decode(id.getString("data"), Base64.DEFAULT)
                    if (b.size > 48 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()) {
                        b = b.copyOfRange(48, b.size)
                    }
                    return TtsOut(polish(b, rate), rate, null)
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    // يحوّل مقطعًا واحدًا لصوت خام (PCM)
    fun synthChunk(text: String, voice: String, style: String, cancel: () -> Boolean, progress: (String) -> Unit): TtsOut {
        val key = Store.key("gemini")
        if (key.isEmpty()) return TtsOut(null, 0, "لا يوجد مفتاح Gemini. أضفه من الإعدادات.")
        val first = Store.ttsModel()
        val ids = ArrayList<String>()
        ids.add(first)
        for (m in Prompts.TTS_MODELS) if (m.first != first) ids.add(m.first)
        var lastErr = "فشل الاتصال"
        for (i in ids.indices) {
            if (cancel()) return TtsOut(null, 0, "تم الإيقاف")
            val id = ids[i]
            if (i > 0) progress("النموذج السابق لم ينجح، أجرّب " + id)
            val body: String
            if (isNew(id)) {
                val part = JSONObject().put("text", text)
                if (style.isNotEmpty()) part.put("speech_metadata", JSONObject().put("style", style))
                body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(part))))
                    .put("generationConfig", JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("voice", voice))))
                    .toString()
            } else {
                val clean = text.replace(Regex("<[^>]*>"), "")
                val prompt = if (style.isNotEmpty()) "Say " + style + ": " + clean else clean
                body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig",
                            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
                    .toString()
            }
            val r = Http.post(
                "https://generativelanguage.googleapis.com/v1beta/models/" + id + ":generateContent",
                body, mapOf("x-goog-api-key" to key), 120000
            )
            if (r.code == 200) {
                val out = extract(r.body)
                if (out != null) return out
                lastErr = "النموذج لم يرجع صوتًا. جرّب نصًا أو انفعالًا آخر."
            } else if (r.code == 401 || r.code == 403) {
                return TtsOut(null, 0, friendly(r.code, r.body))
            } else {
                lastErr = friendly(r.code, r.body)
            }
        }
        return TtsOut(null, 0, lastErr)
    }

    // ---------- تنعيم الصوت لمنع الفرقعة عند البداية والنهاية ----------
    private fun scaleSample(b: ByteArray, idx: Int, g: Float) {
        val lo = b[idx * 2].toInt() and 0xFF
        val hi = b[idx * 2 + 1].toInt()
        val s = (hi shl 8) or lo
        val v = (s * g).toInt()
        b[idx * 2] = (v and 0xFF).toByte()
        b[idx * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }

    fun polish(pcm: ByteArray, rate: Int): ByteArray {
        val n = pcm.size / 2
        if (n < 64) return pcm
        val out = pcm.copyOf(n * 2)
        val fin = minOf(n / 2, rate * 20 / 1000)
        val fout = minOf(n / 2, rate * 80 / 1000)
        for (i in 0 until fin) scaleSample(out, i, i.toFloat() / fin.toFloat())
        for (i in 0 until fout) scaleSample(out, n - 1 - i, i.toFloat() / fout.toFloat())
        return out
    }

    // صمت قصير قبل الكلام وبعده حتى لا يفرقع المشغّل عند البدء والإيقاف
    fun pad(pcm: ByteArray, rate: Int): ByteArray {
        val head = ByteArray((rate * 100 / 1000) * 2)
        val tail = ByteArray((rate * 600 / 1000) * 2)
        return head + pcm + tail
    }

    // نص طويل: يقسمه ويدمج الأجزاء (بدون صمت إضافي)
    fun synthRaw(text: String, voice: String, style: String, cancel: () -> Boolean, progress: (String) -> Unit): TtsOut {
        val chunks = Speaker.split(text, 1200)
        if (chunks.isEmpty()) return TtsOut(null, 0, "لا يوجد نص للتحويل")
        val pcm = ByteArrayOutputStream()
        var rate = 24000
        for (i in chunks.indices) {
            if (cancel()) return TtsOut(null, 0, "تم الإيقاف")
            if (chunks.size > 1) progress("جاري تحويل الجزء " + (i + 1) + " من " + chunks.size)
            val r = synthChunk(chunks[i], voice, style, cancel, progress)
            val b = r.pcm
            if (b == null) return TtsOut(null, 0, r.error)
            rate = r.rate
            if (i > 0) pcm.write(ByteArray((rate * 120 / 1000) * 2))
            pcm.write(b)
        }
        return TtsOut(pcm.toByteArray(), rate, null)
    }

    fun synthAll(text: String, voice: String, style: String, cancel: () -> Boolean, progress: (String) -> Unit): TtsOut {
        val r = synthRaw(text, voice, style, cancel, progress)
        val b = r.pcm ?: return r
        return TtsOut(pad(b, r.rate), r.rate, null)
    }

    // ---------- أصوات متعددة ----------
    class Seg(val voice: Int, val text: String)

    private fun digitsToInt(s: String): Int {
        val sb = StringBuilder()
        for (c in s) {
            if (c in '٠'..'٩') sb.append(c - '٠') else sb.append(c)
        }
        return sb.toString().toIntOrNull() ?: 1
    }

    fun segments(text: String, n: Int, mode: Int): List<Seg> {
        val out = ArrayList<Seg>()
        if (n <= 1) {
            out.add(Seg(0, text))
            return out
        }
        if (mode == 1) {
            var k = 0
            for (line in text.split("\n")) {
                val t = line.trim()
                if (t.isNotEmpty()) {
                    out.add(Seg(k % n, t))
                    k++
                }
            }
        } else if (mode == 2) {
            var k = 0
            for (para in text.split(Regex("\n\\s*\n"))) {
                val t = para.trim()
                if (t.isNotEmpty()) {
                    out.add(Seg(k % n, t))
                    k++
                }
            }
        } else {
            var cur = 0
            val re = Regex("^[\\[(]?([0-9٠-٩]{1,2})[\\])]?\\s*[:：-]\\s*(.*)$")
            for (line in text.split("\n")) {
                val t = line.trim()
                if (t.isEmpty()) continue
                val m = re.find(t)
                if (m != null) {
                    cur = (digitsToInt(m.groupValues[1]) - 1).coerceIn(0, n - 1)
                    val rest = m.groupValues[2].trim()
                    if (rest.isNotEmpty()) out.add(Seg(cur, rest))
                } else {
                    out.add(Seg(cur, t))
                }
            }
        }
        if (out.isEmpty()) out.add(Seg(0, text))
        return out
    }

    // الأصوات المستخدمة: الصوت الأول من الإعدادات ثم الإضافية، وتكمل تلقائيًا لو ناقصة
    fun activeVoices(): List<String> {
        val n = Store.nVoices()
        val list = ArrayList<String>()
        list.add(Store.ttsVoice())
        for (v in Store.extraVoices()) if (!list.contains(v) && list.size < n) list.add(v)
        for (x in Prompts.VOICES) {
            if (list.size >= n) break
            if (!list.contains(x.first)) list.add(x.first)
        }
        return list
    }

    fun synthMulti(text: String, voices: List<String>, mode: Int, style: String, cancel: () -> Boolean, progress: (String) -> Unit): TtsOut {
        val n = voices.size
        val segs = segments(text, n, mode)
        val all = ByteArrayOutputStream()
        var rate = 24000
        for (i in segs.indices) {
            if (cancel()) return TtsOut(null, 0, "تم الإيقاف")
            if (segs.size > 1) progress("جاري تحويل المقطع " + (i + 1) + " من " + segs.size)
            val s = segs[i]
            val r = synthRaw(s.text, voices[s.voice.coerceIn(0, n - 1)], style, cancel, progress)
            val b = r.pcm ?: return TtsOut(null, 0, r.error)
            rate = r.rate
            if (i > 0) all.write(ByteArray((rate * 250 / 1000) * 2))
            all.write(b)
        }
        return TtsOut(pad(all.toByteArray(), rate), rate, null)
    }

    // قراءة ملف WAV بسيط (رأس 44 بايت) وإرجاع الصوت الخام
    fun readWavPcm(path: String): ByteArray? {
        try {
            val b = File(path).readBytes()
            if (b.size <= 44) return null
            return b.copyOfRange(44, b.size)
        } catch (e: Exception) {
            return null
        }
    }

    fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt(36 + pcm.size)
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)
        h.putShort(1)
        h.putShort(1)
        h.putInt(rate)
        h.putInt(rate * 2)
        h.putShort(2)
        h.putShort(16)
        h.put("data".toByteArray())
        h.putInt(pcm.size)
        return h.array() + pcm
    }
}
