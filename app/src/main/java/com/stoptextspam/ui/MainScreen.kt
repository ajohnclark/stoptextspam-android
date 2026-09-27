package com.stoptextspam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stoptextspam.BuildConfig
import com.stoptextspam.service.SmsNotificationListener
import com.stoptextspam.data.SpamDatabase
import com.stoptextspam.data.SpamMessage
import com.stoptextspam.util.PrefsManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { PrefsManager(context) }
    val db = remember { SpamDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(prefs.isEnabled()) }
    var apiKeyInput by remember { mutableStateOf("") }
    var hasApiKey by remember { mutableStateOf(prefs.getApiKey().isNotBlank()) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    val activeMessages by db.spamMessageDao().getAllFlow().collectAsState(initial = emptyList())
    val archivedMessages by db.spamMessageDao().getArchivedFlow().collectAsState(initial = emptyList())
    val totalCount by db.spamMessageDao().getTotalCountFlow().collectAsState(initial = 0)
    val spamMessages = if (showArchived) archivedMessages else activeMessages
    var hasNotificationAccess by remember { mutableStateOf(false) }
    var listenerConnected by remember { mutableStateOf(false) }
    var lastSms by remember { mutableStateOf(0L) }
    var lastClassification by remember { mutableStateOf("") }
    var lastClassificationTime by remember { mutableStateOf(0L) }

    // Check permissions whenever app comes to foreground
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val cn = ComponentName(context, SmsNotificationListener::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        hasNotificationAccess = flat?.contains(cn.flattenToString()) == true
        listenerConnected = SmsNotificationListener.instance != null
        lastSms = prefs.lastSmsReceived()
        lastClassification = prefs.lastClassification()
        lastClassificationTime = prefs.lastClassificationTime()
        if (hasNotificationAccess && !listenerConnected) {
            android.service.notification.NotificationListenerService.requestRebind(cn)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StopTextSpam") },
                actions = {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Enable/Disable toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Spam Protection", fontWeight = FontWeight.Bold)
                        Text(
                            if (enabled) "Enabled — SMS and photo filtering" else "Disabled",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            prefs.setEnabled(it)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("OpenRouter API key", fontWeight = FontWeight.Bold)
                            Text(
                                if (hasApiKey) "Saved on this device" else "No key saved",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = { showApiKeyDialog = true }
                        ) { Text(if (hasApiKey) "Replace" else "Add key") }
                    }
                    Text(
                        "Unknown-sender SMS sender and message are sent to OpenRouter when a key is saved.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (hasApiKey) {
                        TextButton(
                            onClick = {
                                prefs.setApiKey("")
                                hasApiKey = false
                            }
                        ) { Text("Remove key") }
                    }
                }
            }

            if (showApiKeyDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showApiKeyDialog = false
                        apiKeyInput = ""
                    },
                    title = { Text("Your OpenRouter API key") },
                    text = {
                        Column {
                            Text(
                                "The app sends unknown-sender SMS sender and message to OpenRouter. " +
                                    "Your key is stored in app-private preferences; Android backup is disabled."
                            )
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("API key") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    autoCorrect = false
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                prefs.setApiKey(apiKeyInput.trim())
                                hasApiKey = true
                                apiKeyInput = ""
                                showApiKeyDialog = false
                            },
                            enabled = apiKeyInput.isNotBlank()
                        ) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showApiKeyDialog = false
                            apiKeyInput = ""
                        }) { Text("Cancel") }
                    }
                )
            }

            if (enabled) {
                val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                Text(
                    "Notification listener: " + if (listenerConnected) "connected" else "not connected",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Last SMS received: " + if (lastSms > 0) dateFormat.format(Date(lastSms)) else "not recorded yet",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    lastClassification + if (lastClassificationTime > 0) " (${dateFormat.format(Date(lastClassificationTime))})" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (!hasNotificationAccess) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Notification Access Required", fontWeight = FontWeight.Bold)
                            Text(
                                "Tap to enable — allows silencing spam notifications",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (showArchived) "Archived (${archivedMessages.size})"
                    else "Blocked Messages (${activeMessages.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                androidx.compose.material3.TextButton(
                    onClick = { showArchived = !showArchived }
                ) {
                    Text(if (showArchived) "Show Active" else "Show Archived")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (spamMessages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (showArchived) "No archived messages" else "No spam detected yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(spamMessages, key = { it.id }) { msg ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value != SwipeToDismissBoxValue.Settled) {
                                    scope.launch { db.spamMessageDao().archive(msg.id) }
                                    true
                                } else {
                                    false
                                }
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Text(
                                        "Archived",
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        ) {
                            SpamMessageCard(msg, showSwipeHint = !showArchived)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // Estimated cost calculator
            val cost = (totalCount * 50 * 0.25 / 1_000_000) + (totalCount * 30 * 1.50 / 1_000_000)
            Text(
                text = "Est. cost: $${String.format("%.4f", cost)} | Spam zen? Priceless.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
            )
        }
    }

}

@Composable
fun SpamMessageCard(message: SpamMessage, showSwipeHint: Boolean = true) {
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(message.sender, fontWeight = FontWeight.Bold)
                Text(
                    dateFormat.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(message.body, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
            if (message.reason.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Reason: ${message.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (showSwipeHint) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Swipe to dismiss",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.LightGray
                )
            }
        }
    }
}
