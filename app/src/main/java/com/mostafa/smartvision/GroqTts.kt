package com.mostafa.smartvision

import org.json.JSONObject
import java.io.ByteArrayOutputStream

// تحويل النص إلى صوت عبر Groq (نماذج Orpheus). معزول عن باقي المحركات: لا يمس Gemini ولا صوت النظام.
// حد Groq: 200 حرف لكل طلب، فنقسّم النص إلى أجزاء قصيرة ونجمّع الصوت.
object GroqTts {
    const val MODEL_AR = "canopylabs/orpheus-arabic-saudi"
    const val MODEL_EN = "canopylabs/orpheus-v1-english"
    const val URL = "https://api.groq.com/openai/v1/audio/speech"
    const val MAX_CHARS = 190
    const val MAX_CHUNKS = 40

    // أصوات العربية السعودية، والأول هو الافتراضي
    val VOICES_AR = listOf(
        Pair("fahad", "فهد"),
        Pair("abdullah", "عبدالله"),
        Pair("sultan", "سلطان"),
        Pair("lulwa", "لولوة"),
        Pair("noura", "نورة")
    )
    const val VOICE_EN = "hannah"

    private fun isArabic(text: String): Boolean {
        var ar = 0
        var la = 0
        for (ch in text) {
            if (ch in '\u0600'..'\u06FF') ar++
            else if (ch in 'a'..'z' || ch in 'A'..'Z') la++
        }
        return ar >= la
    }

    // يقسم النص إلى أجزاء لا تزيد عن الحد، ويفضّل القطع عند علامات الترقيم
    fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        val noTags = text.replace(Regex("<[^>]*>"), " ")
        for (rawLine in noTags.split('\n')) {
            var rest = rawLine.replace(Regex("\\s+"), " ").trim()
            while (rest.isNotEmpty()) {
                if (rest.length <= MAX_CHARS) {
                    out.add(rest)
                    break
                }
                val window = rest.substring(0, MAX_CHARS)
                var cut = -1
                var i = window.length - 1
                while (i >= MAX_CHARS / 2) {
                    val ch = window[i]
                    if (ch == '.' || ch == '!' || ch == '?' || ch == '\u061F' || ch == '\u060C' || ch == ',' || ch == '\u061B' || ch == ':') {
                        cut = i + 1
                        break
                    }
                    i--
                }
                if (cut < 0) {
                    val sp = window.lastIndexOf(' ')
                    cut = if (sp > MAX_CHARS / 4) sp else MAX_CHARS
                }
                val piece = rest.substring(0, cut).trim()
                if (piece.isNotEmpty()) out.add(piece)
                rest = rest.substring(cut).trim()
            }
        }
        return out
    }

    private class Wav(val pcm: ByteArray, val rate: Int)

    private fun le16(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, p: Int): Int = le16(b, p) or (le16(b, p + 2) shl 16)

    // يقرأ ملف WAV بأقسامه الفعلية (لا يفترض حجم ترويسة ثابت) ويرجع PCM بدقة 16 بت أحادي
    private fun parseWav(b: ByteArray): Wav? {
        if (b.size < 44) return null
        if (String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
        var pos = 12
        var rate = 0
        var ch = 0
        var bits = 0
        var tag = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.ISO_8859_1)
            val sz = le32(b, pos + 4)
            val start = pos + 8
            if (id == "fmt " && start + 16 <= b.size) {
                tag = le16(b, start)
                ch = le16(b, start + 2)
                rate = le32(b, start + 4)
                bits = le16(b, start + 14)
            } else if (id == "data") {
                var len = sz
                if (len <= 0 || start + len > b.size) len = b.size - start
                if (len <= 0) return null
                if (rate < 8000 || rate > 96000) return null
                if (bits != 16 || (tag != 1 && tag != 0xFFFE)) return null
                if (ch != 1 && ch != 2) return null
                var pcm = b.copyOfRange(start, start + len)
                if (ch == 2) {
                    val frames = pcm.size / 4
                    val mono = ByteArray(frames * 2)
                    for (f in 0 until frames) {
                        val l = (pcm[f * 4].toInt() and 0xFF) or (pcm[f * 4 + 1].toInt() shl 8)
                        val r = (pcm[f * 4 + 2].toInt() and 0xFF) or (pcm[f * 4 + 3].toInt() shl 8)
                        val m = (l + r) / 2
                        mono[f * 2] = (m and 0xFF).toByte()
                        mono[f * 2 + 1] = ((m shr 8) and 0xFF).toByte()
                    }
                    pcm = mono
                }
                if (pcm.size % 2 != 0) pcm = pcm.copyOf(pcm.size - 1)
                return Wav(pcm, rate)
            }
            if (sz < 0) return null
            pos = start + sz + (sz and 1)
        }
        return null
    }

    private fun explain(r: BinResult): String {
        if (r.code == -1) return if (r.text == "offline") "مفيش اتصال بالإنترنت" else "تعذر الاتصال بـ Groq"
        if (r.code == -3) return "انتهت مهلة الاتصال بـ Groq، جرّب مرة أخرى"
        if (r.code == 401) return "مفتاح Groq غير صحيح، راجعه من الإعدادات"
        if (r.code == 429) return "الطلبات كتير أو حصة Groq خلصت، جرّب بعد شوية"
        if (r.text.contains("terms", true)) return "لازم تقبل شروط نموذج Orpheus مرة واحدة من موقع Groq (console.groq.com) ثم جرّب تاني"
        return "رفض Groq الطلب (كود " + r.code + "): " + r.text.take(150)
    }

    fun synth(text: String, voice: String, cancel: () -> Boolean, progress: (String) -> Unit): TtsOut {
        val key = Store.key("groq")
        if (key.length < 10) return TtsOut(null, 24000, "مفتاح Groq مش موجود، أضفه من الإعدادات")
        val arabic = isArabic(text)
        val model = if (arabic) MODEL_AR else MODEL_EN
        var v = VOICE_EN
        if (arabic) {
            v = "fahad"
            for (x in VOICES_AR) if (x.first == voice) v = voice
        }
        val parts = chunks(text)
        if (parts.isEmpty()) return TtsOut(null, 24000, "النص فاضي")
        if (parts.size > MAX_CHUNKS) {
            return TtsOut(null, 24000, "النص طويل على Groq (الحد 200 حرف للطلب، ويقبل نحو " + MAX_CHUNKS + " جزء). اختصره أو استخدم Gemini أو صوت النظام")
        }
        val headers = mapOf("Authorization" to "Bearer " + key)
        val all = ByteArrayOutputStream()
        var rate = 0
        for (i in parts.indices) {
            if (cancel()) return TtsOut(null, 24000, "تم الإيقاف")
            progress("جاري تحويل الجزء " + (i + 1) + " من " + parts.size + " (Groq)")
            val body = JSONObject().put("model", model).put("input", parts[i]).put("voice", v).put("response_format", "wav").toString()
            var r = Http.postBinary(URL, body, headers, 60000)
            if (r.code == 429) {
                // محاولة واحدة إضافية بعد انتظار قصير
                var waited = 0
                while (waited < 4000 && !cancel()) {
                    Thread.sleep(250)
                    waited += 250
                }
                if (cancel()) return TtsOut(null, 24000, "تم الإيقاف")
                r = Http.postBinary(URL, body, headers, 60000)
            }
            if (r.code == -2) return TtsOut(null, 24000, "تم الإيقاف")
            if (r.code !in 200..299) return TtsOut(null, 24000, explain(r) + " (الجزء " + (i + 1) + " من " + parts.size + ")")
            val w = parseWav(r.bytes ?: ByteArray(0))
            if (w == null) return TtsOut(null, 24000, "رد Groq مش ملف صوت سليم")
            if (rate == 0) rate = w.rate
            else if (rate != w.rate) return TtsOut(null, 24000, "اختلفت دقة الصوت بين أجزاء Groq")
            all.write(w.pcm)
            if (i < parts.size - 1) all.write(ByteArray(rate / 5 * 2))
        }
        return TtsOut(all.toByteArray(), rate, null)
    }
}
