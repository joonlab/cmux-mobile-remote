// 두 앱(관제실 :app · 기록 :history)이 같이 쓰는 것 — 연결·JSON·테마 주입·글자 크기·칸 레일·아이콘·Pretendard.
// 색은 여기서 정하지 않는다. 앱이 CorePalette 를 주입한다(관제실 = Emerald Noir).
import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 맥 에이전트 주소 — 코드에 박지 않고 빌드 때 주입한다.
// 우선순위: android/local.properties 의 cmr.machines > gradle 속성 -Pcmr.machines > 환경변수 CMR_MACHINES.
// 형식: key|이름|https://주소:포트 를 쉼표로 잇는다. 예시는 android/local.properties.example.
val cmrMachines: String = run {
    val lp = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { lp.load(it) }
    lp.getProperty("cmr.machines")
        ?: (project.findProperty("cmr.machines") as String?)
        ?: System.getenv("CMR_MACHINES")
        ?: ""
}

android {
    namespace = "kr.joonlab.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        buildConfigField("String", "CMR_MACHINES", "\"${cmrMachines.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // 앱이 core 를 쓰면 Compose 도 같이 보이게 api 로 건다(버전은 한 곳에서).
    api(platform("androidx.compose:compose-bom:2026.06.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.material3:material3")
    api("androidx.compose.foundation:foundation")
    api("androidx.activity:activity-compose:1.12.4")
}
