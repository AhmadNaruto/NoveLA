plugins {
    alias(libs.plugins.noveldokusha.android.library.nohilt)
}

android {
    namespace = "com.github.anaruto.lua"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.test.junit)
}
