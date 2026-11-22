package com.mostafa.smartvision

// مُولَّد من main.lua: أصوات المشاعر (علامات تنفذها نماذج Gemini 3.8 كصوت)
object SoundTags {
    val CATS: List<Pair<String, List<Pair<String, String>>>> = listOf(
        Pair("خوف وفزع", listOf(Pair("شهقة فزع", "<gasp>"), Pair("صرخة", "<scream>"), Pair("صرخة حادة مرعوبة", "<shriek>"), Pair("أنين خوف", "<whimper>"), Pair("لهاث ذعر", "<pant>"), Pair("خوف: شهقة وأنين", "<gasp> <whimper>"), Pair("فزع: شهقة وصرخة", "<gasp> <scream>"), Pair("ذعر: لهاث وشهقة", "<pant> <gasp>"))),
        Pair("حزن وبكاء", listOf(Pair("بكاء", "<cry>"), Pair("نشيج وبكاء مكتوم", "<sob>"), Pair("أنين خفيف", "<whimper>"), Pair("أنين ألم", "<moan>"), Pair("تأوه", "<groan>"), Pair("تنهيدة", "<sigh>"))),
        Pair("فرح وضحك", listOf(Pair("ضحكة", "<laugh>"), Pair("ضحكة خفيفة", "<chuckle>"), Pair("ضحكة مكتومة", "<giggle>"), Pair("ضحكة شريرة", "<cackle>"), Pair("ضحكة ساخرة", "<snicker>"), Pair("هتاف وتشجيع", "<cheer>"))),
        Pair("غضب وتهديد", listOf(Pair("زمجرة", "<growl>"), Pair("هسهسة", "<hiss>"), Pair("غضب مكتوم", "<grr>"), Pair("صراخ غضب", "<argh>"), Pair("صياح", "<shout>"), Pair("استنكار", "<tsk>"), Pair("نفخة سخرية", "<snort>"), Pair("نفخة استخفاف", "<pff>"))),
        Pair("تنفس وراحة", listOf(Pair("نفس", "<breath>"), Pair("أنفاس ثقيلة", "<heavy breath>"), Pair("زفير", "<exhales>"), Pair("ارتياح", "<phew>"), Pair("همس", "<whispers>"), Pair("همهمة مجهود", "<grunt>"))),
        Pair("أصوات الجسم", listOf(Pair("كحة", "<cough>"), Pair("تنحنح", "<throat-clearing>"), Pair("عطسة", "<sneeze>"), Pair("تثاؤب", "<yawn>"))),
        Pair("وقفات", listOf(Pair("وقفة قصيرة", "<short pause>"), Pair("وقفة طويلة", "<long pause>")))
    )
}
