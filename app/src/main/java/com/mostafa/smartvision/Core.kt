package com.mostafa.smartvision

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ================== تشفير المفاتيح (Android Keystore) ==================
object Crypto {
    private const val ALIAS = "sv_standalone_key_v1"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    // يرجع null لو التشفير فشل. لا يتم تخزين أي مفتاح كنص صريح أبدًا.
    fun enc(plain: String): String? {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = c.iv
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            return "e1:" + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            return null
        }
    }

    fun dec(stored: String): String {
        try {
            // p1: صيغة قديمة (نص صريح). نقرؤها فقط لترحيلها، ولا نكتبها أبدًا
            if (stored.startsWith("p1:")) return stored.substring(3)
            if (!stored.startsWith("e1:")) return ""
            val all = Base64.decode(stored.substring(3), Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val ct = all.copyOfRange(12, all.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            return String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            return ""
        }
    }
}

// ================== الإعدادات ==================
object Store {
    val PROVIDERS = listOf("gemini", "groq")
    private var holder: SharedPreferences? = null
    private var vaultFile: File? = null

    private fun prefs(): SharedPreferences {
        return holder!!
    }

    // المفاتيح المشفّرة تُحفظ في ملف داخل مجلد no_backup الخاص بالتطبيق،
    // وأندرويد يستثني هذا المجلد من النسخ الاحتياطي تلقائيًا (بدون أي ملفات إعداد إضافية)
    private fun vRead(): JSONObject {
        try {
            val f = vaultFile ?: return JSONObject()
            if (!f.exists()) return JSONObject()
            return JSONObject(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            return JSONObject()
        }
    }

    private fun vWrite(o: JSONObject): Boolean {
        try {
            val f = vaultFile ?: return false
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return false
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun vGet(name: String): String { return vRead().optString(name, "") }

    private fun vPut(name: String, value: String): Boolean {
        val o = vRead()
        o.put(name, value)
        if (!vWrite(o)) return false
        return vGet(name) == value
    }

    private fun vRemove(name: String) {
        val o = vRead()
        if (!o.has(name)) return
        o.remove(name)
        vWrite(o)
    }

    fun init(ctx: Context) {
        if (holder != null) return
        val app = ctx.applicationContext
        holder = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        vaultFile = File(app.noBackupFilesDir, "secure_keys.json")
        History.init(app)
        migrate()
        moveKeys()
    }

    // ترحيل آمن: ينقل كل مفتاح إلى الملف المستقل مشفّرًا (ويحوّل أي مفتاح نصي قديم p1:).
    // لا تُحذف النسخة القديمة إلا بعد التأكد أن النسخة الجديدة تُفتح وتطابق الأصل.
    private fun moveKeys() {
        val s = prefs()
        for (name in listOf("gemini", "groq", "openrouter")) {
            val old = s.getString("enc_" + name, "") ?: ""
            if (old.isEmpty()) continue
            val cur = vGet("enc_" + name)
            if (cur.isNotEmpty()) {
                if (Crypto.dec(cur).isNotEmpty()) s.edit().remove("enc_" + name).commit()
                continue
            }
            val plain = Crypto.dec(old)
            if (plain.isEmpty()) continue
            val fresh = Crypto.enc(plain) ?: continue
            if (Crypto.dec(fresh) != plain) continue
            if (!vPut("enc_" + name, fresh)) continue
            s.edit().remove("enc_" + name).commit()
        }
    }

    private fun migrate() {
        val p = prefs()
        if (p.getBoolean("mig2", false)) return
        val e = p.edit()
        val oldNames = listOf("gemini", "openrouter", "groq")
        var oldProvider = 0
        var failed = false
        try { oldProvider = p.getInt("provider", 0) } catch (ex: Exception) { oldProvider = 0 }
        for (i in 0..2) {
            var raw = ""
            try { raw = p.getString("key_$i", "") ?: "" } catch (ex: Exception) { raw = "" }
            if (raw.isEmpty() && i == 0) {
                try { raw = p.getString("key", "") ?: "" } catch (ex: Exception) { raw = "" }
            }
            raw = raw.trim()
            if (raw.length >= 10) {
                val enc = Crypto.enc(raw)
                if (enc != null) {
                    e.putString("enc_" + oldNames[i], enc)
                    e.remove("key_$i")
                } else {
                    failed = true
                }
            } else {
                e.remove("key_$i")
            }
            e.remove("model_$i")
        }
        if (!failed) {
            e.remove("key")
            e.remove("provider")
            if (oldProvider < 0 || oldProvider > 2) oldProvider = 0
            e.putString("prov", oldNames[oldProvider])
            e.putBoolean("mig2", true)
        }
        e.apply()
    }

    fun pname(p: String): String {
        return when (p) {
            "gemini" -> "Gemini"
            "groq" -> "Groq (مجاني)"
            else -> "OpenRouter"
        }
    }

    fun psite(p: String): String {
        return when (p) {
            "gemini" -> "aistudio.google.com/apikey"
            "groq" -> "console.groq.com/keys"
            else -> "openrouter.ai/keys"
        }
    }

    fun provider(): String {
        val v = prefs().getString("prov", "gemini") ?: "gemini"
        return if (PROVIDERS.contains(v)) v else "gemini"
    }

    fun setProvider(p: String) { prefs().edit().putString("prov", p).apply() }

    private fun stored(p: String): String {
        val a = vGet("enc_" + p)
        if (a.isNotEmpty()) return a
        return prefs().getString("enc_$p", "") ?: ""
    }

    fun key(p: String): String {
        val s = stored(p)
        if (s.isEmpty()) return ""
        return Crypto.dec(s).replace(Regex("\\s"), "")
    }

    // 0 لا يوجد مفتاح، 1 مفتاح سليم، 2 مفتاح محفوظ لكن تعذّر فتحه
    fun keyState(p: String): Int {
        val s = stored(p)
        if (s.isEmpty()) return 0
        return if (Crypto.dec(s).replace(Regex("\\s"), "").length >= 10) 1 else 2
    }

    fun missingKeyMsg(p: String): String {
        if (keyState(p) == 2) {
            return "المفتاح المحفوظ لـ" + pname(p) + " تعذّر فتحه، غالبًا بسبب استعادة نسخة احتياطية أو تغيير في الجهاز. احذف المفتاح من الإعدادات وأدخله من جديد."
        }
        return "لا يوجد مفتاح " + pname(p) + ". أضفه من الإعدادات."
    }

    fun hasKey(p: String): Boolean { return key(p).length >= 10 }

    fun hasAny(): Boolean {
        for (p in PROVIDERS) if (hasKey(p)) return true
        return false
    }

    // يرجع null لو المفتاح سليم، أو رسالة الخطأ
    fun saveKey(p: String, raw: String): String? {
        val k = raw.replace(Regex("\\s"), "")
        if (k.length < 10) return "المفتاح قصير أو فارغ"
        if (p == "groq" && !k.startsWith("gsk_")) return "مفتاح Groq لازم يبدأ بـ gsk_"
        val enc = Crypto.enc(k) ?: return "تعذّر تشفير المفتاح على هذا الجهاز، فلم يُحفظ. أعد تشغيل الهاتف ثم جرّب مرة أخرى."
        if (!vPut("enc_" + p, enc)) return "تعذّر حفظ المفتاح. جرّب مرة أخرى."
        if (Crypto.dec(enc) != k) {
            vRemove("enc_" + p)
            return "تعذّر التحقق من المفتاح بعد حفظه، فلم يُحفظ. أعد إدخاله."
        }
        prefs().edit().remove("enc_$p").apply()
        return null
    }

    fun removeKey(p: String) {
        vRemove("enc_" + p)
        prefs().edit().remove("enc_$p").apply()
    }

    fun historyOn(): Boolean { return prefs().getBoolean("hist_on", false) }
    fun setHistoryOn(v: Boolean) { prefs().edit().putBoolean("hist_on", v).apply() }

    // يمسح إعدادات التطبيق العادية (النماذج والأصوات والخيارات). المفاتيح لها مسح مستقل
    fun clearSettings(): Boolean { return prefs().edit().clear().commit() }

    fun model(p: String): String { return (prefs().getString("model_$p", "") ?: "").trim() }
    fun setModel(p: String, v: String) { prefs().edit().putString("model_$p", v.trim()).apply() }

    fun cross(): Boolean { return prefs().getBoolean("cross", true) }
    fun setCross(v: Boolean) { prefs().edit().putBoolean("cross", v).apply() }

    fun autoSpeak(): Boolean { return prefs().getBoolean("auto", true) }
    fun setAutoSpeak(v: Boolean) { prefs().edit().putBoolean("auto", v).apply() }

    fun rate(): Float { return prefs().getFloat("rate", 1.0f) }
    fun setRate(v: Float) { prefs().edit().putFloat("rate", v).apply() }

    fun engine(): String { return prefs().getString("engine", "") ?: "" }
    fun setEngine(v: String) { prefs().edit().putString("engine", v).apply() }

    fun describePrompt(): String {
        val p = (prefs().getString("prompt", "") ?: "").trim()
        return if (p.isEmpty()) Prompts.DEFAULT_DESCRIBE else p
    }
    fun setDescribePrompt(v: String) {
        val t = v.trim()
        prefs().edit().putString("prompt", if (t == Prompts.DEFAULT_DESCRIBE) "" else t).apply()
    }

    fun good(): String { return prefs().getString("good", "") ?: "" }
    fun setGood(v: String) { prefs().edit().putString("good", v).apply() }

    fun badMap(): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        val s = prefs().getString("bad", "") ?: ""
        for (part in s.split(",")) {
            val i = part.lastIndexOf('=')
            if (i > 0) {
                val t = part.substring(i + 1).toLongOrNull()
                if (t != null) out[part.substring(0, i)] = t
            }
        }
        return out
    }

    fun saveBad(m: Map<String, Long>) {
        val now = System.currentTimeMillis() / 1000
        val sb = StringBuilder()
        for ((k, v) in m) {
            if (v > now) {
                if (sb.isNotEmpty()) sb.append(",")
                sb.append(k).append("=").append(v)
            }
        }
        prefs().edit().putString("bad", sb.toString()).apply()
    }

    fun sp(): SharedPreferences { return prefs() }

    // ---- إعدادات متقدمة لتحويل النص لصوت ----
    fun ttsEngine(): String { return prefs().getString("t_engine", "gemini") ?: "gemini" }
    fun setTtsEngine(v: String) { prefs().edit().putString("t_engine", v).apply() }
    fun ttsTranslateOn(): Boolean { return prefs().getBoolean("t_trans_on", false) }
    fun setTtsTranslateOn(v: Boolean) { prefs().edit().putBoolean("t_trans_on", v).apply() }
    fun ttsTrModel(): String { return prefs().getString("t_trmodel", "") ?: "" }
    fun setTtsTrModel(v: String) { prefs().edit().putString("t_trmodel", v).apply() }
    fun nVoices(): Int { return prefs().getInt("t_nv", 1).coerceIn(1, 8) }
    fun setNVoices(v: Int) { prefs().edit().putInt("t_nv", v.coerceIn(1, 8)).apply() }
    fun mixMode(): Int { return prefs().getInt("t_mix", 1).coerceIn(0, 2) }
    fun setMixMode(v: Int) { prefs().edit().putInt("t_mix", v).apply() }
    fun extraVoices(): List<String> {
        val s = prefs().getString("t_xv", "") ?: ""
        val out = ArrayList<String>()
        for (x in s.split(",")) if (x.isNotBlank()) out.add(x.trim())
        return out
    }
    fun setExtraVoices(l: List<String>) { prefs().edit().putString("t_xv", l.joinToString(",")).apply() }

    // ---- موسيقى ومؤثرات ----
    fun fxOn(): Boolean { return prefs().getBoolean("fx_on", false) }
    fun setFxOn(v: Boolean) { prefs().edit().putBoolean("fx_on", v).apply() }
    fun fxMusic(): String { return prefs().getString("fx_music", "") ?: "" }
    fun setFxMusic(v: String) { prefs().edit().putString("fx_music", v).apply() }
    fun fxAmb1(): String { return prefs().getString("fx_amb1", "") ?: "" }
    fun setFxAmb1(v: String) { prefs().edit().putString("fx_amb1", v).apply() }
    fun fxAmb2(): String { return prefs().getString("fx_amb2", "") ?: "" }
    fun setFxAmb2(v: String) { prefs().edit().putString("fx_amb2", v).apply() }
    fun fxVol(i: Int): Int { return prefs().getInt("fx_vol" + i, if (i == 0) 25 else 35).coerceIn(0, 100) }
    fun setFxVol(i: Int, v: Int) { prefs().edit().putInt("fx_vol" + i, v).apply() }
    fun fxIntro(): Int { return prefs().getInt("fx_intro", 4).coerceIn(0, 20) }
    fun setFxIntro(v: Int) { prefs().edit().putInt("fx_intro", v).apply() }
    fun fxOutro(): Int { return prefs().getInt("fx_outro", 4).coerceIn(0, 20) }
    fun setFxOutro(v: Int) { prefs().edit().putInt("fx_outro", v).apply() }

    // ---- إعدادات تحويل النص لصوت ----
    fun ttsVoice(): String { return prefs().getString("t_voice", "Kore") ?: "Kore" }
    fun setTtsVoice(v: String) { prefs().edit().putString("t_voice", v).apply() }
    fun ttsEmotion(): Int { return prefs().getInt("t_emo", 0).coerceIn(0, Prompts.EMO.size - 1) }
    fun setTtsEmotion(v: Int) { prefs().edit().putInt("t_emo", v).apply() }
    fun ttsAccent(): Int { return prefs().getInt("t_acc", 0).coerceIn(0, Prompts.ACCENTS.size - 1) }
    fun setTtsAccent(v: Int) { prefs().edit().putInt("t_acc", v).apply() }
    fun ttsPace(): Int { return prefs().getInt("t_pace", 2).coerceIn(0, Prompts.PACE.size - 1) }
    fun setTtsPace(v: Int) { prefs().edit().putInt("t_pace", v).apply() }
    fun ttsLang(): Int { return prefs().getInt("t_lang", 0).coerceIn(0, Prompts.LANGS.size - 1) }
    fun setTtsLang(v: Int) { prefs().edit().putInt("t_lang", v).apply() }
    fun ttsModel(): String { return prefs().getString("t_model", Prompts.TTS_MODELS[0].first) ?: Prompts.TTS_MODELS[0].first }
    fun setTtsModel(v: String) { prefs().edit().putString("t_model", v).apply() }
}

// ================== تنظيف الرد من الرموز الزخرفية ==================
object PlainText {
    private val emojiRegex = Regex("[\\x{10000}-\\x{10FFFF}]")
    private val symbolRegex = Regex("[\\u2600-\\u27BF\\u2190-\\u23FF\\u25A0-\\u25FF\\u2B50-\\u2B55\\uFE0E\\uFE0F\\u200D]")
    private val ruleLine = Regex("^\\s*[-*_=]+\\s*$")
    private val headRegex = Regex("^\\s*#+\\s*")
    private val quoteRegex = Regex("^\\s*>+\\s*")
    private val bulletRegex = Regex("^(\\s*)[*\\-+]\\s+")
    private val dotRegex = Regex("^(\\s*)[\\u2022\\u2023]\\s*")
    private val manyBlank = Regex("\\n{3,}")

    fun clean(input: String): String {
        if (input.isEmpty()) return input
        val s = input.replace(emojiRegex, "").replace(symbolRegex, "")
        val out = ArrayList<String>()
        for (line in s.split("\n")) {
            var l = line
            if (ruleLine.matches(l) && l.trim().length >= 3) {
                l = ""
            } else {
                l = l.replace(headRegex, "")
                l = l.replace(quoteRegex, "")
                l = l.replace(bulletRegex, "\$1")
                l = l.replace(dotRegex, "\$1")
                l = l.replace("**", "")
                l = l.replace("__", "")
                l = l.replace("`", "")
                l = l.trimEnd()
            }
            out.add(l)
        }
        return out.joinToString("\n").replace(manyBlank, "\n\n").trim()
    }

    const val RULE = "Formatting rules for your reply, follow them silently and never mention them: " +
        "write in clear, simple, natural language suitable for people who use screen readers. " +
        "Do not use any decorative symbols or formatting marks, such as asterisks, hash signs, squares, bullets, arrows, " +
        "emoji, emoticons, or any symbol used to decorate titles, paragraphs or sentences. " +
        "Do not use Markdown. Do not put symbols at the start of titles, paragraphs or sentences. " +
        "Write plain text titles, organized paragraphs, and use only normal punctuation such as the comma, the period and the question mark when needed. " +
        "Do not remove any important information and do not shorten the content unless the user asked for that. " +
        "Keep the same language as the request. If the task asks for an exact output format, exact text, or sound tags in angle brackets, keep that exactly."
}


// ================== سجل النتائج المحفوظ (اختياري، محلي فقط) ==================
class HistItem(val id: Long, val title: String, val text: String)

object History {
    private var file: File? = null
    private const val MAX = 200

    fun init(ctx: Context) {
        file = File(ctx.applicationContext.noBackupFilesDir, "history.json")
    }

    private fun readAll(): JSONArray {
        try {
            val f = file ?: return JSONArray()
            if (!f.exists()) return JSONArray()
            return JSONArray(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            return JSONArray()
        }
    }

    private fun writeAll(a: JSONArray): Boolean {
        try {
            val f = file ?: return false
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(a.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return false
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // الأحدث أولًا
    fun list(): List<HistItem> {
        val a = readAll()
        val out = ArrayList<HistItem>()
        for (i in a.length() - 1 downTo 0) {
            val o = a.optJSONObject(i) ?: continue
            out.add(HistItem(o.optLong("id"), o.optString("t"), o.optString("x")))
        }
        return out
    }

    fun count(): Int { return readAll().length() }

    // لا يحفظ شيئًا إلا لو المستخدم فعّل حفظ السجل
    fun add(title: String, text: String) {
        if (!Store.historyOn()) return
        if (text.isBlank()) return
        val a = readAll()
        a.put(JSONObject().put("id", System.currentTimeMillis()).put("t", title).put("x", text))
        if (a.length() > MAX) {
            val n = JSONArray()
            for (i in a.length() - MAX until a.length()) n.put(a.get(i))
            writeAll(n)
        } else {
            writeAll(a)
        }
    }

    fun remove(id: Long): Boolean {
        val a = readAll()
        val n = JSONArray()
        var found = false
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optLong("id") == id) found = true else n.put(o)
        }
        if (!found) return false
        return writeAll(n)
    }

    fun clear(): Boolean {
        val f = file ?: return true
        if (!f.exists()) return true
        return f.delete()
    }
}

// ================== مسح البيانات المحلية ==================
object Privacy {
    private fun listCache(ctx: Context, pick: (String) -> Boolean): List<File> {
        val out = ArrayList<File>()
        val fs = ctx.cacheDir.listFiles() ?: return out
        for (f in fs) if (f.isFile && pick(f.name)) out.add(f)
        return out
    }

    // صور مؤقتة أنشأها التطبيق داخل الـ cache فقط (لا تشمل صور المعرض)
    fun imageFiles(ctx: Context): List<File> {
        return listCache(ctx) { n -> n.startsWith("cam_") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") }
    }

    // ملفات صوت مؤقتة داخل الـ cache فقط (لا تشمل ما حفظته في التنزيلات)
    fun audioFiles(ctx: Context): List<File> {
        return listCache(ctx) { n -> n.startsWith("tts_") || n.startsWith("sample") || n.startsWith("fx_") || n.endsWith(".wav") }
    }

    fun bytes(files: List<File>): Long {
        var s = 0L
        for (f in files) s += f.length()
        return s
    }

    fun sizeText(b: Long): String {
        if (b < 1024L) return b.toString() + " بايت"
        if (b < 1048576L) return (b / 1024L).toString() + " كيلوبايت"
        return String.format(java.util.Locale.US, "%.1f", b / 1048576.0) + " ميجابايت"
    }

    // يرجع (عدد ما حُذف، عدد ما تعذّر حذفه)
    fun delete(files: List<File>): Pair<Int, Int> {
        var ok = 0
        var bad = 0
        for (f in files) {
            if (f.delete() || !f.exists()) ok++ else bad++
        }
        return Pair(ok, bad)
    }

    fun clearSession() {
        Session.resultTitle = ""
        Session.resultText = ""
        Session.history = null
        Session.repeatMode = ""
        Session.repeatSource = ""
        Session.repeatExtra = ""
        Session.repeatLabel = ""
        Session.lastAudioPath = ""
        Session.lastDryPath = ""
        Session.lastTranslation = ""
        Session.lastSavedDesc = ""
    }
}

// ================== الاتصال بالإنترنت ==================
class HttpResult(val code: Int, val body: String)

object Http {
    // أسرار الترويسات (مفتاح Gemini أو Authorization) لإخفائها من أي نص خطأ
    private fun secretsOf(headers: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        for ((k, v) in headers) {
            if (k.equals("authorization", true) || k.equals("x-goog-api-key", true)) {
                val s = v.removePrefix("Bearer ").trim()
                if (s.length >= 8) out.add(s)
            }
        }
        return out
    }

    private fun scrub(text: String, secrets: List<String>): String {
        var t = text
        for (s in secrets) if (t.contains(s)) t = t.replace(s, "***")
        return t
    }

    fun post(url: String, body: String, headers: Map<String, String>, readMs: Int): HttpResult {
        if (!url.startsWith("https://")) return HttpResult(-1, "تم رفض الاتصال لأنه غير مشفّر")
        val secrets = secretsOf(headers)
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = readMs
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = if (stream == null) "" else stream.bufferedReader().use { it.readText() }
            return HttpResult(code, if (code in 200..299) text else scrub(text, secrets))
        } catch (e: Exception) {
            return HttpResult(-1, scrub(e.message ?: e.javaClass.simpleName, secrets))
        }
    }
}

// ================== المحادثة والرد ==================
class Msg(val role: String, val text: String, val images: List<String> = emptyList())
class AiResult(val text: String?, val error: String?)
// kind: 0 نجاح، 1 جرّب النموذج التالي، 2 خطأ نهائي (مثل مفتاح خاطئ)
class Verdict(val kind: Int, val text: String)

object Ai {
    // سلسلة النماذج التلقائية في Gemini (نفس ترتيب main.lua)
    val GEMINI_CHAIN = listOf(
        "gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite",
        "gemini-3-flash-preview", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"
    )
    // قائمة الاختيار اليدوي
    val GEMINI_PICK = listOf(
        "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite", "gemini-3-flash-preview", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"
    )
    val OR_PRESETS = listOf(
        "google/gemini-2.5-flash", "google/gemini-2.5-flash-lite", "openai/gpt-4o-mini",
        "qwen/qwen2.5-vl-32b-instruct:free", "openrouter/auto"
    )
    val GROQ_VISION = listOf(
        "qwen/qwen3.8-27b", "qwen/qwen3.6-27b",
        "meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct"
    )
    val GROQ_TEXT = listOf(
        "llama-3.3-70b-versatile", "openai/gpt-oss-120b", "llama-3.1-8b-instant", "openai/gpt-oss-20b"
    )

    fun pickList(p: String): List<String> {
        return when (p) {
            "gemini" -> GEMINI_PICK
            "groq" -> GROQ_VISION + GROQ_TEXT
            else -> OR_PRESETS
        }
    }

    private fun withRule(h: List<Msg>): List<Msg> {
        if (h.isEmpty()) return h
        val last = h[h.size - 1]
        if (last.role == "model") return h
        val out = ArrayList<Msg>()
        for (i in 0 until h.size - 1) out.add(h[i])
        out.add(Msg(last.role, last.text + "\n\n" + PlainText.RULE, last.images))
        return out
    }

    fun ask(history: List<Msg>, cancel: () -> Boolean, progress: (String) -> Unit): AiResult {
        val h = withRule(history)
        val cur = Store.provider()
        val order = ArrayList<String>()
        if (Store.hasKey(cur)) order.add(cur)
        if (Store.cross() || order.isEmpty()) {
            for (p in Store.PROVIDERS) if (p != cur && Store.hasKey(p)) order.add(p)
        }
        if (order.isEmpty()) {
            for (p in Store.PROVIDERS) if (Store.keyState(p) == 2) return AiResult(null, Store.missingKeyMsg(p))
            return AiResult(null, "لا يوجد مفتاح لأي مزود. افتح الإعدادات وأضف مفتاحًا لمزود واحد على الأقل.")
        }
        var firstErr: String? = null
        for (i in order.indices) {
            if (cancel()) return AiResult(null, "تم الإيقاف")
            val p = order[i]
            if (i > 0) progress("المزود السابق لم ينجح، أجرّب " + Store.pname(p))
            val r = if (p == "gemini") callGemini(h, cancel, progress) else callOpenAi(p, h, cancel, progress)
            val t = r.text
            if (t != null) return AiResult(PlainText.clean(t), null)
            if (firstErr == null) firstErr = r.error
        }
        return AiResult(null, firstErr ?: "فشل الاتصال")
    }

    // اختبار مزود واحد بعينه (بدون التبديل لمزود آخر)
    fun askProvider(p: String, history: List<Msg>, cancel: () -> Boolean, progress: (String) -> Unit): AiResult {
        val h = withRule(history)
        val r = if (p == "gemini") callGemini(h, cancel, progress) else callOpenAi(p, h, cancel, progress)
        val t = r.text
        if (t != null) return AiResult(PlainText.clean(t), null)
        return r
    }

    // يجرّب النماذج بالترتيب. عند الفشل ينتقل للتالي فورًا، ولو تأخر النموذج الحالي
    // أكثر من hedgeMs يبدأ التالي بالتوازي (بحد أقصى محاولتين معًا) ويأخذ أول رد ناجح.
    private fun hedged(
        models: List<String>,
        hedgeMs: Long,
        cancel: () -> Boolean,
        progress: (String) -> Unit,
        send: (String) -> HttpResult,
        judge: (String, HttpResult) -> Verdict
    ): AiResult {
        if (models.isEmpty()) return AiResult(null, "لا توجد نماذج متاحة")
        val q = LinkedBlockingQueue<Pair<String, HttpResult>>()
        var started = 0
        var inFlight = 0
        var lastStart = System.currentTimeMillis()
        var lastErr = "فشل الاتصال"

        fun startNext(parallel: Boolean) {
            val m = models[started]
            started++
            inFlight++
            lastStart = System.currentTimeMillis()
            if (started == 1) progress("أجرّب النموذج " + m)
            else if (parallel) progress("النموذج بطيء، أجرّب " + m + " بالتوازي")
            else progress("النموذج السابق لم ينجح، أجرّب " + m)
            thread { q.put(Pair(m, send(m))) }
        }

        startNext(false)
        while (inFlight > 0) {
            if (cancel()) return AiResult(null, "تم الإيقاف")
            val item = q.poll(300, TimeUnit.MILLISECONDS)
            if (item == null) {
                if (inFlight < 2 && started < models.size && System.currentTimeMillis() - lastStart >= hedgeMs) startNext(true)
                continue
            }
            inFlight--
            val v = judge(item.first, item.second)
            if (v.kind == 0) return AiResult(v.text, null)
            if (v.kind == 2) return AiResult(null, v.text)
            lastErr = v.text
            if (inFlight < 2 && started < models.size) startNext(false)
        }
        return AiResult(null, lastErr)
    }

    // مثل askProvider لكن مع تحديد نموذج Gemini بعينه كأول محاولة (فارغ = الإعداد العادي)
    fun askGeminiModel(model: String, history: List<Msg>, cancel: () -> Boolean, progress: (String) -> Unit): AiResult {
        val h = withRule(history)
        val r = callGemini(h, cancel, progress, if (model.isEmpty()) null else model)
        val t = r.text
        if (t != null) return AiResult(PlainText.clean(t), null)
        return r
    }

    // ---------- Gemini ----------
    private fun geminiContents(h: List<Msg>): JSONArray {
        val arr = JSONArray()
        for (m in h) {
            val parts = JSONArray()
            if (m.text.isNotEmpty()) parts.put(JSONObject().put("text", m.text))
            for (b in m.images) {
                parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b)))
            }
            arr.put(JSONObject().put("role", m.role).put("parts", parts))
        }
        return arr
    }

    private fun errMessage(body: String): String {
        try {
            val d = JSONObject(body)
            val e = d.opt("error")
            if (e is JSONObject) return e.optString("message", body)
            if (e is String) return e
            val m = d.optString("message", "")
            if (m.isNotEmpty()) return m
        } catch (ex: Exception) {
        }
        return body
    }

    private fun geminiFriendly(code: Int, body: String): String {
        val msg = errMessage(body)
        if (code == 400 && msg.contains("API key", true)) return "مفتاح Gemini غير صحيح. راجع المفتاح من الإعدادات."
        if (code == 403) return "المفتاح مرفوض أو لا توجد صلاحية. (" + msg.take(150) + ")"
        if (code == 429) return "الحصة المجانية لهذا النموذج انتهت الآن. جرّب بعد قليل أو غيّر النموذج."
        if (code == -1) return "تعذر الاتصال بالإنترنت: " + msg.take(120)
        return "خطأ " + code + " من Gemini: " + msg.take(200)
    }

    private fun extractGemini(body: String): String {
        try {
            val d = JSONObject(body)
            val cands = d.optJSONArray("candidates") ?: return ""
            if (cands.length() == 0) return ""
            val content = cands.getJSONObject(0).optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.has("text") && !p.optBoolean("thought", false)) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(p.getString("text"))
                }
            }
            return sb.toString().trim()
        } catch (e: Exception) {
            return ""
        }
    }

    private fun callGemini(h: List<Msg>, cancel: () -> Boolean, progress: (String) -> Unit, override: String? = null): AiResult {
        val key = Store.key("gemini")
        if (key.isEmpty()) return AiResult(null, Store.missingKeyMsg("gemini"))
        val first = override ?: Store.model("gemini")
        val cand = ArrayList<String>()
        if (first.isNotEmpty()) cand.add(first)
        for (m in GEMINI_CHAIN) if (m != first) cand.add(m)
        val bad = Store.badMap()
        val now = System.currentTimeMillis() / 1000
        val fresh = ArrayList<String>()
        val stale = ArrayList<String>()
        for (m in cand) {
            val ex = bad[m]
            if (ex != null && ex > now) stale.add(m) else fresh.add(m)
        }
        val good = Store.good()
        val firstBad = (bad[first] ?: 0L) > now
        if (good.isNotEmpty() && (first.isEmpty() || firstBad)) {
            val k = fresh.indexOf(good)
            if (k > 0) {
                fresh.removeAt(k)
                fresh.add(0, good)
            }
        }
        val order = ArrayList<String>()
        order.addAll(fresh)
        order.addAll(stale)
        val body = JSONObject().put("contents", geminiContents(h)).toString()
        var hasImg = false
        for (m in h) if (m.images.isNotEmpty()) hasImg = true
        val hedgeMs = if (hasImg) 12000L else 8000L
        return hedged(order, hedgeMs, cancel, progress,
            { m ->
                Http.post(
                    "https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent",
                    body, mapOf("x-goog-api-key" to key), 90000
                )
            },
            { m, r ->
                if (r.code == 200) {
                    val t = extractGemini(r.body)
                    if (t.isNotEmpty()) {
                        bad.remove(m)
                        Store.setGood(m)
                        Store.saveBad(bad)
                        Verdict(0, t)
                    } else {
                        Verdict(1, "النموذج أرجع ردًا فارغًا (ربما حُجبت الصورة لأسباب الأمان).")
                    }
                } else if (r.code == 401 || (r.code == 400 && errMessage(r.body).contains("API key", true))) {
                    Verdict(2, geminiFriendly(r.code, r.body))
                } else {
                    bad[m] = now + (if (r.code == 404) 21600L else 600L)
                    Store.saveBad(bad)
                    Verdict(1, geminiFriendly(r.code, r.body))
                }
            })
    }

    // ---------- Groq / OpenRouter ----------
    private fun openAiMessages(h: List<Msg>): JSONArray {
        val arr = JSONArray()
        for (m in h) {
            val role = if (m.role == "model") "assistant" else "user"
            val o = JSONObject().put("role", role)
            if (role == "assistant" || m.images.isEmpty()) {
                o.put("content", m.text)
            } else {
                val c = JSONArray()
                if (m.text.isNotEmpty()) c.put(JSONObject().put("type", "text").put("text", m.text))
                for (b in m.images) {
                    c.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + b)))
                }
                o.put("content", c)
            }
            arr.put(o)
        }
        return arr
    }

    private fun chain(p: String, hasImg: Boolean): List<String> {
        val list = ArrayList<String>()
        val custom = Store.model(p)
        if (p == "groq") {
            if (custom.isNotEmpty()) {
                if (!hasImg || GROQ_VISION.contains(custom)) list.add(custom)
            }
            if (hasImg) {
                for (m in GROQ_VISION) if (m != custom) list.add(m)
            } else {
                for (m in GROQ_TEXT) if (m != custom) list.add(m)
                for (m in GROQ_VISION) if (m != custom) list.add(m)
            }
            return list
        }
        if (custom.isNotEmpty()) list.add(custom)
        for (m in OR_PRESETS) if (m != custom) list.add(m)
        return list
    }

    private fun extractOpenAi(body: String): String {
        try {
            val d = JSONObject(body)
            val ch = d.optJSONArray("choices") ?: return ""
            if (ch.length() == 0) return ""
            val msg = ch.getJSONObject(0).optJSONObject("message") ?: return ""
            val c = msg.opt("content")
            if (c is String) return c.trim()
            if (c is JSONArray) {
                val sb = StringBuilder()
                for (i in 0 until c.length()) {
                    val x = c.optJSONObject(i)
                    if (x != null && x.has("text")) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(x.getString("text"))
                    }
                }
                return sb.toString().trim()
            }
        } catch (e: Exception) {
        }
        return ""
    }

    private fun openAiFriendly(p: String, code: Int, body: String): String {
        val msg = errMessage(body)
        val nm = Store.pname(p)
        if (code == 401 || code == 403 || (code == 400 && msg.contains("api key", true))) {
            return "مفتاح " + nm + " مرفوض أو غير صحيح. راجع المفتاح من الإعدادات. (" + msg.take(120) + ")"
        }
        if (code == 402) return "رصيد حساب " + nm + " انتهى أو يحتاج شحنًا. (" + msg.take(120) + ")"
        if (code == 429) return "حصة " + nm + " تجاوزت الحد الآن. جرّب بعد قليل أو غيّر النموذج أو المزود."
        if (code == -1) return "تعذر الاتصال بالإنترنت: " + msg.take(120)
        return "خطأ " + code + " من " + nm + ": " + msg.take(200)
    }

    private fun callOpenAi(p: String, h: List<Msg>, cancel: () -> Boolean, progress: (String) -> Unit): AiResult {
        val key = Store.key(p)
        if (key.isEmpty()) return AiResult(null, Store.missingKeyMsg(p))
        var hasImg = false
        for (m in h) if (m.images.isNotEmpty()) hasImg = true
        val list = chain(p, hasImg)
        val msgs = openAiMessages(h)
        val url = if (p == "groq") "https://api.groq.com/openai/v1/chat/completions" else "https://openrouter.ai/api/v1/chat/completions"
        val headers = HashMap<String, String>()
        headers["Authorization"] = "Bearer " + key
        if (p == "openrouter") {
            headers["HTTP-Referer"] = "https://github.com/mouzat2020-a11y/Smart-Vision-Assistant"
            headers["X-Title"] = "Smart Vision Assistant"
        }
        val hedgeMs = if (hasImg) 12000L else 8000L
        return hedged(list, hedgeMs, cancel, progress,
            { m ->
                val body = JSONObject().put("model", m).put("messages", msgs).toString()
                Http.post(url, body, headers, if (hasImg) 75000 else 45000)
            },
            { _, r ->
                if (r.code == 200) {
                    val t = extractOpenAi(r.body)
                    if (t.isNotEmpty()) Verdict(0, t) else Verdict(1, "النموذج أرجع ردًا فارغًا.")
                } else if (r.code == 401 || r.code == 402 || r.code == 403 ||
                    (r.code == 400 && errMessage(r.body).contains("api key", true))) {
                    Verdict(2, openAiFriendly(p, r.code, r.body))
                } else {
                    Verdict(1, openAiFriendly(p, r.code, r.body))
                }
            })
    }
}
