package io.heapy.ktc.kover

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoverTest {
    @Test
    fun readsAggregateCounterInsteadOfNestedClassCounter() {
        withXml("""<report><package><counter type="LINE" covered="100" missed="0"/></package><counter type="LINE" covered="2" missed="1"/></report>""") {
            assertEquals(LineCoverage(2, 1), readLineCoverage(it))
        }
    }

    @Test
    fun comparesThresholdWithoutRounding() {
        assertTrue(LineCoverage(2, 1).meets(66))
        assertFalse(LineCoverage(2, 1).meets(67))
        assertTrue(LineCoverage(1, 1).meets(50))
        assertFalse(LineCoverage(0, 0).meets(0))
        assertTrue(LineCoverage(Long.MAX_VALUE / 2, 0).meets(100))
    }

    @Test
    fun rejectsMissingDuplicateAndInvalidCounters() {
        for (body in listOf(
            "",
            """<counter type="LINE" covered="-1" missed="0"/>""",
            """<counter type="LINE" covered="9223372036854775807" missed="1"/>""",
            """<counter type="LINE" covered="1" missed="0"/><counter type="LINE" covered="1" missed="0"/>""",
        )) {
            withXml("<report>$body</report>") { assertFailsWith<IllegalArgumentException> { readLineCoverage(it) } }
        }
    }

    @Test
    fun allowsKoverDoctypeWithoutFetchingExternalDtd() {
        withXml("""<!DOCTYPE report SYSTEM "file:///nonexistent-ktc-kover-dtd"><report><counter type="LINE" covered="1" missed="0"/></report>""") {
            assertEquals(LineCoverage(1, 0), readLineCoverage(it))
        }
    }

    @Test
    fun rendersSeparateFiltersAndRejectsInjectedAgentOptions() {
        val args = agentArguments(Path.of("coverage.ic"), listOf("app.*", "other.*"), listOf("app.Generated*"))
        assertTrue("report.append=false\ninclude=app.*\ninclude=other.*\nexclude=app.Generated*\n" in args)
        assertFailsWith<IllegalArgumentException> { agentArguments(Path.of("report"), listOf("app.*\nreport.append=true"), emptyList()) }
    }

    @Test
    fun quotesSpaceContainingAgentPathForToolchain() {
        assertEquals("\"-javaagent:/my project/agent.jar\"", quoteJvmArgument("-javaagent:/my project/agent.jar"))
        assertEquals("\"a\\\"b\\\\c\"", quoteJvmArgument("a\"b\\c"))
        assertFailsWith<IllegalArgumentException> { quoteJvmArgument("path\noption") }
    }

    @Test
    fun rejectsMissingOrEntirelySkippedTestReports() {
        val dir = Files.createTempDirectory("kover-test-results-")
        val file = dir.resolve("TEST-junit.xml")
        try {
            assertFalse(hasExecutedTests(dir))
            Files.writeString(file, "<testsuite><testcase name=\"ignored\"><skipped/></testcase></testsuite>")
            assertFalse(hasExecutedTests(dir))
            Files.writeString(file, "<testsuite><testcase name=\"ran\"/></testsuite>")
            assertTrue(hasExecutedTests(dir))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(dir)
        }
    }

    private fun withXml(content: String, block: (Path) -> Unit) {
        val file = Files.createTempFile("kover-test-", ".xml")
        try {
            Files.writeString(file, content)
            block(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
