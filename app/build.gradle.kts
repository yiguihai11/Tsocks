plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.mozilla.rust-android-gradle.rust-android")
}

android {
    namespace = "com.yiguihai.tsocks"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.yiguihai.tsocks"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 设置 APK 文件名：将 archivesBaseName 属性设置为 "$applicationId-$versionName"
        setProperty("archivesBaseName", "$applicationId-$versionName")

        externalNativeBuild.ndkBuild {
            // 使用 addAll 而非 +=，更符合 Kotlin DSL 风格
            arguments.addAll(
                listOf(
                    "APP_CFLAGS+=-DPKGNAME=com/yiguihai/tsocks -ffile-prefix-map=${rootDir}=.",
                    "APP_LDFLAGS+=-Wl,--build-id=none",
                    "-j${Runtime.getRuntime().availableProcessors()}"
                )
            )
        }
    }

    // 最新 AGP 中推荐使用 packaging {} DSL
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("x86_64")
            isUniversalApk = false
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
        }
        debug {
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

cargo {
    module = "src/main/jni/shadowsocks-rust"
    libname = "sslocal"
    verbose = false
    targets = listOf("x86_64")
    profile = findProperty("CARGO_PROFILE")?.toString() ?: "release"
    extraCargoBuildArguments = listOf("--bin", libname ?: "sslocal")
    featureSpec.noDefaultBut(arrayOf(
        "local-tunnel", "local-online-config", "logging", "local-flow-stat", "local-dns", "aead-cipher-2022"
    ))
    exec = { spec, toolchain ->
        run {
            // 检测 Python 版本
            val pythonCommand = if (isPython3Available()) "python" else null
            if (pythonCommand != null) {
                spec.environment("RUST_ANDROID_GRADLE_PYTHON_COMMAND", pythonCommand)
                project.logger.lifecycle("Python 3 detected: $pythonCommand")
            } else {
                throw GradleException("No Python 3 detected. Please install Python 3 to compile the project.")
            }

            // 设置链接相关环境变量
            spec.environment("RUST_ANDROID_GRADLE_CC_LINK_ARG", "-Wl,-z,max-page-size=16384,-soname,lib$libname.so")

            // 使用 File 类构建路径
            val linkerWrapperPath = File(projectDir, "$module/../linker-wrapper.py").absolutePath
            spec.environment("RUST_ANDROID_GRADLE_LINKER_WRAPPER_PY", linkerWrapperPath)

            val targetPath = File("target", "${toolchain.target}/$profile/lib$libname.so").path
            spec.environment("RUST_ANDROID_GRADLE_TARGET", targetPath)
        }
    }
}

// 辅助函数：检查 Python 3 是否可用
fun isPython3Available(): Boolean {
    try {
        val process = Runtime.getRuntime().exec(arrayOf("python", "--version"))
        val output = process.inputStream.bufferedReader().readText().trim()
        if (output.startsWith("Python 3")) {
            return true
        }
    } catch (e: Exception) {
        // Ignore
    }
    try {
        val process = Runtime.getRuntime().exec(arrayOf("python3", "--version"))
        val output = process.inputStream.bufferedReader().readText().trim()
        if (output.startsWith("Python 3")) {
            return true
        }
    } catch (e: Exception) {
        // Ignore
    }
    return false
}

tasks.whenTaskAdded {
    when (name) {
        "mergeDebugJniLibFolders", "mergeReleaseJniLibFolders" -> dependsOn("cargoBuild")
    }
}

tasks.register<Exec>("cargoClean") {
    executable("cargo")
    args("clean")
    workingDir("$projectDir/${cargo.module}")
}
// tasks.clean.dependsOn("cargoClean")

tasks.register<Exec>("buildGoExecutable") {
    // 注意：这里直接使用字符串插值，确保 ndkDir 等变量能正确解析为绝对路径
    val ndkDir = android.ndkDirectory.absolutePath
    val osName = System.getProperty("os.name").lowercase()
    val toolchainDir = if (osName.contains("windows")) "windows" else "linux"
    val toolchain = "$ndkDir/toolchains/llvm/prebuilt/${toolchainDir}-x86_64/bin"

    val abis = listOf("x86_64")
    val goArchs = listOf("amd64")
    val clangArchs = listOf("x86_64-linux-android")
    val minApi = android.defaultConfig.minSdkVersion?.apiLevel ?: 21

    workingDir("src/main/jni/v2ray-plugin")
    environment("CGO_ENABLED", "1")
    environment("GOOS", "android")
    environment("GOARCH", goArchs[0])
    environment("CC", "$toolchain/${clangArchs[0]}${minApi}-clang")

    commandLine("go", "build", "-ldflags=-s -w", "-o", "${projectDir}/src/main/jniLibs/${abis[0]}/libv2ray-plugin.so")
}

tasks.whenTaskAdded {
    if (name == "cargoBuild") {
        dependsOn("buildGoExecutable")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.gson)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.geoip2)
    implementation(libs.flagkit.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}