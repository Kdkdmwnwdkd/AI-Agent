import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.parcelize)
    id("io.objectbox")
    id("kotlin-kapt")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

data class SttModelAsset(
    val targetPath: String,
    val sourceUrl: String,
    val expectedBytes: Long,
    val expectedSha256: String,
)

data class ApkRotationSigningConfig(
    val apksigner: File,
    val oldStoreFile: File,
    val oldStorePassword: String,
    val oldKeyAlias: String,
    val oldKeyPassword: String,
    val newStoreFile: File,
    val newStorePassword: String,
    val newKeyAlias: String,
    val newKeyPassword: String,
    val lineageFile: File,
)

fun requiredLocalProperty(name: String): String {
    val value = localProperties.getProperty(name)?.trim()
    require(!value.isNullOrEmpty()) {
        "local.properties must define $name for Release/Nightly APK rotation signing"
    }
    return value
}

fun configuredFileProperty(name: String): File {
    val configuredPath = File(requiredLocalProperty(name))
    val resolvedPath = if (configuredPath.isAbsolute) configuredPath else rootProject.file(configuredPath)
    require(resolvedPath.isFile) {
        "Configured $name does not point to a file: ${resolvedPath.path}"
    }
    return resolvedPath
}

fun loadApkRotationSigningConfig(): ApkRotationSigningConfig {
    val sdkDirectory = File(requiredLocalProperty("sdk.dir"))
    require(sdkDirectory.isDirectory) {
        "Configured sdk.dir does not point to a directory: ${sdkDirectory.path}"
    }

    val apksignerName = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        "apksigner.bat"
    } else {
        "apksigner"
    }
    val apksigner = sdkDirectory.resolve("build-tools/35.0.0/$apksignerName")
    require(apksigner.isFile) {
        "Android build-tools 35.0.0 apksigner is required: ${apksigner.path}"
    }

    return ApkRotationSigningConfig(
        apksigner = apksigner,
        oldStoreFile = configuredFileProperty("RELEASE_STORE_FILE"),
        oldStorePassword = requiredLocalProperty("RELEASE_STORE_PASSWORD"),
        oldKeyAlias = requiredLocalProperty("RELEASE_KEY_ALIAS"),
        oldKeyPassword = requiredLocalProperty("RELEASE_KEY_PASSWORD"),
        newStoreFile = configuredFileProperty("APK_ROTATION_NEW_STORE_FILE"),
        newStorePassword = requiredLocalProperty("APK_ROTATION_NEW_STORE_PASSWORD"),
        newKeyAlias = requiredLocalProperty("APK_ROTATION_NEW_KEY_ALIAS"),
        newKeyPassword = requiredLocalProperty("APK_ROTATION_NEW_KEY_PASSWORD"),
        lineageFile = configuredFileProperty("APK_ROTATION_LINEAGE_FILE"),
    )
}

fun signApkWithRotation(apkFile: File) {
    require(apkFile.isFile) { "APK to sign was not produced: ${apkFile.path}" }
    val config = loadApkRotationSigningConfig()
    val rotatedApk = apkFile.resolveSibling(".${apkFile.name}.rotation-signing")
    require(!rotatedApk.exists()) {
        "Refusing to overwrite an existing rotation signing output: ${rotatedApk.path}"
    }

    // API 28 is the first platform that selects V3 and understands proof-of-rotation;
    // API 26/27 therefore continue to select the old signer from the V2 block.
    val signingArguments = listOf(
        "sign",
        "--in", apkFile.path,
        "--out", rotatedApk.path,
        "--min-sdk-version", "26",
        "--v1-signing-enabled", "false",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--v4-signing-enabled", "false",
        "--lineage", config.lineageFile.path,
        "--rotation-min-sdk-version", "28",
        "--ks", config.oldStoreFile.path,
        "--ks-type", "PKCS12",
        "--ks-key-alias", config.oldKeyAlias,
        "--ks-pass", "env:OPERIT_OLD_STORE_PASSWORD",
        "--key-pass", "env:OPERIT_OLD_KEY_PASSWORD",
        "--next-signer",
        "--ks", config.newStoreFile.path,
        "--ks-type", "PKCS12",
        "--ks-key-alias", config.newKeyAlias,
        "--ks-pass", "env:OPERIT_NEW_STORE_PASSWORD",
        "--key-pass", "env:OPERIT_NEW_KEY_PASSWORD",
    )

    project.exec {
        commandLine(listOf(config.apksigner.path) + signingArguments)
        environment("OPERIT_OLD_STORE_PASSWORD", config.oldStorePassword)
        environment("OPERIT_OLD_KEY_PASSWORD", config.oldKeyPassword)
        environment("OPERIT_NEW_STORE_PASSWORD", config.newStorePassword)
        environment("OPERIT_NEW_KEY_PASSWORD", config.newKeyPassword)
    }

    project.exec {
        commandLine(config.apksigner.path, "verify", "--verbose", "--print-certs", rotatedApk.path)
    }

    Files.move(
        rotatedApk.toPath(),
        apkFile.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
    )
}

val requiredExternallyBuiltNativeLibraries =
    listOf(
        file("src/main/jniLibs/arm64-v8a/liboperit_ripgrep.so"),
    )

val ffmpegKitLocalAar = file("libs/ffmpeg-kit-local.aar")
val requiredFfmpegKitArm64Libraries =
    setOf(
        "jni/arm64-v8a/libavcodec.so",
        "jni/arm64-v8a/libavdevice.so",
        "jni/arm64-v8a/libavfilter.so",
        "jni/arm64-v8a/libavformat.so",
        "jni/arm64-v8a/libavutil.so",
        "jni/arm64-v8a/libc++_shared.so",
        "jni/arm64-v8a/libffmpegkit.so",
        "jni/arm64-v8a/libffmpegkit_abidetect.so",
        "jni/arm64-v8a/libswresample.so",
        "jni/arm64-v8a/libswscale.so",
    )

val verifyExternallyBuiltNativeLibraries by tasks.registering {
    description = "Checks native libraries built outside Gradle before Android packaging."
    group = "verification"
    inputs.property(
        "requiredLibraries",
        requiredExternallyBuiltNativeLibraries.map { library -> library.path },
    )
    inputs.property("ffmpegKitAar", ffmpegKitLocalAar.path)
    inputs.property("ffmpegKitArm64Libraries", requiredFfmpegKitArm64Libraries)
    outputs.upToDateWhen { false }

    doLast {
        val invalidLibraries =
            requiredExternallyBuiltNativeLibraries.filter { library ->
                !library.isFile || library.length() == 0L
            }
        require(invalidLibraries.isEmpty()) {
            "Missing or empty externally built native library: " +
                invalidLibraries.joinToString { library -> library.path } +
                ". Run tools/native_ripgrep/build_native_ripgrep.ps1 before packaging."
        }

        require(ffmpegKitLocalAar.isFile && ffmpegKitLocalAar.length() > 0L) {
            "Missing or empty FFmpegKit AAR: ${ffmpegKitLocalAar.path}. " +
                "Build it with tools/ffmpeg/build_ffmpeg_kit_wsl.sh and import it with " +
                "tools/ffmpeg/import_local_ffmpeg_kit.ps1 before packaging."
        }

        ZipFile(ffmpegKitLocalAar).use { archive ->
            val invalidEntries =
                requiredFfmpegKitArm64Libraries.filter { entryName ->
                    val entry = archive.getEntry(entryName)
                    entry == null || entry.size <= 0L
                }
            require(invalidEntries.isEmpty()) {
                "FFmpegKit AAR is missing or contains empty arm64 native libraries: " +
                    invalidEntries.joinToString()
            }
        }
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

fun parseSttModelAssetManifest(manifestFile: File): List<SttModelAsset> {
    return manifestFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapIndexed { index, line ->
            val parts = line.split("|")
            require(parts.size == 6) {
                "Invalid STT model asset manifest line ${index + 1}: expected 6 fields"
            }
            val targetPath = parts[0]
            require(!targetPath.startsWith("/") && !targetPath.contains("..") && !targetPath.contains('\\')) {
                "Invalid STT model asset target path: $targetPath"
            }
            SttModelAsset(
                targetPath = targetPath,
                sourceUrl = parts[1],
                expectedBytes = parts[2].toLong(),
                expectedSha256 = parts[3].lowercase(),
            )
        }
}

fun verifySttModelAsset(file: File, asset: SttModelAsset): Boolean {
    return file.isFile &&
        file.length() == asset.expectedBytes &&
        sha256(file) == asset.expectedSha256
}

fun downloadSttModelAsset(asset: SttModelAsset, destination: File) {
    destination.parentFile.mkdirs()
    require(destination.parentFile.isDirectory) {
        "Unable to create STT model asset directory: ${destination.parent}"
    }

    val tempFile = File(destination.parentFile, "${destination.name}.download")
    if (tempFile.exists()) {
        tempFile.delete()
    }

    val connection = URI(asset.sourceUrl).toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 30_000
    connection.readTimeout = 120_000
    connection.setRequestProperty("User-Agent", "Operit Android build STT asset sync")
    try {
        val responseCode = connection.responseCode
        require(responseCode in 200..299) {
            "Unable to download ${asset.targetPath}: HTTP $responseCode from ${asset.sourceUrl}"
        }
        connection.inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    } finally {
        connection.disconnect()
    }

    require(verifySttModelAsset(tempFile, asset)) {
        "Downloaded STT model asset failed verification: ${asset.targetPath}"
    }
    Files.move(tempFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

val sttModelAssetsManifestFile = layout.projectDirectory.file("config/stt-model-assets.properties")
val generatedSttModelAssetsDir = layout.buildDirectory.dir("generated/stt-model-assets")
val generatedMainAssetsDir = layout.buildDirectory.dir("generated/main-assets")

val syncSttModelAssets by tasks.registering {
    description = "Downloads and verifies generated assets for local STT recognition."
    group = "build setup"

    inputs.file(sttModelAssetsManifestFile)
    outputs.dir(generatedSttModelAssetsDir)
    outputs.upToDateWhen { false }

    doLast {
        val manifestFile = sttModelAssetsManifestFile.asFile
        val assets = parseSttModelAssetManifest(manifestFile)
        val outputRoot = generatedSttModelAssetsDir.get().asFile
        outputRoot.mkdirs()

        val outputRootPath = outputRoot.toPath().toAbsolutePath().normalize()
        val expectedFiles = mutableSetOf<File>()

        assets.forEach { asset ->
            val destinationPath = outputRootPath.resolve(asset.targetPath).normalize()
            require(destinationPath.startsWith(outputRootPath)) {
                "STT model asset target escapes generated assets directory: ${asset.targetPath}"
            }
            val destination = destinationPath.toFile()
            expectedFiles.add(destination.canonicalFile)

            if (!verifySttModelAsset(destination, asset)) {
                if (destination.exists() && !destination.delete()) {
                    error("Unable to replace invalid STT model asset: ${destination.path}")
                }
                downloadSttModelAsset(asset, destination)
            }

            require(verifySttModelAsset(destination, asset)) {
                "STT model asset verification failed after sync: ${asset.targetPath}"
            }
        }

        outputRoot.walkBottomUp()
            .filter { it.isFile && it.canonicalFile !in expectedFiles }
            .forEach { file ->
                require(file.delete()) {
                    "Unable to remove stale STT model asset: ${file.path}"
                }
            }
        outputRoot.walkBottomUp()
            .filter { it.isDirectory && it != outputRoot && it.list()?.isEmpty() == true }
            .forEach { directory ->
                require(directory.delete()) {
                    "Unable to remove empty STT model asset directory: ${directory.path}"
                }
            }
    }
}

val syncMainAssets by tasks.registering(Sync::class) {
    description = "Assembles application assets with verified generated STT model files."
    group = "build setup"
    dependsOn(syncSttModelAssets)

    from("src/main/assets") {
        exclude("models/**")
    }
    from(generatedSttModelAssetsDir)
    into(generatedMainAssetsDir)
}

android {
    namespace = "com.ai.assistance.operit"
    compileSdk = 36
    // 显式锁定 NDK 版本，与 CI 环境（ubuntu-24.04 runner SDK 预装的 NDK 27）保持一致，
    // 避免 AGP 自动推断与 ndk.dir 冲突（[CXX1104]）。
    ndkVersion = "27.0.12077973"

    sourceSets {
        getByName("main") {
            assets.setSrcDirs(listOf(generatedMainAssetsDir.get().asFile))
        }
    }

    signingConfigs {
        // 显式配置 debug 签名。
        //
        // 注意：AGP 已内置一个名为 "debug" 的 signingConfig，
        // 因此这里必须用 getByName 覆盖其属性，而不能 create（会报重名）。
        //
        // 为什么要覆盖：AGP 内置 debug 配置的 storeFile 并非
        // ~/.android/debug.keystore，而是 AGP 自己管理的路径，缺失时会
        // **自动生成一把随机密钥**。后果是每次 CI 出包的签名都不同，
        // 安装新包必须先卸载旧版（报 "安装包无效或不兼容"），
        // 且构建显示成功，问题被完全掩盖。
        //
        // 固定后：CI 把仓库内 ci/signing/debug-keystore.b64 解码写入
        // ~/.android/debug.keystore，这里显式指向它，所有构建签名一致。
        getByName("debug") {
            val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
            // 文件不存在时直接失败，而不是静默回退到 AGP 的随机密钥。
            // 静默回退会导致"构建成功但签名不对"，是本问题最难排查的地方。
            if (!debugKeystore.exists()) {
                throw GradleException(
                    "找不到固定的 debug keystore: ${debugKeystore.absolutePath}\n" +
                    "签名不固定会导致 APK 无法覆盖安装。\n" +
                    "CI 环境请确认 `Setup fixed debug keystore` 步骤已成功执行。"
                )
            }
            storeFile = debugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        val releaseKeystorePath = localProperties.getProperty("RELEASE_STORE_FILE")
        val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
        val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
        val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")

        if (releaseKeystorePath != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null &&
            File(releaseKeystorePath).exists()
        ) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    defaultConfig {
        applicationId = "com.ai.assistance.operit"
        minSdk = 26
        targetSdk = 34

        // ── 版本号 ────────────────────────────────────────────────────────
        // 玄枵是 Operit 的二次开发版，需要保证「同签名前提下可直接覆盖安装」
        // （不覆盖安装就必须先卸载，用户数据会丢）。
        //
        // 规则：
        //   versionCode = 基准(51000) + 构建号
        //   versionName = "<上游基线版本>+<构建号>"
        //
        // versionName 的格式是被上游代码强约束的，不能随意发挥：
        //   插件市场 ArtifactMarketModels.parseAppVersionOrNull() 与
        //   ToolPkg 的 OperitVersion.parse() 两处都用同一个正则解析
        //   BuildConfig.VERSION_NAME：
        //       ^(\d+)\.(\d+)\.(\d+)(?:\+(\d+))?$
        //   即只接受 "x.y.z" 或 "x.y.z+n"。写成 "1.12.2-xx.244" 这类
        //   带别的后缀的形式，会在这两处抛 IllegalArgumentException，
        //   表现是插件市场列表整体崩溃（显示"暂无可用插件"）、ToolPkg 校验失败。
        //   上游自身就是这么用的（ToolPkgApiVersion.API_VERSION_1_0_1_INTRODUCED_IN_OPERIT
        //   = "1.12.1+4"），所以这里沿用 "+<构建号>"。
        //
        // 为什么用 51000 起步而不是 52：
        //   上游 Operit 当前 versionCode 为 51，且会持续增长。若本仓库用 52、53…
        //   叠加，将来合入上游版本时极易与之撞号（撞号会导致无法覆盖安装）。
        //   取 51000 前缀使本仓库版本号与上游天然错开，同时仍单调递增。
        //
        // 构建号来源：
        //   - CI：GITHUB_RUN_NUMBER（每次 workflow 运行自增，全局唯一）
        //   - 本地：GITHUB_RUN_NUMBER 不存在时退回 0，得到 1.12.2+0（仅供本地调试）
        //   这样每次出包的 versionCode 都不同，用户可直接覆盖安装，无需手改。
        val ciRunNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0
        versionCode = 51000 + ciRunNumber
        versionName = "1.12.2+$ciRunNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        ndk {
            // Keep native compilation aligned with the app's only supported ABI.
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }

    }

    buildTypes {
        val releaseSigningConfig = signingConfigs.findByName("release")

        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "玄枵")
        }
        create("clone") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".clone"
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "玄枵 Clone")
        }
        create("nightly") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    applicationVariants.all {
        if (buildType.name == "nightly") {
            outputs.all {
                val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                output.outputFileName = "app-nightly.apk"
            }
        }
        if (buildType.name == "clone") {
            outputs.all {
                val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                output.outputFileName = "app-clone.apk"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    packaging {
        
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE-EPL-1.0.txt"
            excludes += "LICENSE-EPL-1.0.txt"
            excludes += "/META-INF/LICENSE-EDL-1.0.txt"
            excludes += "LICENSE-EDL-1.0.txt"
            
            // Resolve merge conflicts for document libraries
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "META-INF/versions/9/module-info.class"
            
            // Fix for duplicate Netty files
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/INDEX.LIST"
            
            // Fix for any other potential duplicate files
            pickFirsts += "**/*.so"
        }
    }
//    aaptOptions {
//        noCompress += "tflite"
//    }
}

val signRotatedReleaseApk by tasks.registering {
    description = "Signs the Release APK with the legacy V2 signer and rotated V3 signer."
    group = "distribution"
    dependsOn("packageRelease")
    doLast {
        signApkWithRotation(
            project.layout.buildDirectory
                .file("outputs/apk/release/app-release.apk")
                .get()
                .asFile,
        )
    }
}

val signRotatedNightlyApk by tasks.registering {
    description = "Signs the Nightly APK with the legacy V2 signer and rotated V3 signer."
    group = "distribution"
    dependsOn("packageNightly")
    doLast {
        signApkWithRotation(
            project.layout.buildDirectory
                .file("outputs/apk/nightly/app-nightly.apk")
                .get()
                .asFile,
        )
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(signRotatedReleaseApk)
}

tasks.matching { it.name == "assembleNightly" }.configureEach {
    finalizedBy(signRotatedNightlyApk)
}

tasks.named("preBuild") {
    dependsOn(syncMainAssets)
    dependsOn(verifyExternallyBuiltNativeLibraries)
}

tasks.matching { it.name.matches(Regex("merge.*Assets")) }.configureEach {
    dependsOn(syncMainAssets)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":dragonbones"))
    implementation(project(":terminal"))
    implementation(project(":mnn"))
    implementation(project(":llama"))
    implementation(project(":mmd"))
    implementation(project(":fbx"))
    implementation(project(":showerclient"))
    implementation(project(":quickjs"))

    // glTF runtime rendering (Filament)
    implementation(libs.filament.android)
    implementation(libs.gltfio.android)
    implementation(libs.filament.utils.android)
    implementation(libs.androidx.ui.graphics.android)
    // The only vendored artifact is the custom FFmpegKit AAR.
    implementation(files("libs/ffmpeg-kit-local.aar"))
    implementation(libs.smart.exception.common)
    implementation(libs.smart.exception.java)
    implementation(libs.androidx.runtime.android)
    implementation(libs.androidx.ui.text.android)
    implementation(libs.androidx.animation.android)
    implementation(libs.androidx.ui.android)
    implementation(libs.androidx.activity.ktx)

    // Desugaring support for modern Java APIs on older Android
    coreLibraryDesugaring(libs.desugar.jdk)

    // ML Kit - 文本识别
    implementation(libs.mlkit.text.recognition)
    // ML Kit - 多语言识别支持
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.korean)
    implementation(libs.mlkit.text.devanagari)
    
    implementation(libs.zxing.core)
    
    // diff
    implementation(libs.java.diff.utils)
    
    // APK解析和修改库
    implementation(libs.android.apksig) // APK签名工具
    implementation(libs.sable.axml) // 用于Android二进制XML的读写
    implementation(libs.zipalign.java) // 用于处理ZIP文件对齐
    
    // ZIP处理库 - 用于APK解压和重打包
    implementation(libs.commons.compress)
    implementation(libs.commons.io) // 添加Apache Commons IO
    
    // XML处理
    implementation(libs.androidx.core.ktx)
    
    // libsu - root access library
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.nio)
    
    // Add missing SVG support
    implementation(libs.androidsvg)
    
    // Add missing GIF support for Markwon
    
    // Image Cropper for background image cropping
    implementation(libs.image.cropper)
    
    // Media3（原 ExoPlayer，2.19.1 已停止维护、坐标已从 Maven Central 移除）
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    implementation(libs.media3.ui)
    
    // Material 3 Window Size Class
    implementation(libs.material3.window)
    
    implementation(libs.androidx.webkit)

    // Document conversion libraries
    implementation(libs.itextg)
    implementation(libs.pdfbox)
    
    // 图片加载库
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    // Coil 3 起 OkHttp 网络栈与 HTTP 缓存头策略改为独立 artifact
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.network.cache.control)
    
    // LaTeX rendering libraries
    implementation(libs.jlatexmath)
    
    // Base Android dependencies
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlin.reflect)
    
    // UUID dependencies
    
    // Gson for JSON parsing
    implementation(libs.gson)

    // HJSON dependency for human-friendly JSON parsing
    implementation(libs.hjson)

    // 中文分词库 - Jieba Android
    implementation(libs.jieba)

    // 向量搜索库 - 轻量级实现，适合Android
    implementation(libs.hnswlib.core)
    implementation(libs.hnswlib.utils)
    
    // 用于向量嵌入的TF Lite (如果需要自定义嵌入)
    
    // ONNX Runtime for Android - 支持更强大的多语言Embedding模型
    implementation(libs.onnxruntime.android)

    // Room 数据库
    implementation(libs.room.runtime)
    implementation(libs.room.ktx) // Kotlin扩展和协程支持
    kapt(libs.room.compiler) // 使用kapt代替ksp

    // ObjectBox
    implementation(libs.objectbox.kotlin)
    kapt(libs.objectbox.processor)
    // commons-compress 统一由「ZIP处理库」区块的 libs.commons.compress (1.25.0) 提供
    implementation(libs.junrar)

    // Compose dependencies - use BOM for version consistency
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    // Use BOM version for all Compose dependencies
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.core)

    // Navigation Compose
    implementation(libs.navigation.compose)

    // Shizuku dependencies
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Tasker Plugin Library
    implementation(libs.taskerpluginlibrary)
    
    // WorkManager for scheduled workflows
    implementation(libs.work.runtime.ktx)

    // Network dependencies
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.jsoup)

    // DataStore dependencies
    implementation(libs.datastore.preferences)
    implementation(libs.datastore.preferences.core)

    // Debug dependencies
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Test dependencies
    testImplementation(libs.junit)
    // JVM tests need a real implementation because Android's org.json methods are throwing stubs.
    testImplementation(libs.json.jvm)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))

    // Apache POI - for Document processing (DOC, DOCX, etc.)
    implementation(libs.poi)
    implementation(libs.poi.ooxml)
    implementation(libs.poi.scratchpad)

    // Color picker for theme customization
    implementation(libs.colorpicker)
    implementation(libs.backdrop)
    implementation(libs.liquid)
    
    // NanoHTTPD for local web server
    implementation(libs.nanohttpd)

    // Android 测试依赖（junit / androidx.junit / espresso / compose-bom 已在上方 "Test dependencies" 声明）
    androidTestImplementation(libs.ui.test.junit4)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    
    // 协程测试依赖
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.coroutines.test)
    
    // 模拟测试框架 - 保留现有的 mockito 并新增 mockk
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.mockito.android)
    
    // // 新增的测试依赖 - mockk 和 kotlin-test
    // testImplementation(libs.mockk)
    // testImplementation(libs.ktor.server.test.host)
    // testImplementation(libs.kotlinx.coroutines.debug)
    // androidTestImplementation(libs.mockk)
    
    implementation(libs.reorderable)

    // Swipe to reveal actions
    implementation(libs.swipe)

    // Coroutine
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation(libs.mcp.sdk.client)
    implementation(libs.ktor.client.okhttp)
    
    // Exclude bcprov-jdk15to18 from all configurations to avoid duplicate classes
    configurations.all {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }

    // Security
    implementation(libs.security.crypto)
    
    // BouncyCastle - explicitly include jdk18on version to avoid conflicts
    implementation(libs.bouncycastle.bcprov)

    implementation(libs.okhttp.logging.interceptor)

    // Glance for Widgets (Compose for Widgets)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
}

