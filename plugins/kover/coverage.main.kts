#!/usr/bin/env kotlinr

/** Run from the consumer project: kotlinr plugins/kover/coverage.main.kts [-m module] [options]. */

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.concurrent.TimeUnit

val usage = """
Usage: kotlinr plugins/kover/coverage.main.kts [options]
  -m, --module NAME                 Cover this module (repeatable; default: all enabled modules)
  --project-dir PATH                Consumer project root (default: current directory)
  --build-dir PATH                  Shared build output (default: KOTLIN_TOOLCHAIN_BUILD_DIR or build)
  --build-timeout-seconds SECONDS   Budget per preparation/reporting command (default: 1800)
  --test-timeout-seconds SECONDS    Override each module's testTimeoutSeconds (includes compilation)
Runs JVM tests once per covered module. Run other modules/platforms separately.
""".trimIndent()
if (args.any { it == "--help" || it == "-h" }) {
    println(usage)
    kotlin.system.exitProcess(0)
}
require(!System.getProperty("os.name").startsWith("Windows")) { "Coverage currently supports macOS and Linux hosts." }
val modules = linkedSetOf<String>()
var project = File(".").canonicalFile
var buildPath: String? = System.getenv("KOTLIN_TOOLCHAIN_BUILD_DIR")
var buildTimeout = 1800L
var testTimeout: Long? = null
var index = 0
while (index < args.size) {
    val option = args[index++]
    require(option in setOf("-m", "--module", "--project-dir", "--build-dir", "--build-timeout-seconds", "--test-timeout-seconds")) {
        "Unknown option: $option\n$usage"
    }
    require(index < args.size) { "Missing value for $option" }
    val value = args[index++]
    when (option) {
        "-m", "--module" -> modules.add(value)
        "--project-dir" -> project = File(value).canonicalFile
        "--build-dir" -> buildPath = value
        "--build-timeout-seconds" -> buildTimeout = value.toLong()
        "--test-timeout-seconds" -> testTimeout = value.toLong()
    }
}
require(buildTimeout in 1..Int.MAX_VALUE && (testTimeout == null || testTimeout!! in 1..Int.MAX_VALUE)) {
    "Timeouts must be positive seconds, at most ${Int.MAX_VALUE}"
}
val build = (buildPath?.let { File(it).let { path -> if (path.isAbsolute) path else project.resolve(path.path) } }
    ?: project.resolve("build")).canonicalFile
val wrapper = project.resolve("kotlin")
require(wrapper.isFile) { "No pinned kotlin wrapper in $project; use --project-dir" }
Files.createDirectories(build.toPath())

fun runToolchain(session: Path, arguments: List<String>, timeout: Long) {
    val command = listOf("sh", wrapper.path) + arguments + listOf("--project-dir", project.path, "--build-dir", build.path)
    println("Coverage: ${arguments.joinToString(" ")}")
    val process = ProcessBuilder(command).directory(project).inheritIO().apply {
        environment()["KTC_KOVER_SESSION_DIR"] = session.toString()
    }.start()
    try {
        check(process.waitFor(timeout, TimeUnit.SECONDS)) { "Coverage command timed out after $timeout seconds: ${arguments.first()}" }
        check(process.exitValue() == 0) { "Coverage command failed (exit ${process.exitValue()}): ${arguments.first()}" }
    } finally {
        if (process.isAlive) {
            try {
                process.descendants().use { it.toList() }.asReversed().forEach { it.destroyForcibly() }
            } finally {
                process.destroyForcibly().waitFor()
            }
        }
    }
}

// The toolchain takes its own lock for each invocation. Serialize entire coverage sessions too.
FileChannel.open(build.resolve("kover-run.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
    val lock = checkNotNull(channel.tryLock()) { "Another coverage session is using $build" }
    lock.use {
        val session = Files.createTempDirectory("ktc-kover-session-")
        try {
            val selected = modules.flatMap { listOf("-m", it) }
            runToolchain(session, listOf("do", "koverPrepare") + selected, buildTimeout)
            val manifests = Files.list(session).use { paths ->
                paths.filter { it.toString().endsWith(".properties") }.sorted().toList()
            }
            check(manifests.isNotEmpty()) { "No coverage-enabled modules selected" }
            try {
                for (manifest in manifests) {
                    val properties = Properties().apply { Files.newInputStream(manifest).use { load(it) } }
                    val module = properties.getProperty("module")
                    val key = manifest.fileName.toString().removeSuffix(".properties")
                    val testArguments = mutableListOf("test", "-m", module, "--platform", "jvm",
                        "--jvm-args", properties.getProperty("jvmArgs"))
                    for ((name, flag) in listOf("includeTags" to "--include-tag", "excludeTags" to "--exclude-tag")) {
                        repeat(properties.getProperty("$name.size").toInt()) { tag ->
                            testArguments += listOf(flag, properties.getProperty("$name.$tag"))
                        }
                    }
                    // Only clear this module's JVM reports; other modules/platforms belong to CI too.
                    val reports = build.resolve("reports").resolve(module).resolve("jvm")
                    check(reports.deleteRecursively()) { "Could not clear test reports: $reports" }
                    val retained = File(properties.getProperty("binary")).parentFile.resolve("test-results")
                    check(retained.deleteRecursively()) { "Could not clear retained test reports: $retained" }
                    check(retained.mkdirs()) { "Could not create retained test reports: $retained" }
                    try {
                        runToolchain(session, testArguments, testTimeout ?: properties.getProperty("testTimeoutSeconds").toLong())
                    } finally {
                        // Keep diagnostics after session cleanup, including reports from failed tests.
                        reports.walkTopDown().filter { it.isFile && it.name.startsWith("TEST-") && it.name.endsWith(".xml") }
                            .forEach { report ->
                                val target = retained.resolve(report.relativeTo(reports))
                                target.parentFile.mkdirs()
                                Files.copy(report.toPath(), target.toPath())
                            }
                    }
                    val results = Files.createDirectory(session.resolve(key))
                    check(retained.copyRecursively(results.toFile(), overwrite = true)) { "Could not stage test reports: $retained" }
                    Files.writeString(session.resolve("$key.success"), "Tests completed successfully\n")
                }
            } catch (failure: Exception) {
                // Reporting owns cleanup, including when tests fail before the normal report phase.
                for (manifest in manifests) {
                    val properties = Properties().apply { Files.newInputStream(manifest).use { load(it) } }
                    try {
                        runToolchain(session, listOf("do", "koverReport", "-m", properties.getProperty("module")), buildTimeout)
                    } catch (reportFailure: Exception) {
                        failure.addSuppressed(reportFailure)
                    }
                }
                throw failure
            }
            runToolchain(session, listOf("check", "koverCheck") + selected, buildTimeout)
        } finally {
            session.toFile().deleteRecursively()
        }
    }
}
