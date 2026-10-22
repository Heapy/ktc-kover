#!/usr/bin/env kotlinr
// Run with Kotlin 2.4.21+ and JDK 25: kotlinr scripts/smoke.main.kts

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

val repo = __FILE__.canonicalFile.parentFile.parentFile
val windows = System.getProperty("os.name").startsWith("Windows")

fun temporary(prefix: String, block: (File) -> Unit) {
    val directory = Files.createTempDirectory(prefix).toFile()
    try { block(directory) } finally { directory.deleteRecursively() }
}

fun write(root: File, path: String, text: String) {
    root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
}

fun copy(source: File, destination: File) {
    check(source.copyRecursively(destination, overwrite = true)) { "Could not copy $source to $destination" }
    if (!windows) {
        source.walkTopDown().filter { it.isFile && it.canExecute() }.forEach { file ->
            val target = if (source.isDirectory) destination.resolve(file.relativeTo(source)) else destination
            check(target.setExecutable(true, false)) { "Could not preserve executable permission: $target" }
        }
    }
}

data class CommandResult(val exitCode: Int, val output: String)
fun command(directory: File, arguments: List<String>, environment: Map<String, String?> = emptyMap(), timeout: Long = 600): CommandResult {
    val log = Files.createTempFile("ktc-command-", ".log").toFile()
    try {
        val process = ProcessBuilder(arguments).directory(directory).redirectErrorStream(true).redirectOutput(log).apply {
            environment.forEach { (key, value) -> if (value == null) environment().remove(key) else environment()[key] = value }
        }.start()
        try {
            check(process.waitFor(timeout, TimeUnit.SECONDS)) { "Timed out: $arguments\n${log.readText()}" }
            return CommandResult(process.exitValue(), log.readText())
        } finally {
            if (process.isAlive) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly().waitFor()
            }
        }
    } finally { log.delete() }
}

fun toolchain(project: File, vararg arguments: String, succeeds: Boolean = true, diagnostic: String? = null): String {
    val wrapper = project.resolve(if (windows) "kotlin.bat" else "kotlin").absolutePath
    val invocation = if (windows) listOf("cmd.exe", "/c", wrapper) else listOf("sh", wrapper)
    val result = command(project, invocation + arguments)
    check((result.exitCode == 0) == succeeds) { "Unexpected exit ${result.exitCode}: ${arguments.toList()}\n${result.output}" }
    check(diagnostic == null || diagnostic in result.output) { "Missing diagnostic $diagnostic:\n${result.output}" }
    println("PASS: ${arguments.joinToString(" ")} (${if (succeeds) "success" else "expected failure"})")
    return result.output
}

temporary("ktc kover smoke ") { temporary ->
    val project = temporary.resolve("consumer")
    val excluded = setOf(".git", "build", ".idea", "__pycache__")
    repo.walkTopDown().onEnter { it.name !in excluded }.filter { it.isFile }.forEach {
        copy(it, project.resolve(it.relativeTo(repo)))
    }
    val module = project.resolve("example/module.yaml")
    val originalModule = module.readText()
    val test = project.resolve("example/test/GreeterTest.kt")
    val originalTest = test.readText()
    toolchain(project, "check", "koverCheck", "-m", "example")
    val reports = project.resolve("build/tasks").listFiles().orEmpty().map { it.resolve("coverage.xml") }.filter { it.isFile }
    check(reports.size == 1) { reports }
    val xml = reports.single()
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }
    val document = factory.newDocumentBuilder().parse(xml)
    val children = document.documentElement.childNodes
    val counter = (0 until children.length).map { children.item(it) }.filterIsInstance<org.w3c.dom.Element>()
        .single { it.tagName == "counter" && it.getAttribute("type") == "LINE" }
    check(counter.getAttribute("covered").toInt() > 0 && counter.getAttribute("missed").toInt() > 0)
    val html = xml.parentFile.resolve("html")
    check(html.resolve("index.html").isFile)
    module.writeText(originalModule.replace("minimumLineCoverage: 50", "minimumLineCoverage: 100"))
    toolchain(project, "check", "koverCheck", "-m", "example", succeeds = false, diagnostic = "below 100%")
    module.writeText(originalModule)
    test.writeText(originalTest.replace("\"Hello, Kotlin!\"", "\"wrong expectation\""))
    toolchain(project, "do", "koverReport", "-m", "example", succeeds = false, diagnostic = "Instrumented tests failed")
    check(!xml.exists()) { "A failed run retained a stale report" }
    check(!html.exists())
    test.writeText(originalTest)
    module.writeText(originalModule + "    includeTags: [\"missing-tag\"]\n")
    toolchain(project, "do", "koverReport", "-m", "example", succeeds = false, diagnostic = "no tests were discovered")
    check(!xml.exists())
    module.writeText(originalModule)
    toolchain(project, "check", "koverCheck", "-m", "example")
    println("Coverage smoke passed: real reports, threshold, failed tests, empty selection, recovery, paths with spaces.")

    // The sequential runner must execute tests once and reuse both main and test compilation outputs.
    val sharedBuild = project.resolve("coverage build")
    val runs = project.resolve("test-runs.txt")
    val countedTest = originalTest.replace("fun greetsByName() {", """
        fun greetsByName() {
            java.io.File("${runs.path.replace("\\", "\\\\")}").appendText("run\n")
    """.trimIndent())
    test.writeText(countedTest)
    fun coverage(succeeds: Boolean = true, diagnostic: String? = null, extra: List<String> = emptyList()): String {
        val result = command(project, listOf("kotlinr", "plugins/kover/coverage.main.kts", "-m", "example",
            "--build-dir", sharedBuild.path) + extra)
        check((result.exitCode == 0) == succeeds) { "Unexpected coverage exit ${result.exitCode}:\n${result.output}" }
        check(diagnostic == null || diagnostic in result.output) { "Missing $diagnostic:\n${result.output}" }
        println("PASS: sequential coverage (${if (succeeds) "success" else "expected failure"})")
        return result.output
    }
    coverage()
    check(runs.readLines().size == 1) { "Tests ran more than once" }
    check(sharedBuild.walkTopDown().none { it.isDirectory && it.name == "test-build" }) { "Created a nested test build" }
    val compiled = sharedBuild.resolve("artifacts/CompiledJvmArtifact").walkTopDown()
        .filter { it.name in setOf("Greeter.class", "GreeterTest.class") }
        .associate { it.toPath() to Files.getLastModifiedTime(it.toPath()) }
    check(compiled.size == 2) { "Expected main and test classes in shared build: $compiled" }
    coverage()
    check(runs.readLines().size == 2) { "Tests were skipped or duplicated on repeat invocation" }
    check(compiled.all { (path, stamp) -> Files.getLastModifiedTime(path) == stamp }) { "Unchanged classes were recompiled" }
    val sharedXml = sharedBuild.resolve("tasks/_example_koverReport@kover/coverage.xml")
    val sharedHtml = sharedXml.parentFile.resolve("html")
    check(sharedXml.isFile && sharedHtml.resolve("index.html").isFile)
    val preparedBinary = sharedBuild.resolve("tasks/_example_koverPrepare@kover/coverage.ic")
    check(preparedBinary.isFile && preparedBinary.length() > 0) { "Preparation must own the coverage binary" }
    check(!sharedXml.parentFile.resolve("coverage.ic").exists()) { "Session reporting wrote a binary into its output directory" }
    module.writeText(originalModule.replace("minimumLineCoverage: 50", "minimumLineCoverage: 100"))
    coverage(succeeds = false, diagnostic = "below 100%")
    check(sharedXml.isFile) { "Threshold failure should retain reports" }
    module.writeText(originalModule)
    test.writeText(countedTest.replace("\"Hello, Kotlin!\"", "\"wrong expectation\""))
    coverage(succeeds = false, diagnostic = "Coverage command failed")
    check(!sharedXml.exists() && !sharedHtml.exists()) { "Failed tests retained stale reports" }
    test.writeText(countedTest)
    module.writeText(originalModule + "    includeTags: [\"missing-tag\"]\n")
    coverage(succeeds = false, diagnostic = "no tests were discovered")
    check(!sharedXml.exists())
    module.writeText(originalModule)
    coverage()
    test.writeText(countedTest.replace("fun greetsByName() {", "fun greetsByName() { Thread.sleep(10_000)"))
    coverage(succeeds = false, diagnostic = "timed out", extra = listOf("--test-timeout-seconds", "1"))
    check(!sharedXml.exists() && !sharedHtml.exists()) { "Timeout retained stale reports" }
    test.writeText(countedTest)
    coverage()
    val otherReports = listOf("reports/example/js/TEST-platform.xml", "reports/uncovered/jvm/TEST-other.xml")
    otherReports.forEach { write(sharedBuild, it, "prior test diagnostics\n") }
    copy(project.resolve("example"), project.resolve("second"))
    val projectYaml = project.resolve("project.yaml")
    projectYaml.writeText(projectYaml.readText().replace("  - example", "  - example\n  - second"))
    val previousRuns = runs.readLines().size
    coverage(extra = listOf("-m", "second"))
    check(runs.readLines().size == previousRuns + 2) { "Multi-module coverage skipped or duplicated tests" }
    check(sharedBuild.resolve("tasks/_second_koverReport@kover/coverage.xml").isFile)
    for (name in listOf("example", "second")) {
        val junit = sharedBuild.resolve("tasks/_${name}_koverPrepare@kover/test-results/TEST-junit-jupiter.xml")
        check(junit.isFile) { "Missing retained JUnit report for $name after session cleanup" }
        val suite = factory.newDocumentBuilder().parse(junit).documentElement
        check(suite.getAttribute("tests").toInt() > 0 && suite.getAttribute("failures").toInt() == 0) {
            "Expected successful test diagnostics for $name"
        }
        check(sharedBuild.resolve("reports/$name/jvm/TEST-junit-jupiter.xml").readText() == junit.readText()) {
            "Shared and retained JUnit reports differ for $name"
        }
    }
    otherReports.forEach { path ->
        check(sharedBuild.resolve(path).readText() == "prior test diagnostics\n") { "Deleted unrelated test report: $path" }
    }
    println("Sequential coverage smoke passed: one test run, reused compilation, custom build path, failures, timeout, recovery and multi-module report retention.")
}
