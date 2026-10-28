package com.pixelforge.agents

import org.junit.Assert.*
import org.junit.Test

class AndroidScaffoldTest {
    @Test fun packageNameIsSafe() {
        assertEquals("com.pixelforge.generated.aboutmuslim", AndroidScaffold.packageFor("About Muslim!"))
        assertEquals("com.pixelforge.generated.app123", AndroidScaffold.packageFor("123"))
        assertEquals("com.pixelforge.generated.app", AndroidScaffold.packageFor("###"))
    }

    @Test fun wantedOnlyForAndroidWithoutGradleProject() {
        assertTrue(AndroidScaffold.wanted("buat aplikasi Android sholat", emptyList()))
        assertFalse(AndroidScaffold.wanted("buat aplikasi Android", listOf("settings.gradle.kts")))
        assertFalse(AndroidScaffold.wanted("buat web statis", emptyList()))
    }

    @Test fun scaffoldHasRequiredFilesAndNoWrapper() {
        val f = AndroidScaffold.files("About Muslim")
        for (p in listOf("settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts",
            "app/src/main/AndroidManifest.xml", ".github/workflows/build-apk.yml")) assertTrue(p, p in f)
        assertTrue(f.keys.any { it.endsWith("MainActivity.kt") && "aboutmuslim" in it })
        assertTrue(f.keys.none { it.startsWith("gradlew") || it.startsWith("gradle/wrapper") })
        val wf = f.getValue(".github/workflows/build-apk.yml")
        assertTrue("gradle assembleDebug" in wf && "./gradlew" !in wf)
        assertTrue("\$(grep" in wf && "\${'$'}" !in wf)   // tanda dolar tidak boleh bocor sebagai literal Kotlin
        assertTrue("namespace = \"com.pixelforge.generated.aboutmuslim\"" in f.getValue("app/build.gradle.kts"))
    }
}
