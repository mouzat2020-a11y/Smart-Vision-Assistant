package com.mostafa.smartvision

import org.json.JSONArray
import org.json.JSONObject

// تفاصيل وشرح الأخبار بالذكاء الاصطناعي (Gemini مع بحث جوجل)، بمفتاح Gemini بتاعك.
// النتيجة ملخص من الذكاء الاصطناعي مش نص الجريدة نفسه.
object NewsAi {
    @Volatile var lastError: String = ""

    private val MODELS = listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash-lite")

    // mode 0 = تفاصيل الخبر، 1 = شرح الخبر وسياقه. يرجع (النص أو null، سبب الفشل)
    fun ask(title: String, link: String, mode: Int, cancel: () -> Boolean): Pair<String?, String> {
        val key = Store.key("gemini")
        if (key.isEmpty()) {
            lastError = "مفتاح Gemini مش موجود"
            return Pair(null, "مفتاح Gemini مش موجود، أضفه من إعدادات التطبيق")
        }
        var withUrl = mode == 0 && link.startsWith("http") && !link.contains("google.com")
        val errs = ArrayList<String>()
        var idx = 0
        val maxTries = 3
        var tries = 0
        while (idx < MODELS.size && tries < maxTries + 1) {
            if (cancel()) return Pair(null, "تم الإيقاف")
            tries++
            val m = MODELS[idx]
            val body = buildBody(title, link, mode, withUrl)
            val r = Http.post(
                "https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent",
                body, mapOf("x-goog-api-key" to key), 45000
            )
            if (r.code == -2) return Pair(null, "تم الإيقاف")
            var text = ""
            if (r.code == 200) text = extract(r.body)
            if (text.length > 80) {
                lastError = ""
                return Pair(text, "")
            }
            val why = if (r.code == 200) "رد فاضي" else explain(r)
            errs.add(m + ": " + why)
            val fatal = r.code == 403 || r.code == 401 || (r.code == 400 && r.body.contains("api key", true))
            if (fatal) break
            // لو النموذج رفض أداة فتح الرابط، نعيد على نفس النموذج ببحث جوجل بس
            if (withUrl && r.code == 400) {
                withUrl = false
                continue
            }
            idx++
        }
        lastError = errs.joinToString("\n")
        return Pair(null, "تعذر جلب التفاصيل بالذكاء الاصطناعي (" + (errs.lastOrNull() ?: "") + ")")
    }

    private fun buildBody(title: String, link: String, mode: Int, withUrl: Boolean): String {
        val prompt = if (mode == 1) {
            "اشرح الخبر التالي بالعربية الفصحى المبسطة لشخص يستمع إليه بدون رؤية. اذكر ما حدث، ثم سياقه وسببه وأهميته، في فقرتين قصيرتين تصلحان للقراءة بصوت عالٍ، بالاعتماد على نتائج البحث الحالية. لا تخمّن، وإن لم تجد معلومات مؤكدة فقل ذلك صراحة. في النهاية اذكر اسم المصدر.\nالخبر: " + title
        } else if (withUrl) {
            "افتح رابط الخبر التالي واقرأ نصه، ثم اكتب تفاصيل الخبر بالعربية الفصحى في فقرة واحدة من 120 إلى 200 كلمة، تصلح للقراءة بصوت عالٍ. اذكر الوقائع والأسماء والأرقام الموجودة في الخبر فقط ولا تخمّن. لو ما قدرت تفتح الرابط فاستعن بنتائج البحث الحالية. في النهاية اذكر اسم المصدر.\nالعنوان: " + title + "\nالرابط: " + link
        } else {
            "اكتب تفاصيل الخبر التالي بالعربية الفصحى في فقرة واحدة من 120 إلى 200 كلمة، تصلح للقراءة بصوت عالٍ، بالاعتماد فقط على نتائج البحث الحالية. اذكر الوقائع والأسماء والأرقام المؤكدة فقط ولا تخمّن. في النهاية اذكر اسم المصدر. إن لم تجد تفاصيل مؤكدة فقل ذلك صراحة.\nالخبر: " + title
        }
        val tools = JSONArray()
        if (withUrl) tools.put(JSONObject().put("url_context", JSONObject()))
        tools.put(JSONObject().put("google_search", JSONObject()))
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("tools", tools)
            .put("generationConfig", JSONObject().put("temperature", 0.2))
            .toString()
    }

    private fun extract(body: String): String {
        return try {
            val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val t = parts.getJSONObject(i).optString("text", "")
                if (t.isNotEmpty()) sb.append(t).append(" ")
            }
            sb.toString().replace(Regex("[*#`]"), "").replace(Regex("\\s+"), " ").trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun explain(r: HttpResult): String {
        if (r.code == -1) return if (r.body == "offline") "مفيش اتصال بالإنترنت" else "تعذر الاتصال"
        if (r.code == -3) return "انتهت المهلة"
        if (r.code == 429) return "حصة Gemini خلصت أو الطلبات كتير"
        if (r.code == 403 || r.code == 401) return "مفتاح Gemini غير صالح أو غير مسموح"
        if (r.code == 404) return "النموذج غير متاح"
        return "كود " + r.code + " " + r.body.take(100)
    }
}
