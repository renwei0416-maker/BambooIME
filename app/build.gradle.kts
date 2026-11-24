/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    id("com.osfans.trime.app-convention")
    id("com.osfans.trime.native-app-convention")
    id("com.osfans.trime.data-checksums")
    id("com.osfans.trime.native-cache-hash")
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

// 内置手写模型（7 MB）默认打进 APK；-PbundleHandwritingModel=false 出「无模型」版，
// 由 App 在首次使用手写时自行下载（见 HandwritingModelStore）。
val bundleHandwritingModel =
    (project.findProperty("bundleHandwritingModel") as String?)?.toBoolean() ?: true

// 内置拍照识字模型（PP-OCRv5 det + rec，约 21.5 MB）默认打进 APK；
// -PbundleOcrModel=false 出「无 OCR 模型」版（入口会提示缺模型）。
val bundleOcrModel =
    (project.findProperty("bundleOcrModel") as String?)?.toBoolean() ?: true

// 内置文曲星诗词包（4 个包共约 4.3 MB）；-PbundleWenquxingPacks=false 出「无内置包」版。
val bundleWenquxingPacks =
    (project.findProperty("bundleWenquxingPacks") as String?)?.toBoolean() ?: true

// 内置外语学习英文词库（CC-CEDICT 转 .bge，8.1 MB）；日语词库走下载，不打进包。
// -PbundleGlossaryEn=false 出「无内置词库」版（设置页仍可自己下载/导入）。
val bundleGlossaryEn =
    (project.findProperty("bundleGlossaryEn") as String?)?.toBoolean() ?: true

// 内置万象拼音 Pro 方案包（小鹤双拼 + 直接辅助码，非 custom 部分 84.7 MB，压缩后约 30 MB）；
// -PbundleWanxiangPro=false 出「不带万象」的精简版（设置页仍会显示入口，但会提示未内置）。
val bundleWanxiangPro =
    (project.findProperty("bundleWanxiangPro") as String?)?.toBoolean() ?: true

android {
    namespace = "com.osfans.trime"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    sourceSets {
        getByName("main") {
            if (bundleHandwritingModel) {
                assets.srcDir("src/main/handwriting-assets")
            }
            if (bundleOcrModel) {
                assets.srcDir("src/main/ocr-assets")
            }
            if (bundleWenquxingPacks) {
                assets.srcDir("src/main/wenquxing-assets")
            }
            if (bundleGlossaryEn) {
                assets.srcDir("src/main/glossary-assets")
            }
            if (bundleWanxiangPro) {
                // 一个根目录下两个子目录：wanxiang-pro（官方 77 个文件）与
                // wanxiang-t9（九键 Pro 需要的 5 个文件），释放时叠进同一个包目录。
                assets.srcDir("src/main/schema-assets")
            }
        }
    }

    defaultConfig {
        applicationId = "com.bambooime"
        minSdk = 29
        targetSdk = 37
        versionCode = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE).toInt() * 100 + ((project.findProperty("buildVersionCodeSeq") as String?)?.toIntOrNull() ?: 1)
        versionName = "3.9.3"

        multiDexEnabled = true
        buildConfigField("String", "BUILDER", "\"${project.builder}\"")
        buildConfigField("long", "BUILD_TIMESTAMP", project.buildTimestamp)
        buildConfigField("String", "BUILD_COMMIT_HASH", "\"${project.buildCommitHash}\"")
        buildConfigField("String", "BUILD_GIT_REPO", "\"${project.buildGitRepo}\"")
        buildConfigField("String", "BUILD_VERSION_NAME", "\"${project.buildVersionName}\"")
        buildConfigField("String", "QNN_VARIANT", "\"${project.qnnVariant ?: ""}\"")
        buildConfigField("boolean", "BUNDLED_HANDWRITING_MODEL", "$bundleHandwritingModel")
        buildConfigField("boolean", "BUNDLED_OCR_MODEL", "$bundleOcrModel")
    // 诊断日志开关。写死成字面量而不是用 BuildConfig.DEBUG（那是
    // Boolean.parseBoolean("true")，不是编译期常量），R8 才能在 release 里
    // 把日志分支整段折叠掉，正式包既不写文件也不留日志字符串。
    buildConfigField("boolean", "BAMBOO_LOG_TO_FILE", "true")
        buildConfigField(
            "boolean",
            "BUNDLED_WENQUXING_PACKS",
            "$bundleWenquxingPacks",
        )
        buildConfigField("boolean", "BUNDLED_GLOSSARY_EN", "$bundleGlossaryEn")
        buildConfigField("boolean", "BUNDLED_WANXIANG_PRO", "$bundleWanxiangPro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                project.signKeyFile?.let {
                    signingConfigs.create("release") {
                        storeFile = it
                        storePassword = project.signKeyStorePwd
                        keyAlias = project.signKeyAlias
                        keyPassword = project.signKeyPwd
                        enableV1Signing = true
                        enableV2Signing = true
                        enableV3Signing = true
                        enableV4Signing = true
                    }
                }

            buildConfigField("boolean", "BAMBOO_LOG_TO_FILE", "false")
            resValue("string", "trime_app_name", "@string/app_name_release")
        }
        debug {
            applicationIdSuffix = ".debug"

            resValue("string", "trime_app_name", "@string/app_name_debug")
        }
        all {
            // remove META-INF/version-control-info.textproto
            @Suppress("UnstableApiUsage")
            vcsInfo.include = false
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // hack workaround lint gradle 8.0.2
    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/*.version",
                    "/META-INF/*.kotlin_module",
                    "/META-INF/androidx/**",
                    "/META-INF/com/android/build/gradle/app-metadata.properties",
                    "/DebugProbesKt.bin",
                    "/kotlin-tooling-metadata.json",
                )
        }
    }
}

aboutLibraries {
    collect {
        configPath.set(file("licenses").takeIf { it.exists() })
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
        includePlatform.set(false)
    }
    export {
        excludeFields.set(
            setOf("generated", "developers", "organization", "scm", "funding", "content"),
        )
    }
}

base {
    // https://www.norio.be/blog/archivesBaseName-removed-from-gradle9.html
    archivesName = "${android.defaultConfig.applicationId}-$buildVersionName"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        // https://youtrack.jetbrains.com/issue/KT-55947
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    ksp(project(":codegen"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.flexbox)
    implementation(libs.bravh)
    implementation(libs.timber)
    implementation(libs.xxpermissions)
    implementation(libs.kodein.di)
    implementation(libs.snakeyaml)
    implementation(libs.okhttp)
    // 拍照识字（BambooOcr）：预览 + 取帧
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.systemservices)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    implementation(fileTree("libs") { include("*.aar") })
    implementation(libs.iconics.core)
    implementation(libs.community.material.typeface) {
        artifact { type = "aar" }
    }
    implementation(libs.androidsvg.aar)
    implementation(libs.sora.editor)
    implementation(libs.gson)
    implementation(libs.jcodings)
    implementation(libs.joni)
    implementation(libs.snakeyaml.engine)
    implementation(libs.eclipse.jdt.annotation)
    implementation(libs.documentfile)
    implementation(libs.splitties.resources)
    implementation(libs.coordinatorlayout)

    // Testing
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    }
}

// ---- BambooIME: librime plugin link ---------------------------------------
// librime's plugins/CMakeLists.txt globs its own subdirectories, so every plugin
// needs a sibling link there. The link is not tracked by this repository
// (app/src/main/jni/librime is a submodule), hence it is recreated on each build.
val librimePluginLink =
    layout.projectDirectory.dir("src/main/jni/librime/plugins/bamboo-nnlm").asFile

tasks.matching { it.name.startsWith("preBuild") || it.name.startsWith("externalNativeBuild") }
    .configureEach {
        doFirst {
            if (!librimePluginLink.exists()) {
                val target = layout.projectDirectory
                    .dir("src/main/jni/librime-plugins/bamboo-nnlm").asFile
                librimePluginLink.parentFile.mkdirs()
                val ok = runCatching {
                    ProcessBuilder("cmd", "/c", "mklink", "/J",
                        librimePluginLink.absolutePath, target.absolutePath)
                        .redirectErrorStream(true)
                        .start().waitFor() == 0
                }.getOrDefault(false)
                if (!ok) logger.warn("bamboo-nnlm: failed to create plugin link")
            }
        }
    }

// ---- BambooIME: Rust inference (optional, run manually) -------------------
// Not wired into assembleDebug/Release: builds still work without Rust installed.
tasks.register<Exec>("buildWxAsrInfer") {
    group = "bamboo"
    description = "Builds libbamboo_wxasr_infer.so with cargo-ndk (arm64-v8a)"
    workingDir = layout.projectDirectory.dir("src/main/rust").asFile
    val jniOut = layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath
    doFirst {
        val cargoHome = System.getenv("USERPROFILE") + "\\.cargo\\bin"
        environment("PATH", cargoHome + File.pathSeparator + (System.getenv("PATH") ?: ""))
        val ndk = System.getenv("ANDROID_NDK_HOME")
            ?: (System.getenv("LOCALAPPDATA") + "\\Android\\Sdk\\ndk\\29.0.14206865")
        environment("ANDROID_NDK_HOME", ndk)
    }
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-P", "24",
        "-o", jniOut,
        "build", "--release",
    )
}