package com.deltauser123456.chat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var googleResult: ((String?) -> Unit)? = null
    private val googleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                googleResult?.invoke("Google returned no ID token. Check that the Web client ID is configured in Firebase.")
            } else {
                googleResult?.invoke(idToken)
            }
        } catch (e: com.google.android.gms.common.api.ApiException) {
            googleResult?.invoke("Google sign-in error (code ${e.statusCode}): ${e.statusMessage ?: e.localizedMessage ?: "Unknown error"}")
        } catch (e: Exception) {
            googleResult?.invoke("Google sign-in failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFB8C7FF),
                background = Color(0xFF101114),
                surface = Color(0xFF1B1D22)
            )) {
                var user by remember { mutableStateOf(auth.currentUser) }
                DisposableEffect(Unit) {
                    val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }
                    auth.addAuthStateListener(listener)
                    onDispose { auth.removeAuthStateListener(listener) }
                }
                if (user == null) {
                    AuthScreen(
                        onEmail = { email, pass, create, done ->
                            val action = if (create) auth.createUserWithEmailAndPassword(email, pass)
                                else auth.signInWithEmailAndPassword(email, pass)
                            action.addOnCompleteListener { done(if (it.isSuccessful) null else it.exception?.localizedMessage ?: "Sign-in failed") }
                        },
                        onGoogle = { done ->
                            val webClientId = getString(R.string.default_web_client_id)
                            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestIdToken(webClientId).requestEmail().build()
                            val client = GoogleSignIn.getClient(this, options)
                            googleResult = { token ->
                                if (token == null) done("Google sign-in was cancelled or failed.")
                                else auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                                    .addOnCompleteListener { done(if (it.isSuccessful) null else it.exception?.localizedMessage ?: "Google sign-in failed") }
                            }
                            googleLauncher.launch(client.signInIntent)
                        }
                    )
                } else {
                    ChatScreen(
                        email = user?.email ?: "Your account",
                        db = db,
                        uid = user!!.uid,
                        onSignOut = { auth.signOut() }
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthScreen(
    onEmail: (String, String, Boolean, (String?) -> Unit) -> Unit,
    onGoogle: ((String?) -> Unit) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Color(0xFF101114)).padding(26.dp), verticalArrangement = Arrangement.Center) {
        Text("Chat", style = MaterialTheme.typography.displayLarge, color = Color.White)
        Text("Messages, together.", color = Color.LightGray, modifier = Modifier.padding(bottom = 28.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = {
            busy = true; error = null
            onEmail(email.trim(), password, create) { message -> busy = false; error = message }
        }, enabled = !busy && email.isNotBlank() && password.length >= 6, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Please wait…" else if (create) "Create account" else "Sign in")
        }
        TextButton(onClick = { create = !create; error = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (create) "Already have an account? Sign in" else "Create an account")
        }
        OutlinedButton(onClick = { busy = true; onGoogle { error = it; busy = false } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Continue with Google")
        }
    }
}

private data class ChatItem(val id: String, val email: String, val lastMessage: String)

@Composable
private fun ChatScreen(email: String, db: FirebaseFirestore, uid: String, onSignOut: () -> Unit) {
    var target by remember { mutableStateOf("") }
    var chats by remember { mutableStateOf<List<ChatItem>>(emptyList()) }
    var active by remember { mutableStateOf<ChatItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uid, email) {
        val profile = mapOf(
            "email" to email,
            "emailLower" to email.trim().lowercase(),
            "updatedAt" to System.currentTimeMillis()
        )
        db.collection("users").document(uid).set(profile, SetOptions.merge())
            .addOnFailureListener { error = "Couldn't save your account profile: ${it.localizedMessage}" }
    }

    DisposableEffect(uid) {
        val registration = db.collection("chats").whereArrayContains("participantUIDs", uid)
            .addSnapshotListener { snap, e ->
                if (e != null) error = e.localizedMessage
                else chats = snap?.documents?.map { d ->
                    val participants = d.get("participantEmails") as? List<*> ?: emptyList<Any>()
                    ChatItem(d.id, participants.filterIsInstance<String>().firstOrNull { it != email } ?: "Chat", d.getString("lastMessage") ?: "No messages yet")
                } ?: emptyList()
            }
        onDispose { registration.remove() }
    }

    if (active != null) {
        ConversationScreen(active!!, db, uid, email, onBack = { active = null })
        return
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101114)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chat", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Text(email, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
        Spacer(Modifier.height(24.dp))
        Text("Start a conversation", color = Color.White, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(target, { target = it }, label = { Text("Their email address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            error = null
            val otherEmail = target.trim().lowercase()
            if (otherEmail == email.lowercase()) { error = "Enter someone else's email."; return@Button }
            db.collection("users").whereEqualTo("emailLower", otherEmail).limit(1).get()
                .addOnSuccessListener { result ->
                    val other = result.documents.firstOrNull()
                    if (other == null) { error = "No account found with that email. Ask them to open the app once."; return@addOnSuccessListener }
                    val ids = listOf(uid, other.id).sorted()
                    db.collection("chats").whereArrayContains("participantUIDs", uid).get()
                        .addOnSuccessListener { existing ->
                            val found = existing.documents.firstOrNull { (it.get("participantUIDs") as? List<*>)?.toSet() == ids.toSet() }
                            if (found != null) active = ChatItem(found.id, otherEmail, found.getString("lastMessage") ?: "No messages yet")
                            else {
                                val data = mapOf("participantUIDs" to ids, "participantEmails" to listOf(email.lowercase(), otherEmail).sorted(),
                                    "lastMessage" to "No messages yet", "updatedAt" to System.currentTimeMillis())
                                db.collection("chats").add(data).addOnSuccessListener { active = ChatItem(it.id, otherEmail, "No messages yet") }
                                    .addOnFailureListener { error = it.localizedMessage }
                            }
                        }.addOnFailureListener { error = it.localizedMessage }
                }.addOnFailureListener { error = it.localizedMessage }
        }, modifier = Modifier.fillMaxWidth()) { Text("Find or create chat") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(18.dp))
        Text("Your conversations", color = Color.White, style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(chats) { chat ->
                ListItem(headlineContent = { Text(chat.email) }, supportingContent = { Text(chat.lastMessage) },
                    modifier = Modifier.clickable { active = chat }, colors = ListItemDefaults.colors(containerColor = Color(0xFF1B1D22)))
            }
        }
    }
}

private data class MessageItem(val id: String, val sender: String, val text: String)

@Composable
private fun ConversationScreen(chat: ChatItem, db: FirebaseFirestore, uid: String, email: String, onBack: () -> Unit) {
    var messages by remember { mutableStateOf<List<MessageItem>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(chat.id) {
        val registration = db.collection("chats").document(chat.id).collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, e ->
                if (e != null) error = e.localizedMessage
                else messages = snap?.documents?.map { MessageItem(it.id, it.getString("senderUID") ?: "", it.getString("text") ?: "") } ?: emptyList()
            }
        onDispose { registration.remove() }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF101114)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text(chat.email, color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = false) {
            items(messages) { msg ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.sender == uid) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (msg.sender == uid) Color(0xFF34466E) else Color(0xFF282B32),
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(msg.text, color = Color.White, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message…") }, maxLines = 4)
            IconButton(onClick = {
                val text = draft.trim()
                if (text.isNotEmpty() && text.length <= 4000) {
                    db.collection("chats").document(chat.id).collection("messages").add(
                        mapOf("senderUID" to uid, "text" to text, "createdAt" to System.currentTimeMillis())
                    ).addOnSuccessListener {
                        db.collection("chats").document(chat.id).update("lastMessage", text, "updatedAt", System.currentTimeMillis())
                        draft = ""
                    }.addOnFailureListener { error = it.localizedMessage }
                }
            }) { Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White) }
        }
    }
}
