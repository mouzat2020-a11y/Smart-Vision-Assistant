package com.mostafa.smartvision

// نغمة إخبارية قصيرة بتشتغل لما تنتقل لتبويب الأخبار: ثلاث ضربات سريعة ثم نغمة صاعدة ممتدة بصوت نحاسي.
// بتتولد بالكود بدون أي ملف صوت، وبتتبع نفس خيار "نغمة فتح التطبيق" في الإعدادات.
object NewsJingle {
    fun play() {
        if (!Store.chimeOn()) return
        Thread { render() }.start()
    }

    // يضيف نغمة بصوت نحاسي (أصل وتوافقياته) مع بداية سريعة وخفوت تدريجي
    private fun addNote(mix: DoubleArray, rate: Int, freq: Double, start: Double, dur: Double, vol: Double) {
        val s0 = (start * rate).toInt()
        val len = (dur * rate).toInt()
        val weights = doubleArrayOf(1.0, 0.5, 0.3, 0.15)
        for (i in 0 until len) {
            val idx = s0 + i
            if (idx >= mix.size) break
            val t = i.toDouble() / rate
            val att = if (t < 0.012) t / 0.012 else 1.0
            val rel = if (t > dur - 0.04) (dur - t) / 0.04 else 1.0
            val env = att * rel * Math.exp(-t * (1.6 / dur))
            var w = 0.0
            for (h in weights.indices) {
                w += weights[h] * Math.sin(2.0 * Math.PI * freq * (h + 1) * t)
            }
            mix[idx] += w * env * vol
        }
    }

    private fun render() {
        var track: android.media.AudioTrack? = null
        try {
            val rate = 22050
            val total = (rate * 1.8).toInt()
            val mix = DoubleArray(total)
            // ثلاث ضربات سريعة
            addNote(mix, rate, 392.00, 0.00, 0.13, 0.22)
            addNote(mix, rate, 392.00, 0.16, 0.13, 0.22)
            addNote(mix, rate, 392.00, 0.32, 0.13, 0.22)
            // نغمة ختامية صاعدة ممتدة (دو مي صول دو)
            addNote(mix, rate, 523.25, 0.50, 1.20, 0.20)
            addNote(mix, rate, 659.25, 0.50, 1.20, 0.15)
            addNote(mix, rate, 783.99, 0.50, 1.20, 0.13)
            addNote(mix, rate, 1046.50, 0.50, 1.20, 0.08)
            val pcm = ShortArray(total)
            for (i in 0 until total) {
                var v = mix[i]
                if (v > 1.0) v = 1.0
                if (v < -1.0) v = -1.0
                pcm[i] = (v * 30000.0).toInt().toShort()
            }
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val fmt = android.media.AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val tr = android.media.AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
            track = tr
            tr.write(pcm, 0, pcm.size)
            tr.play()
            Thread.sleep(1900)
        } catch (e: Exception) {
            // النغمة اختيارية: أي فشل فيها لا يؤثر على التطبيق
        } finally {
            try { track?.release() } catch (e: Exception) { }
        }
    }
}
