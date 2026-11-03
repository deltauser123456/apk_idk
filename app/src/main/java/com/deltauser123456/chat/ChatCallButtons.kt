package com.deltauser123456.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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

    fun connectNow(useVideo: Boolean) {
        video = useVideo
        scope.launch {
            try {
                session.connect(chatId, useVideo)
                connected = true
                status = "Connected"
            } catch (e: Exception) {
                status = e.localizedMessage ?: "Call failed"
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true &&
            (!video || grants[Manifest.permission.CAMERA] == true)) {
            connectNow(video)
        } else {
            status = "Allow microphone permission; video calls also need camera permission."
        }
    }

    fun requestPermissions(useVideo: Boolean) {
        video = useVideo
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (useVideo) needed.add(Manifest.permission.CAMERA)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) connectNow(useVideo)
        else permissionLauncher.launch(missing.toTypedArray())
    }

    TextButton(onClick = { requestPermissions(false) }, enabled = !connected) { Text("Voice call") }
    TextButton(onClick = { requestPermissions(true) }, enabled = !connected) { Text("Video call") }
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
