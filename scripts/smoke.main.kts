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
}
