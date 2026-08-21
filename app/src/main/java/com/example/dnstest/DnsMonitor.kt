package com.example.dnstest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.*

/**
 * A plain [ViewModel] -- it has no Android framework dependency at all. The log
 * [file] is resolved by the caller (who does need a [Context][android.content.Context]
 * to do that) and handed in already built, so this class stays constructible in a
 * plain JVM unit test with nothing more than a temp file.
 *
 * Survives configuration changes: monitoring starts once, in [init], and only
 * stops in [onCleared] -- when the screen is actually finished, not just rotated.
 */
class DnsMonitor(private val logFile: File) : ViewModel() {

    private val interval = 10_000L // 10 seconds
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // Observables
    private val _successCount = MutableStateFlow(0)
    val successCount: StateFlow<Int> = _successCount

    private val _failureCount = MutableStateFlow(0)
    val failureCount: StateFlow<Int> = _failureCount

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _lastResult = MutableStateFlow("")
    val lastResult: StateFlow<String> = _lastResult

    private var monitorJob: Job? = null
    private var startTime = 0L

    init {
        start()
    }

    private fun start() {
        if (_isRunning.value) return

        // Write header
        writeToLog("DNS Monitoring Log for ${Constants.DOMAIN}")
        writeToLog("Started: ${dateFormat.format(Date())}")
        writeToLog("Interval: ${interval / 1000} seconds")
        writeToLog("Device: ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
        writeToLog("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        writeToLog("Log file: ${logFile.absolutePath}")
        writeToLog("")
        writeToLog("⚠️ NOTE: Only FAILURES are logged to keep file size small")
        writeToLog("Hourly summaries show total success/failure counts")
        writeToLog("Run for as long as needed - stop anytime to get final summary")
        writeToLog("=" .repeat(80))
        writeToLog("")

        startTime = System.currentTimeMillis()
        _isRunning.value = true

        monitorJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                checkDNS()
                delay(interval)
            }
        }
    }

    private fun stop() {
        monitorJob?.cancel()
        _isRunning.value = false
        writeFinalSummary()
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    private suspend fun checkDNS() {
        val timestamp = dateFormat.format(Date())

        try {
            val startTime = System.currentTimeMillis()
            val address = InetAddress.getByName(Constants.DOMAIN)
            val duration = System.currentTimeMillis() - startTime

            _successCount.value++
            val message = "✅ SUCCESS - IP: ${address.hostAddress} | Time: ${duration}ms"
            _lastResult.value = "[$timestamp] $message"

        } catch (e: UnknownHostException) {
            val cause = when {
                e.message?.contains("No address associated", ignoreCase = true) == true ->
                    "DNS_NO_ADDRESS - DNS server returned no IP address"
                e.message?.contains("unable to resolve", ignoreCase = true) == true ->
                    "DNS_RESOLUTION_FAILED - Unable to resolve hostname"
                e.message?.contains("nodename nor servname", ignoreCase = true) == true ->
                    "DNS_INVALID_HOSTNAME - Invalid hostname or DNS not available"
                e.message?.contains("temporary failure", ignoreCase = true) == true ->
                    "DNS_TEMPORARY_FAILURE - Temporary DNS resolution failure"
                else ->
                    "DNS_UNKNOWN_ERROR"
            }
            recordFailure(timestamp, "❌ FAILED - DNS Resolution Failed", cause, e)

        } catch (e: java.net.SocketTimeoutException) {
            recordFailure(
                timestamp,
                "⏱️ TIMEOUT - DNS Query Timed Out",
                "NETWORK_TIMEOUT - DNS server didn't respond in time",
                e,
            )

        } catch (e: java.io.IOException) {
            val cause = when {
                e.message?.contains("network is unreachable", ignoreCase = true) == true ->
                    "NETWORK_UNREACHABLE - No network connection"
                e.message?.contains("connection refused", ignoreCase = true) == true ->
                    "CONNECTION_REFUSED - DNS server refused connection"
                e.message?.contains("host is down", ignoreCase = true) == true ->
                    "HOST_DOWN - Remote host is down"
                else ->
                    "IO_ERROR - Network I/O error"
            }
            recordFailure(timestamp, "🔌 NETWORK ERROR", cause, e)

        } catch (e: Exception) {
            recordFailure(
                timestamp,
                "⚠️ UNEXPECTED ERROR",
                "UNKNOWN - Unexpected exception occurred",
                e,
                includeStackTrace = true,
            )
        }

        // Log hourly summary
        val elapsed = System.currentTimeMillis() - this.startTime
        if (elapsed > 0 && (elapsed % 3600000) < interval) {
            logHourlySummary(elapsed)
        }
    }

    /**
     * Every failure branch does the same three things -- count it, format it, log
     * it -- and only differs in the cause classification and (for unexpected
     * exceptions) whether a truncated stack trace is worth including.
     */
    private fun recordFailure(
        timestamp: String,
        title: String,
        cause: String,
        error: Throwable,
        includeStackTrace: Boolean = false,
    ) {
        _failureCount.value++

        var message = "$title\n" +
            "   Cause: $cause\n" +
            "   Error: ${error.javaClass.simpleName}\n" +
            "   Details: ${error.message}"
        if (includeStackTrace) {
            message += "\n   Stack: ${error.stackTraceToString().take(200)}"
        }

        _lastResult.value = "[$timestamp] $message"
        writeToLog("[$timestamp] $message")
        writeToLog("")
    }

    private fun writeToLog(message: String) {
        try {
            logFile.appendText(message + "\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun logHourlySummary(elapsed: Long) {
        val hours = elapsed / 3600000
        val total = _successCount.value + _failureCount.value
        val successRate = if (total > 0) (_successCount.value.toFloat() / total * 100) else 0f
        val failureRate = if (total > 0) (_failureCount.value.toFloat() / total * 100) else 0f

        val summary = """

            --- HOURLY SUMMARY (after $hours hour${if (hours != 1L) "s" else ""}) ---
            Timestamp: ${dateFormat.format(Date())}
            Total checks: $total
            Successes: ${_successCount.value} (${String.format("%.2f", successRate)}%)
            Failures: ${_failureCount.value} (${String.format("%.2f", failureRate)}%)

        """.trimIndent()

        writeToLog(summary)
    }

    private fun writeFinalSummary() {
        val elapsed = System.currentTimeMillis() - startTime
        val hours = elapsed / 3600000.0
        val minutes = (elapsed % 3600000) / 60000
        val total = _successCount.value + _failureCount.value
        val successRate = if (total > 0) (_successCount.value.toFloat() / total * 100) else 0f
        val failureRate = if (total > 0) (_failureCount.value.toFloat() / total * 100) else 0f

        val durationText = if (hours >= 1.0) {
            "${String.format("%.2f", hours)} hours"
        } else {
            "$minutes minutes"
        }

        val finalSummary = """

            ${"=".repeat(80)}
            FINAL SUMMARY
            ${"=".repeat(80)}
            Duration: $durationText (${elapsed / 1000} seconds)
            Started: ${dateFormat.format(Date(startTime))}
            Stopped: ${dateFormat.format(Date())}

            Total checks: $total
            Successes: ${_successCount.value} (${String.format("%.2f", successRate)}%)
            Failures: ${_failureCount.value} (${String.format("%.2f", failureRate)}%)

            Average check interval: ${if (total > 0) elapsed / total / 1000 else 0} seconds

            ANALYSIS:
            ${if (_failureCount.value == 0) {
            "✅ No failures detected - DNS is stable!"
        } else {
            """⚠️ ${_failureCount.value} failures detected
            Review failure details above to identify patterns:
            - Check if failures cluster at specific times
            - Look for consistent error types (DNS_NO_ADDRESS, TIMEOUT, etc.)
            - Note any correlation with time of day or duration"""
        }}
            ${"=".repeat(80)}
        """.trimIndent()

        writeToLog(finalSummary)
    }

    fun getLogFilePath(): String = logFile.absolutePath

    fun getElapsedTime(): String {
        if (!_isRunning.value) return "Not running"

        val elapsed = System.currentTimeMillis() - startTime
        val hours = elapsed / 3600000
        val minutes = (elapsed % 3600000) / 60000

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }

    object Constants {
        const val DOMAIN = "google.com"
    }
}
