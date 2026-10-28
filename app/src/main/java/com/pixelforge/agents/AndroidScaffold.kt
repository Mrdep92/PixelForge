package com.pixelforge.agents

/**
 * Kerangka proyek Android yang sudah terbukti build di CI kita (AGP 8.7.2, Kotlin 2.0.21, Gradle 8.9, compileSdk 35).
 * Agent tidak lagi mengarang konfigurasi Gradle (penyebab kegagalan About Muslim); ia hanya menambah kode
 * di app/src/main. Ide template dari agent Termux v0.1.58, disamakan dengan versi PixelForge.
 */
object AndroidScaffold {

    private val NAME_RE = Regex("[^a-z0-9]")

    /** Nama paket aman dari nama project: huruf kecil + angka, diawali huruf. */
    fun packageFor(projectName: String): String {
        val slug = projectName.lowercase().replace(NAME_RE, "").ifEmpty { "app" }
        val safe = if (slug[0].isLetter()) slug else "app$slug"
        return "com.pixelforge.generated.${safe.take(30)}"
    }

    /** Perlu kerangka? Belum ada proyek Gradle di workspace dan konteksnya jelas Android. */
    fun wanted(contextText: String, files: List<String>): Boolean {
        if (files.any { it.substringAfterLast('/').startsWith("settings.gradle") }) return false
        return Regex("android|\\bapk\\b", RegexOption.IGNORE_CASE).containsMatchIn(contextText)
    }

    fun files(projectName: String): Map<String, String> {
        val pkg = packageFor(projectName)
        val title = projectName.trim().ifEmpty { "App" }.replace("\"", "").replace("\\", "")
        return linkedMapOf(
            "settings.gradle.kts" to """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        google()
                        mavenCentral()
                    }
                }

                rootProject.name = "app"
                include(":app")
            """.trimIndent() + "\n",

            "build.gradle.kts" to """
                plugins {
                    id("com.android.application") version "8.7.2" apply false
                    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
                    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
                }
            """.trimIndent() + "\n",

            "gradle.properties" to """
                org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
                android.useAndroidX=true
                kotlin.code.style=official
            """.trimIndent() + "\n",

            "app/build.gradle.kts" to """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                    id("org.jetbrains.kotlin.plugin.compose")
                }

                android {
                    namespace = "$pkg"
                    compileSdk = 35

                    defaultConfig {
                        applicationId = "$pkg"
                        minSdk = 26
                        targetSdk = 35
                        versionCode = 1
                        versionName = "1.0"
                    }

                    buildFeatures { compose = true }

                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                    kotlinOptions { jvmTarget = "17" }
                }

                dependencies {
                    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
                    implementation("androidx.core:core-ktx:1.13.1")
                    implementation("androidx.activity:activity-compose:1.9.3")
                    implementation("androidx.compose.ui:ui")
                    implementation("androidx.compose.material3:material3")
                }
            """.trimIndent() + "\n",

            "app/src/main/AndroidManifest.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application
                        android:label="$title"
                        android:theme="@android:style/Theme.Material.Light.NoActionBar">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent() + "\n",

            "app/src/main/java/${pkg.replace('.', '/')}/MainActivity.kt" to """
                package $pkg

                import android.os.Bundle
                import androidx.activity.ComponentActivity
                import androidx.activity.compose.setContent
                import androidx.compose.material3.MaterialTheme
                import androidx.compose.material3.Text

                class MainActivity : ComponentActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContent {
                            MaterialTheme { Text("Hello") }
                        }
                    }
                }
            """.trimIndent() + "\n",

            ".gitignore" to "build/\n.gradle/\nlocal.properties\n*.iml\n.idea/\n",

            ".github/workflows/build-apk.yml" to """
                name: Build APK

                on:
                  push:
                  workflow_dispatch:

                permissions:
                  contents: write

                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - uses: actions/setup-java@v4
                        with:
                          distribution: temurin
                          java-version: 17
                      - uses: gradle/actions/setup-gradle@v4
                        with:
                          gradle-version: 8.9
                      - name: Build debug APK
                        shell: bash
                        run: gradle assembleDebug --stacktrace 2>&1 | tee build.log
                      - name: Ringkas error build
                        if: failure()
                        shell: bash
                        run: |
                          msg=${'$'}(grep -E '^e: |error:|What went wrong|FAILED|Execution failed' -A3 build.log | head -60 | sed ':a;N;${'$'}!ba;s/\n/%0A/g' || true)
                          echo "::error title=Build errors::${'$'}msg"
                      - uses: actions/upload-artifact@v4
                        with:
                          name: app-debug-apk
                          path: app/build/outputs/apk/debug/app-debug.apk
            """.trimIndent() + "\n"
        )
    }
}
