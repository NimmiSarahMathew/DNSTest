package com.example.dnstest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    // DnsMonitor no longer knows about Context/Application at all, so it can't
    // be auto-constructed by the default factory -- this builds the one thing
    // it actually needs (the log file) and hands it in.
    private val dnsMonitor: DnsMonitor by viewModels {
        viewModelFactory {
            initializer {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val logFile = File(getExternalFilesDir(null), "dns_monitor_$timestamp.log")
                DnsMonitor(logFile)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    DNSMonitorScreen(dnsMonitor)
                }
            }
        }
    }
}
