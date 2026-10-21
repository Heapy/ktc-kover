package io.heapy.ktc.plugins.kover

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoverVersionTest {
    @Test
    fun yamlPinsMatchKoverVersion() {
        val project = checkNotNull(generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull {
                Files.isRegularFile(it.resolve("plugins/kover/plugin.yaml")) &&
                    Files.isRegularFile(it.resolve("plugins/kover/module.yaml"))
            }) { "Could not locate the Kover project files" }

        val coordinate = Regex("""org\.jetbrains\.kotlinx:kover-[A-Za-z0-9-]+:([0-9][A-Za-z0-9.+-]*)""")
        val versions = listOf("plugin.yaml", "module.yaml").flatMap { file ->
            coordinate.findAll(Files.readString(project.resolve("plugins/kover").resolve(file)))
                .map { it.groupValues[1] }
                .toList()
        }

        assertTrue(versions.isNotEmpty(), "Expected Kover artifact versions in plugin and module YAML")
        assertEquals(setOf(KOVER_VERSION), versions.toSet())
    }
}
