package com.mccal.folio

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/** The build declares the release number. */
class FolioVersionTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "CHANGELOG.md").exists() }

    private val version = Regex("""^val folioVersion = "([^"]+)"""", RegexOption.MULTILINE)
        .find(File(root, "app/build.gradle.kts").readText())?.groupValues?.get(1)

    @Test fun `the build declares a version`() {
        assertNotNull("app/build.gradle.kts should set folioVersion", version)
    }

}
