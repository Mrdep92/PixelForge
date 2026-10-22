package com.pixelforge.agents

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProtectedFilesTest {
    @Test
    fun wrapperAlwaysProtected() {
        assertNotNull(ProtectedFiles.violation("gradlew", false))
        assertNotNull(ProtectedFiles.violation("./gradlew.bat", false))
        assertNotNull(ProtectedFiles.violation("gradle/wrapper/gradle-wrapper.properties", true))
        assertNotNull(ProtectedFiles.violation("app/gradle/wrapper/gradle-wrapper.jar", false))
    }

    @Test
    fun workflowProtectedOnlyWhenItExists() {
        assertNotNull(ProtectedFiles.violation(".github/workflows/build.yml", true))
        assertNull(ProtectedFiles.violation(".github/workflows/build.yml", false))
    }

    @Test
    fun normalFilesAllowed() {
        assertNull(ProtectedFiles.violation("app/src/Main.kt", true))
        assertNull(ProtectedFiles.violation("build.gradle.kts", true))
        assertNull(ProtectedFiles.violation("docs/gradle-notes.md", false))
    }
}
