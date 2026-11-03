package com.deltauser123456.chat

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@Composable
fun ChatCallButtons(chatId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { CallSession(context) }
    var connected by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    TextButton(onClick = {
        video = false
        scope.launch {
            try { session.connect(chatId, false); connected = true; status = "Connected" }
            catch (e: Exception) { status = e.localizedMessage ?: "Call failed" }
        }
    }, enabled = !connected) { Text("Voice call") }

    TextButton(onClick = {
        video = true
        scope.launch {
            try { session.connect(chatId, true); connected = true; status = "Connected" }
            catch (e: Exception) { status = e.localizedMessage ?: "Call failed" }
        }
    }, enabled = !connected) { Text("Video call") }

    if (connected) {
        TextButton(onClick = {
            scope.launch { session.setMuted(!muted); muted = !muted }
        }) { Text(if (muted) "Unmute" else "Mute") }
        TextButton(onClick = {
            scope.launch { session.disconnect(); connected = false; status = "Call ended" }
        }) { Text("Hang up") }
    }
    if (status.isNotEmpty()) Text(status)
}
