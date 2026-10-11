plugins {
    alias(libs.plugins.noveldokusha.android.library.nohilt)
}

android {
    namespace = "my.noveldokusha.tooling.native_scraper"

    defaultConfig {
        externalNativeBuild {
            cmake {
                cFlags("-O3", "-std=c11")
                cppFlags("-O3", "-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(projects.core)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.test.junit)
}
