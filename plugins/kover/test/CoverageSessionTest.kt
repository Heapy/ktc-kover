package io.heapy.ktc.plugins.kover

import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverageSessionTest {
    @Test
    fun rejectsMissingOrMismatchedManifest() = withSession {
        Files.delete(manifest)
        rejects("No prepared coverage session") { validate() }
        saveManifest()
        rejects("does not match") { validateSession(session, root, module, report) }
        rejects("does not match") { validateSession(session, project, module, root) }
        properties.setProperty("module", "another-module")
        saveManifest()
        rejects("does not match") { validate() }
    }

    @Test
    fun collectedBinaryCannotReplaceSuccessfulExecutedTests() = withSession {
        // Everything else is valid, including a nonempty binary from a test process.
        Files.delete(success)
        rejects("did not succeed") { validate() }
        Files.writeString(success, "success")
        Files.delete(testResults)
        rejects("No JVM tests executed") { validate() }
        Files.writeString(testResults, "<testsuite><testcase><skipped/></testcase></testsuite>")
        rejects("No JVM tests executed") { validate() }
    }

    @Test
    fun rejectsMissingOrEmptyCollectionAfterSuccessfulTests() = withSession {
        Files.delete(binary)
        rejects("No coverage was collected") { validate() }
        Files.writeString(binary, "")
        rejects("No coverage was collected") { validate() }
    }

    @Test
    fun reportOutputCleanupPreservesPreparedCollection() = withSession {
        removeTree(report)
        clearReports(report)
        assertEquals(binary, validate())
    }

    @Test
    fun preparedManifestTagsRoundTripThroughRunnerDecoding() = withSession {
        for ((includes, excludes) in listOf(
            listOf("fast", "integration & !slow", "tag=with:punctuation") to listOf("quarantined", "slow | flaky"),
            emptyList<String>() to emptyList<String>(),
        )) {
            val settings = object : KoverSettings {
                override val includeTags = includes
                override val excludeTags = excludes
            }
            prepareSession(session, project, module, root.resolve("agent.jar"), binary.parent, report, settings)
            val loaded = Properties().apply { Files.newInputStream(manifest).use { load(it) } }
            assertEquals(project.toAbsolutePath().normalize().toString(), loaded.getProperty("project"))
            assertFalse(Files.exists(session.resolve("project")))
            // Decode the stored properties using the runner's indexed tag contract.
            val arguments = mutableListOf<String>()
            for ((name, flag) in listOf("includeTags" to "--include-tag", "excludeTags" to "--exclude-tag")) {
                repeat(loaded.getProperty("$name.size").toInt()) { tag ->
                    arguments += listOf(flag, loaded.getProperty("$name.$tag"))
                }
            }
            assertEquals(includes.flatMap { listOf("--include-tag", it) } +
                excludes.flatMap { listOf("--exclude-tag", it) }, arguments)
        }
    }

    @Test
    fun reportingClearsReportsWithoutDeletingCompiledOutputs() {
        val root = Files.createTempDirectory("coverage-clear-test-")
        try {
            Files.writeString(root.resolve("coverage.ic"), "old")
            Files.writeString(root.resolve("coverage.xml"), "old")
            Files.writeString(Files.createDirectory(root.resolve("html")).resolve("index.html"), "old")
            Files.writeString(root.resolve("compiled.class"), "preserve")
            clearReports(root)
            assertFalse(Files.exists(root.resolve("coverage.ic")))
            assertFalse(Files.exists(root.resolve("coverage.xml")))
            assertFalse(Files.exists(root.resolve("html")))
            assertTrue(Files.exists(root.resolve("compiled.class")))
        } finally {
            removeTree(root)
        }
    }

    private class SessionFixture {
        val root = Files.createTempDirectory("coverage-session-test-")
        val project = Files.createDirectory(root.resolve("project"))
        val report = Files.createDirectory(root.resolve("report"))
        val session = Files.createDirectory(root.resolve("session"))
        val binary = Files.createDirectory(root.resolve("prepare")).resolve("coverage.ic")
        val module = "module with/slashes"
        val key = sessionKey(module)
        val manifest = session.resolve("$key.properties")
        val success = session.resolve("$key.success")
        val testResults = Files.createDirectory(session.resolve(key)).resolve("TEST-results.xml")
        val properties = Properties().apply {
            setProperty("module", module)
            setProperty("project", project.toString())
            setProperty("report", report.toString())
            setProperty("binary", binary.toString())
        }

        init {
            assertFalse('/' in key)
            saveManifest()
            Files.writeString(binary, "binary")
            Files.writeString(success, "success")
            Files.writeString(testResults, "<testsuite><testcase/></testsuite>")
            assertEquals(binary, validate())
        }

        fun saveManifest() = Files.newOutputStream(manifest).use { properties.store(it, null) }
        fun validate() = validateSession(session, project, module, report)
    }

    private fun withSession(block: SessionFixture.() -> Unit) {
        val fixture = SessionFixture()
        try { fixture.block() } finally { removeTree(fixture.root) }
    }

    private fun rejects(message: String, block: () -> Unit) {
        val failure = assertFailsWith<IllegalArgumentException>(block = block)
        assertTrue(message in failure.message.orEmpty(), "Expected '$message', got '${failure.message}'")
    }

}
