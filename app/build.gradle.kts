import java.util.Properties
import java.io.FileInputStream

// SENTINEL release signing. keystore.properties lives at the repo root next to the .jks file.
// When absent (CI / fresh clone), release builds fall back to the debug-signed config so the
// build pipeline never breaks just because someone doesn't have the private keys.
val keystorePropertiesFile = file("../keystore.properties")
val keystoreFile = file("sentinel.jks")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists() && keystoreFile.exists()
if (hasReleaseKeystore) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    id("com.android.application")
    // Firebase / Crashlytics intentionally stripped for the SENTINEL production build so no
    // telemetry leaks to the upstream (brian7704) Firebase project. Re-add here if you want
    // to ship to your own Firebase.
    //   id("com.google.gms.google-services")
    //   id("com.google.firebase.crashlytics")
    // Version is now explicit (defaultConfig.versionName / versionCode); the git-tag plugin
    // was removed so a shallow clone or a non-git build still works.
}

android {
    namespace = "io.opentakserver.opentakicu"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.artyllm.sentinel"
        minSdk = 26
        targetSdk = 35

        versionCode = 20000   // SENTINEL 2.0.0
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources.excludes.apply {
            add("META-INF/**")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                keyAlias = keystoreProperties["RELEASE_KEY_ALIAS"] as String
                keyPassword = keystoreProperties["RELEASE_KEY_PASSWORD"] as String
                storeFile = keystoreFile
                storePassword = keystoreProperties["RELEASE_STORE_PASSWORD"] as String
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Keep R8/ProGuard off for the first prod cut — shrinker can hide subtle reflection
            // bugs (Jackson, GoPro BLE callbacks). Turn on later once a smoke test is in place.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        getByName("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        viewBinding = true
    }
    lint {
        // GoProSource opts into Media3's @UnstableApi (UdpDataSource / ProgressiveMediaSource /
        // DefaultLoadControl). The opt-in is explicit and intentional; don't let the lint marker
        // or the release lint-vital gate break the build pipeline (we already assemble with -x lint).
        disable += "UnsafeOptInUsageError"
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {

    implementation("androidx.appcompat:appcompat:1.7.1")
    //implementation("com.google.android.material:material:1.12.0")
    // Use 1.13.0-alpha08 because it adds orientation to the Slider
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.navigation:navigation-fragment:2.9.7")
    implementation("androidx.navigation:navigation-ui:2.9.7")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("com.github.pedroSG94.RootEncoder:library:2.6.7")
    implementation("com.github.AppIntro:AppIntro:6.3.1")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.datastore:datastore-preferences-rxjava3:1.2.1")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.21.1")
    implementation("com.fasterxml.woodstox:woodstox-core:7.1.1")
    implementation("javax.xml.stream:stax-api:1.0-2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.sealwu:kscript-tools:1.0.22")
    // Firebase deps removed — see plugins block.
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:nio:6.0.0")

    implementation("com.github.pedroSG94.RootEncoder:extra-sources:2.6.7")

    // Media3 / ExoPlayer — used to ingest the GoPro's MPEG-TS preview over UDP.
    // Hand-rolling an MPEG-TS demuxer + H.264 NAL framer proved fragile (PID content-sniffing
    // false-positives, mis-framed SPS), so we delegate demux+decode to Media3's production
    // TsExtractor + MediaCodec, rendering decoded frames straight into the encoder's input
    // surface. The phone-side re-encode (chosen bitrate) is unchanged.
    // Pinned to 1.9.0 — the version pedroSG94 extra-sources transitively forces, so we match it
    // to avoid a mixed-version classpath. media3-extractor gives us TsExtractor directly.
    val media3 = "1.9.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-extractor:$media3")
    implementation("androidx.media3:media3-common:$media3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}