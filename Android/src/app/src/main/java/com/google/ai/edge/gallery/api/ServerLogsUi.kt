package com.google.ai.edge.gallery.api

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun ServerLogsOverlay() {
    var isExpanded by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var isStarted by remember { mutableStateOf(false) }

    // Manual refresh loop to avoid complex Flow observations
    LaunchedEffect(Unit) {
        while (true) {
            logs = SimpleLlmServer.getLogs()
            isStarted = SimpleLlmServer.isRunning()
            delay(1000)
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        Column(horizontalAlignment = Alignment.End) {
            if (isExpanded) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.4f).padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("API Server (Java) - ${if (isStarted) "ON" else "OFF"}", style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { isExpanded = false }) { Icon(Icons.Default.Close, contentDescription = "Close") }
                        }
                        HorizontalDivider()
                        LazyColumn(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.05f)).padding(4.dp)) {
                            items(logs) { log ->
                                Text(log, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = { isExpanded = !isExpanded },
                containerColor = if (isStarted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            ) {
                Icon(if (isStarted) Icons.Default.Dns else Icons.Default.BugReport, contentDescription = "Logs")
            }
        }
    }
}
