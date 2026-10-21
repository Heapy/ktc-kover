package io.heapy.ktc.plugins.kover

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

/** Prepares instrumentation without invoking another build or compiling the consumer. */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun koverPrepare(
    @Input(inferTaskDependency = false) projectDir: Path,
    moduleName: String,
    @Input agentClasspath: Classpath,
    @Output outputDir: Path,
    // Manifest identity only; a Path input would overlap the projectDir input.
    reportDir: String,
    settings: KoverSettings,
) {
    val session = requireNotNull(coverageSessionDirectory()) {
        "Run koverPrepare through plugins/kover/coverage.main.kts."
    }
    require(settings.minimumLineCoverage in 0..100) { "minimumLineCoverage must be between 0 and 100" }
    require(settings.testTimeoutSeconds > 0) { "testTimeoutSeconds must be positive" }
    val agent = koverAgent(agentClasspath)
    prepareSession(session, projectDir, moduleName, agent, outputDir, Path.of(reportDir), settings)
}

internal fun prepareSession(
    session: Path,
    projectDir: Path,
    moduleName: String,
    agent: Path,
    outputDir: Path,
    reportDir: Path,
    settings: KoverSettings,
) {
    Files.createDirectories(outputDir)
    val binary = outputDir.resolve("coverage.ic").toAbsolutePath().normalize()
    Files.deleteIfExists(binary)
    val arguments = outputDir.resolve("agent.args")
    Files.writeString(arguments, agentArguments(binary, settings.includes, settings.excludes))
    val properties = Properties().apply {
        setProperty("module", moduleName)
        setProperty("project", projectDir.toAbsolutePath().normalize().toString())
        setProperty("report", reportDir.toAbsolutePath().normalize().toString())
        setProperty("binary", binary.toString())
        setProperty("jvmArgs", quoteJvmArgument("-javaagent:$agent=file:$arguments"))
        setProperty("testTimeoutSeconds", settings.testTimeoutSeconds.toString())
        for ((name, values) in listOf("includeTags" to settings.includeTags, "excludeTags" to settings.excludeTags)) {
            setProperty("$name.size", values.size.toString())
            values.forEachIndexed { index, value -> setProperty("$name.$index", value) }
        }
    }
    Files.newOutputStream(session.resolve("${sessionKey(moduleName)}.properties")).use {
        properties.store(it, "ktc-kover coverage session")
    }
}

internal fun coverageSessionDirectory(): Path? = System.getenv("KTC_KOVER_SESSION_DIR")?.let { value ->
    Path.of(value).also { require(it.isAbsolute && Files.isDirectory(it)) { "Invalid KTC_KOVER_SESSION_DIR" } }
}

internal fun sessionKey(moduleName: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(moduleName.toByteArray(Charsets.UTF_8))

internal fun clearReports(directory: Path) {
    Files.createDirectories(directory)
    Files.deleteIfExists(directory.resolve("coverage.ic"))
    Files.deleteIfExists(directory.resolve("coverage.xml"))
    removeTree(directory.resolve("html"))
}

internal fun validateSession(session: Path, projectDir: Path, moduleName: String, reportDir: Path): Path {
    val key = sessionKey(moduleName)
    val manifest = session.resolve("$key.properties")
    require(Files.isRegularFile(manifest)) { "No prepared coverage session for $moduleName" }
    val properties = Properties().apply { Files.newInputStream(manifest).use { load(it) } }
    require(properties.getProperty("project") == projectDir.toAbsolutePath().normalize().toString() &&
        properties.getProperty("report") == reportDir.toAbsolutePath().normalize().toString() &&
        properties.getProperty("module") == moduleName) { "Coverage session does not match this project/module/build directory" }
    require(Files.isRegularFile(session.resolve("$key.success"))) {
        "Instrumented tests did not succeed in this coverage session for $moduleName"
    }
    require(hasExecutedTests(session.resolve(key))) { "No JVM tests executed for $moduleName" }
    val binary = Path.of(requireNotNull(properties.getProperty("binary")) { "Coverage session has no binary path" })
    require(Files.isRegularFile(binary) && Files.size(binary) > 0) { "No coverage was collected for $moduleName" }
    return binary
}
