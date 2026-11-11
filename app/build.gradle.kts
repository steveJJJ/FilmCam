import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息放在项目根的 keystore.properties（不入库）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { this.load(it) }
}

android {
    namespace = "com.filmcam.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.filmcam.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 72
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
        viewBinding = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // 相机：直接使用 Camera2（预览 SurfaceTexture + ImageReader 全尺寸 JPEG），不再依赖 CameraX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    // BLE
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // EXIF 写入（模拟胶片机元数据）
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // 图片加载（画廊缩略图，含内存/磁盘缓存）
    implementation("io.coil-kt:coil:2.7.0")

    // 内购：工程模式 Pro（一次性买断）。Play 自 2026-08-31 起强制要求 8+。
    // 传递依赖会注入网络/位置权限，已在 AndroidManifest 用 tools:node="remove" 剔除。
    implementation("com.android.billingclient:billing:9.1.0")

    // 检查更新（v65）：Play 安装的包走官方应用内更新，其余走 ACTION_VIEW 跳转页。
    // 零新增权限 —— 官方库通过 Play 商店 IPC 工作；传递依赖若注入网络权限，
    // 已被 AndroidManifest 的 tools:node="remove" 剔除（构建后用 aapt2 实测）。
    implementation("com.google.android.play:app-update:2.1.0")

    // 单元测试：纯 JVM（映射表 / 参数拼装 / 状态机），不依赖 Robolectric
    testImplementation("junit:junit:4.13.2")
    // Android 的 org.json 在 JVM 单测里是空桩，换成真实实现以便断言 JSON
    testImplementation("org.json:json:20240303")
}
