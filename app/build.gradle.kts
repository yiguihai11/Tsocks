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

        // 使用稳定的 NDK 构建配置方式
        externalNativeBuild {
            ndkBuild {
                // 正确传递编译和链接标志
                arguments("NDK_APPLICATION_MK=${project.file("src/main/jni/Application.mk")}")
                arguments("APP_CFLAGS+=-DPKGNAME=com/yiguihai/tsocks")
                arguments("APP_CFLAGS+=-ffile-prefix-map=${rootDir}=.")
                arguments("APP_LDFLAGS+=-Wl,--build-id=none")
                // 添加多线程编译参数
                arguments("-j${Runtime.getRuntime().availableProcessors()}")
            }
        }
    }

    externalNativeBuild.ndkBuild.path("src/main/jni/Android.mk")

    // 最新 AGP 中推荐使用 packaging {} DSL
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            // 指定多个 ABI 的 jniLibs 路径
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            // 添加所有目标 ABI
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
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

// 为 cargo（Rust）设置多个编译目标：注意 target triple 需根据实际情况调整
cargo {
    module = "src/main/jni/shadowsocks-rust"
    libname = "sslocal"
    verbose = false
    // 这里以常用的 Android Rust target triple 为例
    targets = listOf("arm", "arm64", "x86", "x86_64")
    profile = findProperty("CARGO_PROFILE")?.toString() ?: "release"
    extraCargoBuildArguments = listOf("--bin", libname ?: "sslocal")
    featureSpec.noDefaultBut(arrayOf(
        "local-tunnel",
        "local-online-config",
        "logging",
        "local-flow-stat",
        "local-dns",
        "aead-cipher-2022"
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

            // toolchain.target 这里应为当前正在编译的 target triple（例如 "x86_64-linux-android"），
            // 如果可能为空可添加默认值
            val currentTarget: String = toolchain.target as? String ?: "x86_64-linux-android"
            val targetPath = File("target", "$currentTarget/$profile/lib$libname.so").path
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

// 当合并 JNI 文件夹时依赖 cargoBuild
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

// 为 Go 编译分别为不同 ABI 生成独立任务
val ndkDir = android.ndkDirectory.absolutePath
val osName = System.getProperty("os.name").lowercase()
val toolchainDir = if (osName.contains("windows")) "windows" else "linux"
val toolchainPath = "$ndkDir/toolchains/llvm/prebuilt/${toolchainDir}-x86_64/bin"
val minApi: Int = android.defaultConfig.minSdkVersion?.apiLevel as? Int ?: 21

// 定义各 ABI 的配置：(ABI, GOARCH, clang 前缀)
val abiConfigs = listOf(
    Triple("armeabi-v7a", "arm", "armv7a-linux-androideabi"),
    Triple("arm64-v8a", "arm64", "aarch64-linux-android"),
    Triple("x86", "386", "i686-linux-android"),
    Triple("x86_64", "amd64", "x86_64-linux-android")
)

// 为每个 ABI 注册一个 go 编译任务
abiConfigs.forEach { (abi, goArch, clangArch) ->
    tasks.register<Exec>("buildGoExecutable_$abi") {
        workingDir("src/main/jni/v2ray-plugin")
        environment("CGO_ENABLED", "1")
        environment("GOOS", "android")
        environment("GOARCH", goArch)
        environment("CC", "$toolchainPath/${clangArch}${minApi}-clang")
        // 输出路径根据 ABI 放到对应文件夹中
        commandLine("go", "build", "-ldflags=-s -w", "-o", "${projectDir}/src/main/jniLibs/$abi/libv2ray-plugin.so")
    }
}

// 注册一个汇总任务，使得调用 buildGoExecutable 时构建所有 ABI 版本
tasks.register("buildGoExecutable") {
    dependsOn(abiConfigs.map { (abi, _, _) -> tasks.named("buildGoExecutable_$abi") })
}

// 当 cargoBuild 任务添加时，依赖 buildGoExecutable（汇总任务）
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
    implementation(libs.snakeyaml)
    implementation(libs.androidx.localbroadcastmanager)
    implementation(libs.material3)
    // 二维码扫描库
    implementation(libs.zxing.android.embedded)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}