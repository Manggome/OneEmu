import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

fun gitCommitCount(): Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

// versionName is 0.1.<commit count>; the updater compares this against the GitHub release tag.
val commitCount = (findProperty("oneemu.versionCode") as String?)?.toInt() ?: gitCommitCount()
val appVersionName = (findProperty("oneemu.versionName") as String?) ?: "0.1.$commitCount"

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.manggome.oneemu"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.manggome.oneemu"
        minSdk = 26
        targetSdk = 36
        versionCode = commitCount
        versionName = appVersionName
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_PLATFORM=android-26")
                cppFlags += listOf("-std=c++20", "-fexceptions", "-frtti")
            }
        }
        buildConfigField("String", "GITHUB_REPO", "\"Manggome/OneEmu\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
            else signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.get().asFile.resolve("generated/coreassets"))
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true } // extract cores to disk so dlopen() by path works
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*")
    }
}

// Copy cores/<id>/core.json (and the optional cores/<id>/assets/ folder) into APK assets so the
// app knows which cores it ships and what each needs at runtime.
val syncCoreAssets by tasks.registering(Sync::class) {
    val coresDir = rootProject.file("cores")
    into(layout.buildDirectory.dir("generated/coreassets"))
    coresDir.listFiles { f -> f.isDirectory && f.resolve("core.json").exists() }?.forEach { dir ->
        from(dir.resolve("core.json")) { into("cores"); rename { "${dir.name}.json" } }
        val assets = dir.resolve("assets")
        if (assets.isDirectory) from(assets) { into("coreassets/${dir.name}") }
    }
}
tasks.named("preBuild") { dependsOn(syncCoreAssets) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.oboe)
}
