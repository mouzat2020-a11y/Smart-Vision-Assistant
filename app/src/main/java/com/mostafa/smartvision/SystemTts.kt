package com.mostafa.smartvision

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// توليد ملف صوتي من محرك صوت النظام (Android TTS) وتحويله إلى PCM 16-bit أحادي
// بنفس شكل ناتج Gemini (بعد Seams.clean على كل جزء)، حتى يمر على Fx.mix والحفظ والمشاركة وإعادة المزج.
// تُستدعى من خيط خلفي فقط (تنتظر انتهاء التوليد).
object SystemTts {

    private class Run {
        @Volatile var latch = CountDownLatch(1)
        @Volatile var failed = false
        @Volatile var curId = ""
    }

    private class Wav(val pcm: ByteArray?, val rate: Int, val err: String?)

    fun synth(ctx: Context, text: String, cancel: () -> Boolean, progress: (String) -> Unit): TtsOut {
        val app = ctx.applicationContext
        val chunks = Speaker.split(text, maxOf(500, minOf(3000, TextToSpeech.getMaxSpeechInputLength() - 100)))
        if (chunks.isEmpty()) return TtsOut(null, 0, "لا يوجد نص للتحويل")

        val holder = arrayOfNulls<TextToSpeech>(1)
        val initLatch = CountDownLatch(1)
        val initStatus = AtomicInteger(-99)

        Handler(Looper.getMainLooper()).post {
            try {
                val engine = Store.engine()
                val l = TextToSpeech.OnInitListener { s ->
                    initStatus.set(s)
                    initLatch.countDown()
                }
                holder[0] = if (engine.isEmpty()) TextToSpeech(app, l) else TextToSpeech(app, l, engine)
            } catch (e: Throwable) {
                initStatus.set(TextToSpeech.ERROR)
                initLatch.countDown()
            }
        }

        try {
            var waited = 0L
            while (!initLatch.await(200, TimeUnit.MILLISECONDS)) {
                if (cancel()) return TtsOut(null, 0, "تم الإيقاف")
                waited += 200
                if (waited > 15000) return TtsOut(null, 0, "محرك صوت النظام لم يستجب")
            }
            val t = holder[0]
            if (t == null || initStatus.get() != TextToSpeech.SUCCESS) {
                return TtsOut(null, 0, "تعذر تشغيل محرك صوت النظام")
            }
            try {
                t.setLanguage(Locale("ar"))
                t.setSpeechRate(Store.rate())
            } catch (e: Throwable) {
            }

            val run = Run()
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == run.curId) run.latch.countDown()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == run.curId) {
                        run.failed = true
                        run.latch.countDown()
                    }
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == run.curId) {
                        run.failed = true
                        run.latch.countDown()
                    }
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == run.curId) {
                        run.failed = true
                        run.latch.countDown()
                    }
                }
            })

            val all = ByteArrayOutputStream()
            var rate = 0
            for (i in chunks.indices) {
                if (cancel()) return TtsOut(null, 0, "تم الإيقاف")
                if (chunks.size > 1) progress("جاري تحويل الجزء " + (i + 1) + " من " + chunks.size)
                val f = File(app.cacheDir, "sys_tts_part_" + i + ".wav")
                f.delete()
                run.failed = false
                run.curId = "svsys" + i
                run.latch = CountDownLatch(1)
                val r = t.synthesizeToFile(chunks[i], Bundle(), f, run.curId)
                if (r != TextToSpeech.SUCCESS) {
                    f.delete()
                    return TtsOut(null, 0, "محرك النظام رفض توليد الملف الصوتي")
                }
                var w = 0L
                while (!run.latch.await(300, TimeUnit.MILLISECONDS)) {
                    if (cancel()) {
                        try { t.stop() } catch (e: Throwable) {}
                        f.delete()
                        return TtsOut(null, 0, "تم الإيقاف")
                    }
                    w += 300
                    if (w > 180000) {
                        try { t.stop() } catch (e: Throwable) {}
                        f.delete()
                        return TtsOut(null, 0, "انتهت مهلة توليد الصوت بمحرك النظام")
                    }
                }
                if (run.failed) {
                    f.delete()
                    return TtsOut(null, 0, "فشل محرك النظام في توليد الصوت")
                }
                val wav = parseWav(f)
                f.delete()
                val pcm = wav.pcm ?: return TtsOut(null, 0, wav.err ?: "تعذر قراءة الملف الصوتي")
                if (rate == 0) rate = wav.rate
                else if (rate != wav.rate) return TtsOut(null, 0, "اختلف معدل العينة بين أجزاء الصوت")
                if (i > 0) all.write(ByteArray((rate * 120 / 1000) * 2))
                all.write(Seams.clean(pcm, rate))
            }
            val body = all.toByteArray()
            if (rate <= 0 || body.isEmpty()) {
                return TtsOut(null, 0, "محرك النظام أنتج صوتًا فارغًا. جرّب محرك قراءة آخر من الإعدادات.")
            }
            return TtsOut(TtsEngine.pad(body, rate), rate, null)
        } catch (e: Throwable) {
            return TtsOut(null, 0, "تعذر التحويل بمحرك النظام")
        } finally {
            try { holder[0]?.stop() } catch (e: Throwable) {}
            try { holder[0]?.shutdown() } catch (e: Throwable) {}
        }
    }

    private fun le16(b: ByteArray, p: Int): Int {
        return (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    }

    private fun le32(b: ByteArray, p: Int): Long {
        return (le16(b, p).toLong()) or (le16(b, p + 2).toLong() shl 16)
    }

    private fun tag(b: ByteArray, p: Int): String {
        return String(b, p, 4, Charsets.ISO_8859_1)
    }

    // يقرأ WAV بقراءة الأقسام فعليًا (لا يفترض رأس 44 بايت) ويرجع PCM 16-bit أحادي
    private fun parseWav(f: File): Wav {
        try {
            if (!f.exists()) return Wav(null, 0, "المحرك لم يكتب أي ملف صوتي")
            val b = f.readBytes()
            if (b.size < 44 || tag(b, 0) != "RIFF" || tag(b, 8) != "WAVE") {
                return Wav(null, 0, "صيغة الملف الذي أنتجه المحرك غير مدعومة")
            }
            var pos = 12
            var fmtOk = false
            var fmtTag = 1
            var ch = 1
            var rate = 0
            var bits = 16
            var dStart = -1
            var dLen = 0
            while (pos + 8 <= b.size) {
                val id = tag(b, pos)
                val sz = le32(b, pos + 4)
                val body = pos + 8
                if (id == "fmt " && body + 16 <= b.size) {
                    fmtTag = le16(b, body)
                    ch = le16(b, body + 2)
                    rate = le32(b, body + 4).toInt()
                    bits = le16(b, body + 14)
                    fmtOk = true
                } else if (id == "data") {
                    val avail = b.size - body
                    dLen = if (sz <= 0L || sz > avail.toLong()) avail else sz.toInt()
                    dStart = body
                    break
                }
                if (sz > b.size.toLong()) break
                pos = (body + sz + (sz and 1L)).toInt()
            }
            if (!fmtOk || dStart < 0 || rate <= 0) return Wav(null, 0, "تعذر فهم رأس الملف الصوتي")
            if (fmtTag != 1 && fmtTag != 0xFFFE) return Wav(null, 0, "المحرك أنتج صوتًا غير PCM")
            if (bits != 16) return Wav(null, 0, "المحرك أنتج صوتًا بعمق " + bits + " بت وهو غير مدعوم")
            if (ch != 1 && ch != 2) return Wav(null, 0, "عدد قنوات الصوت غير مدعوم")
            val frame = 2 * ch
            dLen -= dLen % frame
            if (dLen <= 0) return Wav(null, 0, "الملف الصوتي فارغ")
            if (ch == 1) return Wav(b.copyOfRange(dStart, dStart + dLen), rate, null)
            val n = dLen / 4
            val out = ByteArray(n * 2)
            for (i in 0 until n) {
                val l = le16(b, dStart + i * 4).toShort().toInt()
                val r = le16(b, dStart + i * 4 + 2).toShort().toInt()
                val m = (l + r) / 2
                out[i * 2] = (m and 0xFF).toByte()
                out[i * 2 + 1] = ((m shr 8) and 0xFF).toByte()
            }
            return Wav(out, rate, null)
        } catch (e: Throwable) {
            return Wav(null, 0, "تعذر قراءة الملف الصوتي")
        }
    }
}
