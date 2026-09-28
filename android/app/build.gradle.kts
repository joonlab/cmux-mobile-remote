import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.application")
    // AGP 9 부터 Kotlin 은 AGP 내장 — kotlin.android 플러그인을 넣으면 빌드가 죽는다.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "kr.joonlab.cmuxremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.joonlab.cmuxremote"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // 재설치가 진짜 반영됐는지 화면에서 확인하려고 빌드 시각을 박는다.
        val stamp = SimpleDateFormat("MM-dd HH:mm").apply {
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
        }.format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$stamp\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose·activity 는 core 가 api 로 넘겨준다(검증한 조합 — BOM 2026.08+ 는 compileSdk 37 요구).
    implementation(project(":core"))
}
