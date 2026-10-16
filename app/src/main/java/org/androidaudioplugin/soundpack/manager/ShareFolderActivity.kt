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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 * persisted when the user confirms. The kinds come from the app, where the user chose them
 * before picking the folder (D6); nothing is guessed from the folder's content. Sharing widens
 * who can read the folder, so the user confirms the kinds here, and chooses whether the Manager
 * imports a copy or uses the folder in place. The folder keeps its pack id, so apps that also
 * link it locally still see it once. */
class ShareFolderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tree = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        val registry = FolderRegistry.get(this)
        val served = registry.servedKinds()
        val offered = intent.getStringArrayExtra(SoundpackContract.Hub.EXTRA_KINDS)?.mapNotNull { registry.kind(it) }?.toSet().orEmpty()
        val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) launchedFromPackage else referrer?.host
        val senderLabel = sender?.let { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() } ?: sender ?: "An app"
        setResult(RESULT_CANCELED)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val scope = rememberCoroutineScope()
                    var chosen by remember { mutableStateOf(offered) }
                    var import by remember { mutableStateOf(false) }
                    var status by remember { mutableStateOf<String?>(null) }
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (tree == null) {
                            Text("Nothing to share", style = MaterialTheme.typography.headlineSmall)
                            Button(onClick = { finish() }) { Text("Close") }
                            return@Column
                        }
                        val name = DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':')
                        Text("Share $name with Soundpack Manager?", style = MaterialTheme.typography.headlineSmall)
                        Text("$senderLabel offers this folder. Every soundpack app will be able to read it, as the kinds below.")
                        // The app uses it as these kinds; without any, the user says what it is for.
                        (if (offered.isEmpty()) served else offered).forEach { kind ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = kind in chosen, onCheckedChange = { chosen = if (it) chosen + kind else chosen - kind })
                                Text(kind.label)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = !import, onClick = { import = false })
                            Text("Use it in place: no copy, slower loading")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = import, onClick = { import = true })
                            Text("Import a copy: loads fast and works without the folder, takes storage")
                        }
                        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = chosen.isNotEmpty() && status == null, onClick = {
                                status = if (import) "Importing… A large folder can take minutes." else "Sharing…"
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { registry.add(tree, chosen, import) }
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
