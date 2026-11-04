package com.deltauser123456.chat

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the LiveKit room for one call. Call only after runtime microphone permission
 * has been granted. Camera permission is required before enabling video.
 */
class CallSession(context: Context) {
    private val appContext = context.applicationContext
    private var room: Room? = null
    val currentRoom: Room? get() = room

    suspend fun connect(chatId: String, video: Boolean): Room = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Sign in before starting a call.")
        val firebaseToken = user.getIdToken(false).awaitToken()
        val connection = URL("https://chat-call-server.onrender.com/token")
        val http = connection.openConnection() as HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.connectTimeout = 20000
            http.readTimeout = 30000
            http.doOutput = true
            http.setRequestProperty("Content-Type", "application/json")
            http.setRequestProperty("Authorization", "Bearer $firebaseToken")
            http.outputStream.use {
                it.write(JSONObject().put("roomName", "chat_$chatId").toString().toByteArray())
            }
            val code = http.responseCode
            val body = (if (code in 200..299) http.inputStream else http.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "{}"
            if (code !in 200..299) {
                val message = JSONObject(body).optString("error", "Call server returned HTTP $code")
                throw IllegalStateException(message)
            }
            val json = JSONObject(body)
            val liveRoom = LiveKit.create(appContext)
            liveRoom.connect(json.getString("serverUrl"), json.getString("token"))
            liveRoom.localParticipant.setMicrophoneEnabled(true)
            if (video) liveRoom.localParticipant.setCameraEnabled(true)
            room = liveRoom
            liveRoom
        } finally {
            http.disconnect()
        }
    }

    suspend fun setMuted(muted: Boolean) {
        room?.localParticipant?.setMicrophoneEnabled(!muted)
    }

    suspend fun setCameraEnabled(enabled: Boolean) {
        room?.localParticipant?.setCameraEnabled(enabled)
    }

    suspend fun disconnect() {
        room?.disconnect()
        room = null
    }

    private suspend fun com.google.android.gms.tasks.Task<com.google.firebase.auth.GetTokenResult>.awaitToken(): String =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result.token ?: "") {}
            }.addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }
}
