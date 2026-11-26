plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val originalAssets = layout.projectDirectory.dir("src/main/assets")
val compactAssets = layout.buildDirectory.dir("generated/compact-artwork/assets")
val artworkPacker = rootProject.layout.projectDirectory.file("scripts/compact-artwork.py")
val packArtwork by tasks.registering(Exec::class) {
    inputs.dir(originalAssets).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(artworkPacker).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("scripts/artwork-requirements.txt"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(compactAssets)
    val defaultPython = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
    commandLine(providers.gradleProperty("cuteDogPython").getOrElse(defaultPython), artworkPacker.asFile,
        originalAssets.asFile, compactAssets.get().asFile)
}

val voiceAssets = layout.buildDirectory.dir("generated/voice-input/assets")
val bundleLegacySpeech = providers.gradleProperty("cuteDogBundleLegacySpeech").map(String::toBoolean).getOrElse(false)
val prepareVoiceInput by tasks.registering(Exec::class) {
    val script = rootProject.file("scripts/prepare-voice-input.py")
    inputs.file(script)
    outputs.dir(voiceAssets)
    val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
    commandLine(providers.gradleProperty("cuteDogPython").getOrElse(python), script,
        rootProject.file(".gradle/cutedog-voice-input"), voiceAssets.get().asFile)
}

android {
    namespace = "com.cutedog.app"
    compileSdk = 35
    sourceSets.getByName("main").assets.setSrcDirs(
        if (bundleLegacySpeech) listOf(compactAssets, voiceAssets) else listOf(compactAssets)
    )
    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources/chat")
    defaultConfig {
        applicationId = "com.cutedog.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.2.1-preview.6"
        buildConfigField("boolean", "LEGACY_SPEECH", bundleLegacySpeech.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("cuteDog.compactAssets", compactAssets.get().asFile.absolutePath) }
    }
    lint { abortOnError = true }
    packaging.jniLibs.useLegacyPackaging = true
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
if (bundleLegacySpeech) apply(from = rootProject.file("scripts/pet-speech.gradle"))

tasks.named("preBuild") {
    dependsOn(packArtwork)
    if (bundleLegacySpeech) dependsOn(prepareVoiceInput)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
