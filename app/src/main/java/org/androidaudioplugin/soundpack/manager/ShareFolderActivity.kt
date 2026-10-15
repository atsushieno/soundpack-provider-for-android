package org.androidaudioplugin.soundpack.manager

import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.host.FolderRegistry

/** "Share with Soundpack Manager" (docs/PLAN.md §3.9): an app hands over a folder it linked.
 * The grant arrives with READ | PERSISTABLE | PREFIX in the ClipData (spike S6) and is
 * persisted when the user confirms. Sharing a folder widens who can read it, so the user picks
 * the kinds here (D2). The folder keeps its pack id, so apps that also link it locally still
 * see it once. */
class ShareFolderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tree = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        val proposed = intent.getStringArrayExtra(SoundpackContract.Hub.EXTRA_KINDS)?.mapNotNull { SoundpackKind.builtIn(it) }?.toSet().orEmpty()
        val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) launchedFromPackage else referrer?.host
        val senderLabel = sender?.let { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() } ?: sender ?: "An app"
        val registry = FolderRegistry.get(this)
        setResult(RESULT_CANCELED)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val scope = rememberCoroutineScope()
                    var detected by remember { mutableStateOf<Map<SoundpackKind, Int>?>(null) }
                    var chosen by remember { mutableStateOf(emptySet<SoundpackKind>()) }
                    var status by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(tree) {
                        if (tree == null) return@LaunchedEffect
                        try {
                            val counts = withContext(Dispatchers.IO) { registry.detectKinds(tree) }
                            detected = counts
                            chosen = counts.filterValues { it > 0 }.keys.let { found -> if (proposed.isEmpty()) found else proposed.intersect(found).ifEmpty { found } }
                        } catch (e: Exception) {
                            status = "Cannot read that folder: ${e.message}"
                        }
                    }
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (tree == null) {
                            Text("Nothing to share", style = MaterialTheme.typography.headlineSmall)
                            Button(onClick = { finish() }) { Text("Close") }
                            return@Column
                        }
                        val name = DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':')
                        Text("Share $name with Soundpack Manager?", style = MaterialTheme.typography.headlineSmall)
                        Text("$senderLabel offers this folder. Every soundpack app will be able to read it, as the kinds below.")
                        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        val counts = detected
                        if (counts == null && status == null)
                            Text("Looking at the folder…")
                        counts?.forEach { (kind, count) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = kind in chosen, onCheckedChange = { chosen = if (it) chosen + kind else chosen - kind })
                                Text("${kind.id.uppercase()} ($count found)")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = counts != null && chosen.isNotEmpty(), onClick = {
                                status = "Sharing…"
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { registry.add(tree, chosen) }
                                        setResult(RESULT_OK)
                                        finish()
                                    } catch (e: Exception) {
                                        status = "Cannot share it: ${e.message}"
                                    }
                                }
                            }) { Text("Share") }
                            OutlinedButton(onClick = { finish() }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}
