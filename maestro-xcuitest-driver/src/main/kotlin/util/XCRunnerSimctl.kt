package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.utils.MaestroTimer
import net.harawata.appdirs.AppDirsFactory
import okio.buffer
import okio.source
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object XCRunnerSimctl {

    private const val APP_NAME = "maestro"
    private const val APP_AUTHOR = "mobile_dev"
    private const val LOG_DIR_DATE_FORMAT = "yyyy-MM-dd_HHmmss"
    private const val MAX_COUNT_XCTEST_LOGS = 5

    private val dateFormatter by lazy { DateTimeFormatter.ofPattern(LOG_DIR_DATE_FORMAT) }
    private val logDirectory by lazy {
        File(AppDirsFactory.getInstance().getUserLogDir(APP_NAME, null, APP_AUTHOR), "xctest_runner_logs").apply {
            if (!exists()) {
                mkdir()
            } else {
                val existing = listFiles() ?: emptyArray()
                val toDelete = existing.sortedByDescending { it.name }
                val count = toDelete.size
                if (count > MAX_COUNT_XCTEST_LOGS) toDelete.forEach { it.deleteRecursively() }
            }
        }
    }

    fun listApps(): Set<String> {
        val process = ProcessBuilder("bash", "-c", "xcrun simctl listapps booted | plutil -convert json - -o -").start()

        val json = String(process.inputStream.readBytes())

        val mapper = jacksonObjectMapper()
        val appsMap = mapper.readValue(json, Map::class.java) as Map<String, Any>

        return appsMap.keys
    }

    fun setProxy(host: String, port: Int) {
        ProcessBuilder("networksetup", "-setwebproxy", "Wi-Fi", host, port.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        ProcessBuilder("networksetup", "-setsecurewebproxy", "Wi-Fi", host, port.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    fun resetProxy() {
        ProcessBuilder("networksetup", "-setwebproxystate", "Wi-Fi", "off")
            .redirectErrorStream(true)
            .start()
            .waitFor()
        ProcessBuilder("networksetup", "-setsecurewebproxystate", "Wi-Fi", "off")
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    fun uninstall(bundleId: String) {
        CommandLineUtils.runCommand("xcrun simctl uninstall booted $bundleId")
    }

    fun ensureAppAlive(bundleId: String) {
        MaestroTimer.retryUntilTrue(timeoutMs = 4000, delayMs = 300) {
            val process = ProcessBuilder(
                "bash",
                "-c",
                "xcrun simctl spawn booted launchctl list | grep $bundleId | awk '/$bundleId/ {print \$3}'"
            ).start()

            val processOutput = process.inputStream.source().buffer().readUtf8().trim()
            process.waitFor(3000, TimeUnit.MILLISECONDS)

            processOutput.contains(bundleId)
        }
    }

    fun runXcTestWithoutBuild(deviceId: String, xcTestRunFilePath: String): Process {
        val date = dateFormatter.format(LocalDateTime.now())
        return CommandLineUtils.runCommand(
            "xcodebuild test-without-building -xctestrun $xcTestRunFilePath -destination id=$deviceId",
            waitForCompletion = false,
            outputFile = File(logDirectory, "xctest_runner_$date.log")
        )
    }

    fun screenshot(path: String) {
        CommandLineUtils.runCommand(
            listOf("xcrun", "simctl", "io", "booted", "screenshot", path),
            waitForCompletion = true
        )
    }
}