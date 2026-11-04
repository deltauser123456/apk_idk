package com.deltauser123456.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.livekit.android.compose.state.rememberTracks
import io.livekit.android.compose.ui.VideoTrackView
import io.livekit.android.room.track.Track
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@Composable
fun ChatCallButtons(chatId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { CallSession(context) }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    var connected by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var otherUid by remember { mutableStateOf<String?>(null) }
    var incomingId by remember { mutableStateOf<String?>(null) }
    var incomingType by remember { mutableStateOf("audio") }
    var activeCallId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(chatId, uid) {
        val chatListener = db.collection("chats").document(chatId).addSnapshotListener { snap, _ ->
            val ids = snap?.get("participantUIDs") as? List<*>
            otherUid = ids?.filterIsInstance<String>()?.firstOrNull { it != uid }
        }
        val callsListener = if (uid == null) null else db.collection("chats").document(chatId)
            .collection("calls").whereEqualTo("calleeUID", uid).whereEqualTo("status", "ringing")
            .addSnapshotListener { snap, _ ->
                val doc = snap?.documents?.firstOrNull()
                incomingId = doc?.id
                incomingType = doc?.getString("type") ?: "audio"
            }
        onDispose { chatListener.remove(); callsListener?.remove() }
    }

    fun connectNow(useVideo: Boolean, callId: String?) {
        video = useVideo
        busy = true
        scope.launch {
            try {
                session.connect(chatId, useVideo)
                connected = true
                activeCallId = callId
                status = "Connected"
            } catch (e: Exception) {
                status = e.localizedMessage ?: "Call failed"
                callId?.let { db.collection("chats").document(chatId).collection("calls").document(it).update("status", "ended", "updatedAt", FieldValue.serverTimestamp()) }
            } finally { busy = false }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true && (!video || grants[Manifest.permission.CAMERA] == true)) {
            connectNow(video, activeCallId)
        } else { status = "Microphone permission is required; video also needs camera permission."; busy = false }
    }

    fun requestPermissions(useVideo: Boolean, callId: String? = null) {
        video = useVideo
        activeCallId = callId
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (useVideo) needed.add(Manifest.permission.CAMERA)
        val missing = needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) connectNow(useVideo, callId) else permissionLauncher.launch(missing.toTypedArray())
    }

    fun startCall(useVideo: Boolean) {
        if (uid == null || otherUid == null || busy) { status = "Chat is still loading. Try again."; return }
        busy = true
        val ref = db.collection("chats").document(chatId).collection("calls").document()
        ref.set(mapOf("callerUID" to uid, "calleeUID" to otherUid, "status" to "ringing",
            "type" to if (useVideo) "video" else "audio", "roomName" to "chat_$chatId",
            "createdAt" to FieldValue.serverTimestamp()))
            .addOnSuccessListener { requestPermissions(useVideo, ref.id) }
            .addOnFailureListener { status = it.localizedMessage ?: "Could not send call invitation"; busy = false }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (connected && video) {
            val room = session.currentRoom
            if (room != null) {
                val tracks = rememberTracks(sources = listOf(Track.Source.CAMERA), passedRoom = room, onlySubscribed = false).value
                tracks.forEach { trackRef ->
                    VideoTrackView(trackReference = trackRef, room = room, modifier = Modifier.fillMaxWidth().height(220.dp))
                }
            }
        }
        Row {
            TextButton(onClick = { startCall(false) }, enabled = !connected && !busy) { Text("Voice call") }
            TextButton(onClick = { startCall(true) }, enabled = !connected && !busy) { Text("Video call") }
        }
        if (incomingId != null && !connected) {
            Text("Incoming ${if (incomingType == "video") "video" else "voice"} call")
            Row {
                TextButton(onClick = {
                    val id = incomingId ?: return@TextButton
                    val useVideo = incomingType == "video"
                    db.collection("chats").document(chatId).collection("calls").document(id)
                        .update("status", "accepted", "updatedAt", FieldValue.serverTimestamp())
                        .addOnSuccessListener { requestPermissions(useVideo, id) }
                        .addOnFailureListener { status = it.localizedMessage ?: "Could not accept call" }
                }, enabled = !busy) { Text("Accept") }
                TextButton(onClick = {
                    val id = incomingId ?: return@TextButton
                    db.collection("chats").document(chatId).collection("calls").document(id)
                        .update("status", "declined", "updatedAt", FieldValue.serverTimestamp())
                        .addOnFailureListener { status = it.localizedMessage ?: "Could not decline call" }
                }) { Text("Decline") }
            }
        }
        if (connected) {
            TextButton(onClick = { scope.launch { session.setMuted(!muted); muted = !muted } }) { Text(if (muted) "Unmute" else "Mute") }
            TextButton(onClick = {
                scope.launch {
                    session.disconnect()
                    activeCallId?.let { db.collection("chats").document(chatId).collection("calls").document(it).update("status", "ended", "updatedAt", FieldValue.serverTimestamp()) }
                    connected = false; activeCallId = null; status = "Call ended"
                }
            }) { Text("Hang up") }
        }
        if (status.isNotEmpty()) Text(status)
    }
}
