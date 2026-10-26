import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Firma de release: ~/.bona-apks-signing/keystore.properties (fuera del repo). Sin eso, sale con la clave debug.
val keystoreProps = File(System.getProperty("user.home"), ".bona-apks-signing/keystore.properties")
    .takeIf { it.exists() }?.reader()?.use { Properties().apply { load(it) } }

android {
    namespace = "com.guardvalker.bonaapp"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        buildConfig = true
        resValues = true
    }

    // Un flavor por app: cada una es la misma cáscara WebView apuntando a su PWA.
    flavorDimensions += "app"
    productFlavors {
        fun app(name: String, id: String, label: String, url: String, color: String, orientation: String) =
            create(name) {
                dimension = "app"
                applicationId = "com.guardvalker.$id"
                resValue("string", "app_name", label)
                resValue("color", "bar_color", color)
                buildConfigField("String", "START_URL", "\"$url\"")
                buildConfigField("String", "ORIENTATION", "\"$orientation\"")
                buildConfigField("String", "APK_NAME", "\"$label.apk\"")
            }
        app("super", "bonasuper", "BonaSuper", "https://guardvalker.github.io/lista-super/", "#1e1e2e", "portrait")
        app("cuentas", "bonacuentas", "BonaCuentas", "https://guardvalker.github.io/bona-consumos/", "#1e1e2e", "portrait")
        app("gym", "bonagym", "BonaGym", "https://guardvalker.github.io/gym-app/", "#181825", "portrait")
        app("planos", "bonaplanos", "BonaPlanos", "https://guardvalker.github.io/bona-planos/", "#18222c", "any")
        app("hongolandia", "hongolandia", "Hongolandia", "https://guardvalker.github.io/hongolandia/", "#1c2433", "portrait")
    }
}
