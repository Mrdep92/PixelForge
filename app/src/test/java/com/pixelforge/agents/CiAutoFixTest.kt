package com.pixelforge.agents

import org.junit.Assert.*
import org.junit.Test

class CiAutoFixTest {
    private val bad = "jobs:\n  b:\n    steps:\n      - run: ./gradlew assembleDebug\n"

    @Test fun replacesWorkflowAndAddsScaffoldWhenNoGradleProject() {
        val plan = CiAutoFix.plan(mapOf(".github/workflows/gradle-build.yml" to bad, "README.md" to "x"), "About Muslim")
        assertTrue("gradle assembleDebug" in plan.getValue(".github/workflows/gradle-build.yml"))
        assertTrue("settings.gradle.kts" in plan)
        assertFalse(AndroidScaffold.WORKFLOW_PATH in plan)   // tidak ada workflow ganda
    }

    @Test fun onlyReplacesWorkflowWhenGradleProjectExists() {
        val plan = CiAutoFix.plan(mapOf(".github/workflows/x.yml" to bad, "settings.gradle.kts" to ""), "App")
        assertEquals(setOf(".github/workflows/x.yml"), plan.keys)
    }

    @Test fun noPlanWhenWrapperPresentOrWorkflowFine() {
        assertTrue(CiAutoFix.plan(mapOf(".github/workflows/x.yml" to bad, "gradle/wrapper/gradle-wrapper.jar" to ""), "A").isEmpty())
        assertTrue(CiAutoFix.plan(mapOf(".github/workflows/x.yml" to "run: gradle build"), "A").isEmpty())
    }
}
