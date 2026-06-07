import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.ksp)
}

android {
    namespace = "io.legado.plugin"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.legado.plugin"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach {
            val outputImpl = it as com.android.build.api.variant.impl.VariantOutputImpl
            val originalFileName = outputImpl.outputFileName.get()
            val newFileName = originalFileName.replace(".apk", ".apk.lnrp")
            outputImpl.outputFileName = newFileName
        }
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            listOf(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            )
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.manifests.addStaticManifestFile(
            layout.buildDirectory.file("generated/ksp/${variant.name}/resources/auto_register_manifest.xml").get().toString()
        )
    }
}

tasks.matching { it.name.matches(Regex("process(Debug|Release)MainManifest")) }.configureEach {
    val variant = name.removePrefix("process").removeSuffix("MainManifest")
    dependsOn("ksp${variant}Kotlin")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.runtime)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.foundation.layout)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.cxhttp)
    implementation(libs.okhttp3.okhttp)
    implementation(libs.okhttp3.logging.interceptor)
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.apache.commons:commons-text:1.13.1")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.jayway.jsonpath:json-path:2.10.0")
    implementation("cn.wanghaomiao:JsoupXpath:2.5.3")
    implementation("cn.hutool:hutool-crypto:5.8.22")
    implementation("org.dom4j:dom4j:2.2.0")
    implementation(libs.jsoup)

    //LNR Api
    compileOnly(libs.lightnovelreader.api)
    ksp(libs.lightnovelreader.compiler)
}

val debugHostPkg = "indi.dmzz_yyhyy.lightnovelreader.debug"
val releaseHostPkg = "indi.dmzz_yyhyy.lightnovelreader"


fun pluginApk(): File =
    File(layout.buildDirectory.asFile.get(), "outputs/apk/debug")
        .walkTopDown()
        .first {
            it.isFile && it.name.endsWith(".apk") || it.name.endsWith(".lnrp")
        }

fun installPluginTask(name: String, hostPkg: String) {
    tasks.register(name) {
        group = "plugin"
        dependsOn("assembleDebug")

        doLast {
            val adb = listOf(androidComponents.sdkComponents.adb.get().asFile.absolutePath) +
                    (System.getenv("ANDROID_SERIAL")?.let { listOf("-s", it) } ?: emptyList())
            val src = pluginApk()
            val file =
                if (src.name.endsWith(".apk")) src
                else File(src.parent, src.name.removeSuffix(".lnrp"))
                    .also { src.renameTo(it) }

            try {
                providers.exec {
                    commandLine(adb + listOf("install", "-r", "-t", file))
                }.result.get()
            } finally {
                if (file != src) file.renameTo(src)
            }

            providers.exec {
                commandLine(adb + listOf("shell", "am", "force-stop", hostPkg))
            }.result.get()

            providers.exec {
                commandLine(
                    adb + listOf(
                        "shell", "monkey", "-p", hostPkg, "-c",
                        "android.intent.category.LAUNCHER", "1"
                    )
                )
            }.result.get()
        }
    }
}

installPluginTask("runDebugHost", debugHostPkg)
installPluginTask("runReleaseHost", releaseHostPkg)

android {
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
        }
    }
}
