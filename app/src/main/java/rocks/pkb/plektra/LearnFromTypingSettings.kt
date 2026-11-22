package rocks.pkb.plektra

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Context
import android.text.format.Formatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rocks.pkb.plektra.core.suggestions.UserNGramStore

/**
 * Plektra Custom: "Learn from my typing" switch and "Delete learned data" button.
 * Learned word pairs live only in app-private storage on this device.
 */
@Composable
fun LearnFromTypingSettings(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var learnFromTyping by remember { mutableStateOf(SettingsManager.getLearnFromTyping(context)) }
    var showConfirm by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var usage by remember { mutableStateOf("") }

    LaunchedEffect(refresh) {
        usage = withContext(Dispatchers.IO) { learnedDataSummary(context) }
    }

    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.TextFields,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.plektra_custom_learn_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = stringResource(R.string.plektra_custom_learn_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3
                )
            }
            Switch(
                checked = learnFromTyping,
                onCheckedChange = { value ->
                    learnFromTyping = value
                    SettingsManager.setLearnFromTyping(context, value)
                },
                enabled = enabled
            )
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { showConfirm = true }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.plektra_custom_clear_learned_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = usage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.plektra_custom_clear_learned_title)) },
            text = { Text(stringResource(R.string.plektra_custom_clear_learned_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            UserNGramStore(context).use { it.clearAll() }
                        }
                        refresh++
                    }
                }) { Text(stringResource(R.string.plektra_custom_clear_learned_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

private fun learnedDataSummary(context: Context): String {
    val pairs = try {
        UserNGramStore(context).use { it.learnedPairCount() }
    } catch (_: Exception) {
        0L
    }
    val file = context.getDatabasePath(UserNGramStore.DATABASE_NAME)
    val bytes = (if (file.exists()) file.length() else 0L) +
        context.getDatabasePath("${UserNGramStore.DATABASE_NAME}-wal").let { if (it.exists()) it.length() else 0L }
    return context.getString(
        R.string.plektra_custom_learned_usage,
        pairs,
        UserNGramStore.MAX_ROWS,
        Formatter.formatShortFileSize(context, bytes)
    )
}
