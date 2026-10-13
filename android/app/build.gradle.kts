import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "ru.miet.calendar"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.miet.calendar"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

}

// Python 3.14 на машине сборки нужен Chaquopy для pip. Если его нет в PATH,
// укажите путь в android/local.properties: chaquopy.buildPython=C:/путь/к/python.exe
val buildPythonPath: String? = Properties().let { props ->
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use(props::load)
    props.getProperty("chaquopy.buildPython")
}

chaquopy {
    defaultConfig {
        version = "3.14"
        buildPythonPath?.let { buildPython(it) }
        pip {
            install("httpx")
            install("tzdata")
        }
    }
    // Парсер лежит в корне репозитория (../../src) и подключается как есть.
    // ical и CLI в приложение не попадают: pydantic-core не собирается под Android.
    sourceSets {
        getByName("main") {
            srcDir("../../src")
            exclude("**/cli.py", "**/ics.py", "**/__main__.py")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation("junit:junit:4.13.2")
    // В android.jar org.json — заглушка, для JVM-тестов нужна настоящая реализация.
    testImplementation("org.json:json:20240303")
}
