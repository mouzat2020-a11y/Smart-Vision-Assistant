package com.mostafa.smartvision

import java.net.URLEncoder

// مصدر خبر واحد (جريدة أو قسم مقالات): روابط أساسية، وروابط احتياطية، وهل لغته أجنبية (تُترجم للعربية)
class NewsSource(val name: String, val urls: List<String>, val fallback: List<String>, val foreign: Boolean)

// مجموعة مصادر تظهر في قائمة واحدة
class NewsGroup(val title: String, val sources: List<NewsSource>, val hasForeign: Boolean)

// الصحف والمقالات: محلية (حسب الدولة)، وعربية، وعالمية
object NewsSources {
    private class Loc(val hl: String, val gl: String, val lang: String)

    private val AR_SA = Loc("ar", "SA", "ar")
    private val AR_EG = Loc("ar", "EG", "ar")
    private val EN_US = Loc("en-US", "US", "en")
    private val EN_GB = Loc("en-GB", "GB", "en")
    private val FR = Loc("fr", "FR", "fr")
    private val ES = Loc("es", "ES", "es")
    private val DE = Loc("de", "DE", "de")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // بحث جوجل نيوز داخل موقع معين
    private fun gs(domain: String, q: String, loc: Loc, w: String): String {
        var query = "site:" + domain
        if (q.isNotEmpty()) query += " " + q
        query += " when:" + w
        return "https://news.google.com/rss/search?q=" + enc(query) + "&hl=" + loc.hl + "&gl=" + loc.gl + "&ceid=" + loc.gl + ":" + loc.lang
    }

    // بحث جوجل نيوز عام بالعربية
    private fun gn(q: String, gl: String, w: String): String {
        return "https://news.google.com/rss/search?q=" + enc(q + " when:" + w) + "&hl=ar&gl=" + gl + "&ceid=" + gl + ":ar"
    }

    private fun src(name: String, direct: List<String>?, domain: String, q: String, loc: Loc, foreign: Boolean, w: String): NewsSource {
        val f = listOf(gs(domain, q, loc, w))
        if (direct != null && direct.isNotEmpty()) return NewsSource(name, direct, f, foreign)
        return NewsSource(name, f, emptyList(), foreign)
    }

    private fun group(title: String, list: List<NewsSource>): NewsGroup {
        var f = false
        for (s in list) if (s.foreign) f = true
        return NewsGroup(title, list, f)
    }

    // جرائد كل دولة (الاسم، الموقع)
    private val COUNTRY_PAPERS: Map<String, List<Pair<String, String>>> = mapOf(
        "EG" to listOf(Pair("اليوم السابع", "youm7.com"), Pair("المصري اليوم", "almasryalyoum.com"), Pair("الأهرام", "gate.ahram.org.eg"), Pair("الشروق", "shorouknews.com"), Pair("الوطن", "elwatannews.com"), Pair("مصراوي", "masrawy.com"), Pair("الدستور", "dostor.org"), Pair("الوفد", "alwafd.news"), Pair("صدى البلد", "elbalad.news"), Pair("القاهرة 24", "cairo24.com"), Pair("الجمهورية", "gomhuriaonline.com")),
        "SA" to listOf(Pair("عكاظ", "okaz.com.sa"), Pair("الرياض", "alriyadh.com"), Pair("سبق", "sabq.org"), Pair("الوطن", "alwatan.com.sa"), Pair("الاقتصادية", "aleqt.com"), Pair("المدينة", "al-madina.com"), Pair("اليوم", "alyaum.com"), Pair("وكالة الأنباء السعودية", "spa.gov.sa")),
        "AE" to listOf(Pair("الخليج", "alkhaleej.ae"), Pair("الاتحاد", "alittihad.ae"), Pair("البيان", "albayan.ae"), Pair("الإمارات اليوم", "emaratalyoum.com"), Pair("وكالة أنباء الإمارات", "wam.ae")),
        "DZ" to listOf(Pair("الشروق", "echoroukonline.com"), Pair("الخبر", "elkhabar.com"), Pair("النهار", "ennaharonline.com"), Pair("البلاد", "elbilad.net"), Pair("المساء", "el-massa.com"), Pair("وكالة الأنباء الجزائرية", "aps.dz"), Pair("الفجر", "al-fadjr.com")),
        "MA" to listOf(Pair("هسبريس", "hespress.com"), Pair("اليوم 24", "alyaoum24.com"), Pair("هبة بريس", "hibapress.com"), Pair("كود", "goud.ma"), Pair("أخبارنا", "akhbarona.com"), Pair("الصباح", "assabah.ma"), Pair("العلم", "alalam.ma")),
        "TN" to listOf(Pair("الصباح", "assabah.com.tn"), Pair("الشروق", "alchourouk.com"), Pair("موزاييك إف إم", "mosaiquefm.net"), Pair("ديوان إف إم", "diwanfm.net"), Pair("جوهرة إف إم", "jawharafm.net")),
        "LY" to listOf(Pair("الوسط", "alwasat.ly"), Pair("ليبيا الأحرار", "libyaalahrar.net"), Pair("ليبيا أوبزرفر", "libyaobserver.ly"), Pair("218", "218tv.net"), Pair("المرصد", "almarsad.co")),
        "JO" to listOf(Pair("الغد", "alghad.com"), Pair("الدستور", "addustour.com"), Pair("الرأي", "alrai.com"), Pair("عمون", "ammonnews.net"), Pair("جو 24", "jo24.net"), Pair("زاد الأردن", "jordanzad.com"), Pair("المملكة", "almamlaka.jo")),
        "LB" to listOf(Pair("النهار", "annahar.com"), Pair("الأخبار", "al-akhbar.com"), Pair("اللواء", "aliwaa.com.lb"), Pair("لبنان 24", "lebanon24.com"), Pair("النشرة", "elnashra.com"), Pair("الوكالة الوطنية للإعلام", "nna-leb.gov.lb"), Pair("إم تي في", "mtv.com.lb")),
        "SY" to listOf(Pair("سانا", "sana.sy"), Pair("الوطن", "alwatan.sy"), Pair("تشرين", "tishreen.news.sy"), Pair("عنب بلدي", "enabbaladi.net")),
        "IQ" to listOf(Pair("شفق نيوز", "shafaq.com"), Pair("السومرية", "alsumaria.tv"), Pair("وكالة الأنباء العراقية", "ina.iq"), Pair("روداو", "rudaw.net"), Pair("المدى", "almadapaper.net"), Pair("بغداد اليوم", "baghdadtoday.news")),
        "KW" to listOf(Pair("القبس", "alqabas.com"), Pair("الراي", "alraimedia.com"), Pair("الجريدة", "aljarida.com"), Pair("الأنباء", "alanba.com.kw")),
        "QA" to listOf(Pair("الوطن", "al-watan.com"), Pair("الراية", "raya.com"), Pair("العرب", "alarab.qa"), Pair("الشرق", "al-sharq.com"), Pair("وكالة الأنباء القطرية", "qna.org.qa")),
        "BH" to listOf(Pair("الوطن", "alwatannews.net"), Pair("الأيام", "alayam.com"), Pair("البلاد", "albiladpress.com"), Pair("أخبار الخليج", "akhbar-alkhaleej.com")),
        "OM" to listOf(Pair("عمان", "omandaily.om"), Pair("الرؤية", "alroya.om"), Pair("أثير", "atheer.om"), Pair("الشبيبة", "shabiba.com")),
        "YE" to listOf(Pair("المصدر أونلاين", "almasdaronline.com"), Pair("26 سبتمبر", "26sep.net"), Pair("سبأ", "saba.ye"), Pair("عدن الغد", "adengad.net")),
        "PS" to listOf(Pair("وفا", "wafa.ps"), Pair("معا", "maannews.net"), Pair("صفا", "safa.ps"), Pair("المركز الفلسطيني للإعلام", "palinfo.com"), Pair("القدس", "alquds.com")),
        "SD" to listOf(Pair("الراكوبة", "alrakoba.net"), Pair("سودان أخبار", "sudanakhbar.com"), Pair("الانتباهة", "alintibaha.net"), Pair("سونا", "suna-sd.net")),
        "MR" to listOf(Pair("الأخبار", "alakhbar.info"), Pair("صحراء ميديا", "saharamedias.net"))
    )

    fun localPapers(cc: String): NewsGroup {
        val name = NewsData.NAMES[cc] ?: ""
        val loc = Loc("ar", cc, "ar")
        val list = ArrayList<NewsSource>()
        for (p in COUNTRY_PAPERS[cc] ?: emptyList()) list.add(src(p.first, null, p.second, "", loc, false, "7d"))
        return group("الصحف المحلية (" + name + ")", list)
    }

    fun localArticles(cc: String): NewsGroup {
        val name = NewsData.NAMES[cc] ?: ""
        val loc = Loc("ar", cc, "ar")
        val list = ArrayList<NewsSource>()
        list.add(NewsSource("آخر المقالات", listOf(gn("مقالات رأي " + name, cc, "30d"), gn("مقال رأي " + name, cc, "30d")), emptyList(), false))
        var k = 0
        for (p in COUNTRY_PAPERS[cc] ?: emptyList()) {
            k++
            if (k > 7) break
            list.add(src(p.first + " - مقالات", null, p.second, "مقال رأي", loc, false, "30d"))
        }
        return group("المقالات المحلية (" + name + ")", list)
    }

    fun arabPapers(): NewsGroup {
        val l = listOf(
            src("الجزيرة نت", listOf("https://www.aljazeera.net/aljazeerarss/a7c186be-1baa-4bd4-9d80-a84db769f779/73d0e1b4-532f-45ef-b135-bfdff8b8cab9"), "aljazeera.net", "", AR_SA, false, "7d"),
            src("بي بي سي عربي", listOf("https://feeds.bbci.co.uk/arabic/rss.xml"), "bbc.com/arabic", "", AR_SA, false, "7d"),
            src("سكاي نيوز عربية", listOf("https://www.skynewsarabia.com/web/rss"), "skynewsarabia.com", "", AR_SA, false, "7d"),
            src("العربية", listOf("https://www.alarabiya.net/feed/rss2/ar.xml"), "alarabiya.net", "", AR_SA, false, "7d"),
            src("الشرق الأوسط", listOf("https://aawsat.com/feed"), "aawsat.com", "", AR_SA, false, "7d"),
            src("القدس العربي", listOf("https://www.alquds.co.uk/feed/"), "alquds.co.uk", "", AR_SA, false, "7d"),
            src("العربي الجديد", null, "alaraby.co.uk", "", AR_SA, false, "7d"),
            src("الشرق", null, "asharq.com", "", AR_SA, false, "7d"),
            src("الخليج", null, "alkhaleej.ae", "", AR_SA, false, "7d"),
            src("الاتحاد", null, "alittihad.ae", "", AR_SA, false, "7d"),
            src("عكاظ", null, "okaz.com.sa", "", AR_SA, false, "7d"),
            src("الرياض", null, "alriyadh.com", "", AR_SA, false, "7d"),
            src("الأخبار اللبنانية", null, "al-akhbar.com", "", AR_SA, false, "7d"),
            src("الغد الأردنية", null, "alghad.com", "", AR_SA, false, "7d"),
            src("الشروق الجزائرية", listOf("https://www.echoroukonline.com/feed"), "echoroukonline.com", "", AR_SA, false, "7d"),
            src("هسبريس المغربية", listOf("https://www.hespress.com/feed"), "hespress.com", "", AR_SA, false, "7d")
        )
        return group("الصحف العربية", l)
    }

    fun worldPapers(): NewsGroup {
        val l = listOf(
            src("BBC News", listOf("https://feeds.bbci.co.uk/news/world/rss.xml"), "bbc.com", "", EN_GB, true, "7d"),
            src("The Guardian", listOf("https://www.theguardian.com/world/rss"), "theguardian.com", "", EN_GB, true, "7d"),
            src("New York Times", listOf("https://rss.nytimes.com/services/xml/rss/nyt/World.xml"), "nytimes.com", "", EN_US, true, "7d"),
            src("Washington Post", listOf("https://feeds.washingtonpost.com/rss/world"), "washingtonpost.com", "", EN_US, true, "7d"),
            src("Reuters", null, "reuters.com", "", EN_US, true, "7d"),
            src("Associated Press", null, "apnews.com", "", EN_US, true, "7d"),
            src("CNN", listOf("https://rss.cnn.com/rss/edition_world.rss"), "cnn.com", "", EN_US, true, "7d"),
            src("Al Jazeera English", listOf("https://www.aljazeera.com/xml/rss/all.xml"), "aljazeera.com", "", EN_US, true, "7d"),
            src("DW English", listOf("https://rss.dw.com/xml/rss-en-all"), "dw.com", "", EN_US, true, "7d"),
            src("France 24 English", listOf("https://www.france24.com/en/rss"), "france24.com", "", EN_US, true, "7d"),
            src("Japan Times", listOf("https://www.japantimes.co.jp/feed/"), "japantimes.co.jp", "", EN_US, true, "7d"),
            src("South China Morning Post", null, "scmp.com", "", EN_US, true, "7d"),
            src("Le Monde (فرنسية)", listOf("https://www.lemonde.fr/rss/une.xml"), "lemonde.fr", "", FR, true, "7d"),
            src("El País (إسبانية)", null, "elpais.com", "", ES, true, "7d"),
            src("Der Spiegel (ألمانية)", listOf("https://www.spiegel.de/schlagzeilen/index.rss"), "spiegel.de", "", DE, true, "7d")
        )
        return group("الصحف العالمية", l)
    }

    fun arabArticles(): NewsGroup {
        val l = listOf(
            NewsSource("آخر المقالات العربية", listOf(gn("مقالات رأي عربية", "SA", "30d"), gn("مقال رأي الوطن العربي", "SA", "30d")), emptyList(), false),
            NewsSource(
                "العربية - آراء ومقالات",
                listOf(gs("alarabiya.net/views", "", AR_SA, "30d"), gs("alarabiya.net/opinions", "", AR_SA, "30d"), gs("alarabiya.net/views", "", AR_EG, "30d")),
                listOf(gs("alarabiya.net", "مقال رأي", AR_SA, "30d"), gs("alarabiya.net", "كاتب رأي", AR_EG, "30d")),
                false
            ),
            src("الجزيرة نت - آراء", null, "aljazeera.net", "رأي", AR_SA, false, "30d"),
            src("الشرق الأوسط - آراء", null, "aawsat.com", "رأي", AR_SA, false, "30d"),
            src("القدس العربي - مقالات", null, "alquds.co.uk", "مقال", AR_SA, false, "30d"),
            src("العربي الجديد - آراء", null, "alaraby.co.uk", "رأي", AR_SA, false, "30d"),
            src("الاتحاد - مقالات", null, "alittihad.ae", "مقال", AR_SA, false, "30d"),
            src("الخليج - مقالات", null, "alkhaleej.ae", "مقال", AR_SA, false, "30d"),
            src("عكاظ - مقالات", null, "okaz.com.sa", "مقال", AR_SA, false, "30d"),
            src("الرياض - مقالات", null, "alriyadh.com", "مقال", AR_SA, false, "30d"),
            src("الغد - مقالات", null, "alghad.com", "مقال", AR_SA, false, "30d"),
            src("الشرق - آراء", null, "asharq.com/opinion", "", AR_SA, false, "30d"),
            src("إندبندنت عربية - آراء", null, "independentarabia.com", "رأي OR مقال", AR_SA, false, "30d"),
            src("عربي21 - مقالات", null, "arabi21.com", "مقال OR رأي", AR_SA, false, "30d"),
            src("رأي اليوم - مقالات", null, "raialyoum.com", "مقال OR رأي", AR_SA, false, "30d"),
            src("العرب اللندنية - آراء", null, "alarab.co.uk", "رأي OR مقال", AR_SA, false, "30d"),
            src("المجلة - آراء", null, "majalla.com", "رأي OR مقال", AR_SA, false, "30d"),
            src("Arab News - Opinion (إنجليزي)", null, "arabnews.com", "opinion", EN_US, true, "30d")
        )
        return group("المقالات العربية", l)
    }

    fun worldArticles(): NewsGroup {
        val l = listOf(
            src("The Guardian - Opinion", listOf("https://www.theguardian.com/commentisfree/rss"), "theguardian.com", "opinion", EN_GB, true, "30d"),
            src("New York Times - Opinion", listOf("https://rss.nytimes.com/services/xml/rss/nyt/Opinion.xml"), "nytimes.com", "opinion", EN_US, true, "30d"),
            src("Washington Post - Opinions", listOf("https://feeds.washingtonpost.com/rss/opinions"), "washingtonpost.com", "opinion", EN_US, true, "30d"),
            src("Project Syndicate", listOf("https://www.project-syndicate.org/rss"), "project-syndicate.org", "", EN_US, true, "30d"),
            src("Foreign Policy", listOf("https://foreignpolicy.com/feed/"), "foreignpolicy.com", "", EN_US, true, "30d"),
            src("The Economist - Leaders", listOf("https://www.economist.com/leaders/rss.xml"), "economist.com", "leaders", EN_US, true, "30d"),
            src("Al Jazeera English - Opinion", null, "aljazeera.com", "opinion", EN_US, true, "30d"),
            src("DW - Opinion", null, "dw.com", "opinion", EN_US, true, "30d"),
            src("Le Monde - Tribunes (فرنسية)", null, "lemonde.fr", "tribune idées", FR, true, "30d"),
            src("El País - Opinión (إسبانية)", null, "elpais.com", "opinión", ES, true, "30d"),
            src("Der Spiegel - Meinung (ألمانية)", listOf("https://www.spiegel.de/meinung/index.rss"), "spiegel.de", "Meinung", DE, true, "30d")
        )
        return group("المقالات العالمية", l)
    }
}
