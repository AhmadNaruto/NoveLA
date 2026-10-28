plugins {
    alias(libs.plugins.noveldokusha.android.library.nohilt)
}

android {
    namespace = "my.noveldokusha.tooling.native_scraper"

    defaultConfig {
        externalNativeBuild {
            cmake {
                cFlags(
                    "-O3",
                    "-std=c11",
                    "-flto",
                    "-fvisibility=hidden",
                    "-ffunction-sections",
                    "-fdata-sections",
                )
                cppFlags(
                    "-O3",
                    "-std=c++17",
                    "-flto",
                    "-fvisibility=hidden",
                    "-ffunction-sections",
                    "-fdata-sections",
                )
                arguments(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-flto -Wl,--gc-sections -Wl,--exclude-libs,ALL",
                )
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
