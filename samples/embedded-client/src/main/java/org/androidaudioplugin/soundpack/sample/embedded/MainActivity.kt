package org.androidaudioplugin.soundpack.sample.embedded

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.client.Sf2Metadata
import org.androidaudioplugin.soundpack.client.SoundpackClient
import org.androidaudioplugin.soundpack.host.FolderRegistry
import org.androidaudioplugin.soundpack.host.ui.SoundpackFoldersActivity
import org.androidaudioplugin.soundpack.ui.SoundpackPicker

/** Picks SFZ or SF2 entries from installed packs and from this app's own linked folders. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { SampleScreen() }
            }
        }
    }
}

@Composable
private fun SampleScreen() {
    val context = LocalContext.current
    val registry = remember { FolderRegistry.get(context) }
    var kind by remember { mutableStateOf(SoundpackKind.SFZ) }
    val client = remember(kind) { SoundpackClient(context, kind) }
    var selected by remember(kind) { mutableStateOf<SoundpackClient.Entry?>(null) }
    var detail by remember { mutableStateOf("") }
    var linkResult by remember { mutableStateOf<String?>(null) }

    // The UI-free path: SoundpackFolderPickerActivity picks and links in one go.
    val link = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val counts = data?.getBundleExtra(FolderRegistry.EXTRA_ENTRY_COUNTS)
        linkResult = when {
            counts != null -> "Linked: " + counts.keySet().joinToString { "${counts.getInt(it)} ${it.uppercase()}" }
            else -> data?.getStringExtra(FolderRegistry.EXTRA_ERROR) ?: "Cancelled"
        }
    }

    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Soundpack embedded sample", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(SoundpackKind.SFZ, SoundpackKind.SF2).forEach {
                FilterChip(selected = kind == it, onClick = { kind = it }, label = { Text(it.id.uppercase()) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { context.startActivity(Intent(context, SoundpackFoldersActivity::class.java)) }) { Text("Manage folders") }
            OutlinedButton(onClick = { link.launch(registry.pickFolderIntent()) }) { Text("Link folder (UI-free)") }
        }
        linkResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        SoundpackPicker(client, selected?.let { client.identityOf(it) }, onSelect = { selected = it }, Modifier.weight(1f).fillMaxWidth())
        selected?.let { entry ->
            LaunchedEffect(entry) { detail = withContext(Dispatchers.IO) { describe(client, entry) } }
            Text(client.identityOf(entry).format(), style = MaterialTheme.typography.bodySmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun describe(client: SoundpackClient, entry: SoundpackClient.Entry): String = try {
    when (client.kind) {
        SoundpackKind.SF2 -> Sf2Metadata.presets(entry).joinToString("\n") { "${it.bank}:${it.program} ${it.name}" }.ifEmpty { "No presets" }
        else -> {
            val session = client.open(client.identityOf(entry))
            val head = session.openEntry().use { it.createInputStream().bufferedReader().readLine() }
            "${session.files.size} files in the pack. First line: $head"
        }
    }
} catch (e: Exception) {
    "Cannot open: ${e.message}"
}
