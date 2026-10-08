plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

val releaseSigningVariables = listOf(
    "FOLIO_RELEASE_STORE_FILE",
    "FOLIO_RELEASE_STORE_PASSWORD",
    "FOLIO_RELEASE_KEY_ALIAS",
    "FOLIO_RELEASE_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningVariables.associateWith { name ->
    System.getenv(name)?.takeIf { it.isNotBlank() }
}
val suppliedReleaseSigningVariables = releaseSigningValues.filterValues { it != null }.keys
check(suppliedReleaseSigningVariables.isEmpty() || suppliedReleaseSigningVariables.size == releaseSigningVariables.size) {
    val missing = releaseSigningVariables.filterNot(suppliedReleaseSigningVariables::contains)
    "Release signing is only configured when all four FOLIO_RELEASE_* variables are set. Missing: ${missing.joinToString()}"
}

val releaseStoreFile = releaseSigningValues["FOLIO_RELEASE_STORE_FILE"]?.let { configuredPath ->
    rootProject.file(configuredPath).canonicalFile.also { storeFile ->
        val repositoryRoot = rootProject.projectDir.canonicalFile.toPath()
        check(!storeFile.toPath().startsWith(repositoryRoot)) {
            "FOLIO_RELEASE_STORE_FILE must point outside the repository."
        }
        check(storeFile.isFile && storeFile.canRead()) {
            "FOLIO_RELEASE_STORE_FILE does not point to a readable file."
        }
    }
}

val folioVersion = "0.6.7-beta.3"

/**
 * The profile is recorded from the "fast" build ("Folio Dev", debug-signed) because the release APK can't be
 * installed over the signed Folio on a test phone, and merged into main so the release build ships it.
 */
baselineProfile {
    mergeIntoMain = true
}

/**
 * The two build types the baseline profile plugin adds get their own application id so they install beside the
 * real Folio instead of trying to replace it: the phone used for recording runs a release-signed Folio, which a
 * locally signed build of the same id can't update. A profile is a list of classes and methods, so recording it
 * under another id changes nothing about what ends up in the release APK.
 */
androidComponents {
    onVariants { variant ->
        if (variant.buildType == "nonMinifiedRelease" || variant.buildType == "benchmarkRelease") {
            variant.applicationId.set("com.mccal.folio.profile")
        }
    }
}

android {
    namespace = "com.mccal.folio"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mccal.folio"
        minSdk = 31
        targetSdk = 36
        // Semantic version; see CHANGELOG.md and docs/adr/0007-release-trains.md.
        //   versionCode = (MAJOR * 10000 + MINOR * 100 + PATCH) * 10 + HOTFIX
        // The last digit is why a fix on top of a release can exist at all: 0.6.7 is 6070 and 0.6.7.1 is 6071, so a
        // fix never has to take the number the next feature release wanted, which is what went wrong on 23 Sep 2026.
        // Pre-releases share their release's version code. Codes only ever rise.
        versionName = folioVersion
        versionCode = folioVersion.substringBefore('-').split('.').map(String::toInt).let { parts ->
            require(parts.size in 3..4) { "folioVersion needs MAJOR.MINOR.PATCH, and may add .HOTFIX: was $folioVersion" }
            val hotfix = parts.getOrElse(3) { 0 }
            require(hotfix in 1..9 || parts.size == 3) { "the hotfix digit runs 1 to 9: was $folioVersion" }
            (parts[0] * 10000 + parts[1] * 100 + parts[2]) * 10 + hotfix
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseSigningValues.getValue("FOLIO_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("FOLIO_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("FOLIO_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["appLabel"] = "folio_fork"
        }
        // Optimized like release (R8, no debuggable JIT slowdown) but signed with the local debug key, so it
        // installs over a debug build and keeps Folio's data. Use this to judge real smoothness on the phone.
        create("fast") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            // Its own app ("Folio Dev") so test builds install next to the signed release instead of over it.
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Folio Dev"
        }
        // Same app id as "fast", so one build installs over the other and Folio Dev keeps its layout and settings.
        // Only the name differs, because that is the one place you can tell them apart on the phone, and it matters:
        // a debug build is far slower, so frame numbers taken on one mean nothing next to numbers from the other.
        getByName("debug") {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Folio Debug"
        }
    }
    // "fast" uses release's no-op tracing/diagnostic sources.
    sourceSets {
        getByName("fast") { kotlin.directories.add("src/release/java"); res.directories.add("src/dev/res") }
        // Folio Dev (debug and fast builds) gets an amber icon so it's easy to tell apart from the release.
        getByName("debug") { res.directories.add("src/dev/res") }
    }
    buildFeatures { compose = true }
    // Robolectric needs the app's resources and manifest in unit tests.
    testOptions.unitTests.isIncludeAndroidResources = true
    // Android 13's per-app language picker: AGP builds locales_config.xml from the values-* folders a translation adds.
    androidResources { generateLocaleConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    baselineProfile(project(":baselineprofile"))
    implementation("androidx.window:window:1.5.1")
    // Installs the baseline profiles that Compose and AndroidX ship, so hot paths are compiled ahead of time.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Bundled, on-device Korean/Latin OCR and text extraction from PDFs.
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814") // real org.json for StatusStyle round-trip tests
    // Renders Compose on the JVM, so a screen can be checked without a phone attached.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
