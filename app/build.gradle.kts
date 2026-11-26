plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mostafa.smartvision"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mostafa.smartvision"
        minSdk = 26
        targetSdk = 35
        // رقم الإصدار يزيد تلقائيًا مع كل بناء على GitHub، حتى يقبله أندرويد كتحديث
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1
        versionName = "0.1-alpha"
    }
    // مفتاح توقيع ثابت: كل نسخة تُبنى بنفس التوقيع، فتتثبت فوق النسخة السابقة بدون مسح
    // كلمة سر المفتاح لا تُكتب هنا: تُقرأ من سر GitHub باسم SV_KEY_PASSWORD
    signingConfigs {
        create("fixed") {
            val pw = System.getenv("SV_KEY_PASSWORD")
            if (pw != null && pw.isNotEmpty()) {
                storeFile = file("smartvision-release.p12")
                storePassword = pw
                keyAlias = "smartvision"
                keyPassword = pw
                storeType = "pkcs12"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("fixed")
            isMinifyEnabled = false
        }
    }
    lint {
        checkReleaseBuilds = false
    }
}
