package org.androidaudioplugin.soundpack.host.ui

import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.host.FolderRegistry

/** The folders this app has linked, one kind at a time (docs/PLAN.md §3.9, D6). The user
 * chooses the kind first and then picks a folder for it, to import (copy into this app) or to
 * use in place. A Compose front end for [FolderRegistry]; apps with another UI toolkit use the
 * registry directly. */
@Composable
fun SoundpackFoldersScreen(
    registry: FolderRegistry,
    modifier: Modifier = Modifier,
    /** The kind shown first; the first kind this app serves when null. */
    initialKind: SoundpackKind? = null,
    onDone: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // Resource types can change while the screen is shown (the Soundpack Manager's Types tab).
    var served by remember { mutableStateOf(registry.servedKinds()) }
    var kind by remember { mutableStateOf(initialKind?.takeIf { it in served } ?: served.firstOrNull()) }
    var revision by remember { mutableIntStateOf(0) }
    var folders by remember { mutableStateOf(emptyList<FolderRegistry.Folder>()) }
    var counts by remember { mutableStateOf(emptyMap<String, Map<SoundpackKind, Int>>()) }
    var shareIntents by remember { mutableStateOf(emptyMap<String, android.content.Intent>()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    DisposableEffect(registry) {
        val observation = registry.observe { revision++ }
        onDispose { observation.close() }
    }
    LaunchedEffect(revision) {
        served = withContext(Dispatchers.IO) { registry.servedKinds() }
        // The same type with its current label and description, or the first one if it is gone.
        kind = served.firstOrNull { it == kind } ?: served.firstOrNull()
        val (list, entryCounts) = withContext(Dispatchers.IO) {
            registry.folders().let { list -> list to list.associate { folder -> folder.id to folder.kinds.associateWith { registry.entryCount(folder.id, it) } } }
        }
        folders = list
        counts = entryCounts
        shareIntents = withContext(Dispatchers.IO) { list.mapNotNull { folder -> registry.shareWithHubIntent(folder.id)?.let { folder.id to it } }.toMap() }
    }

    fun work(label: String, block: suspend () -> Unit) {
        busy = label
        message = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                busy = null
                revision++
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val chosen = kind
        if (tree == null || chosen == null) return@rememberLauncherForActivityResult
        val import = importing
        work(if (import) IMPORTING else "Scanning…") {
            val folder = withContext(Dispatchers.IO) { registry.add(tree, setOf(chosen), import) }
            if (withContext(Dispatchers.IO) { registry.entryCount(folder.id, chosen) } == 0)
                message = "${folder.displayName} has no ${chosen.label} files. Check that you picked the right folder."
        }
    }

    Surface(modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Folders", style = MaterialTheme.typography.headlineSmall)
            if (served.size > 1)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    served.forEach { FilterChip(selected = kind == it, onClick = { kind = it }, enabled = busy == null, label = { Text(it.label) }) }
                }
            val current = kind ?: return@Column
            val label = current.label
            if (current.description.isNotEmpty())
                Text(current.description, style = MaterialTheme.typography.bodyMedium)
            Text("Add a folder that you put on this device (adb push, USB, a file manager); " +
                "subfolders are included. Import copies it into this app: it loads as fast as an installed pack and keeps " +
                "working without the folder, but takes storage. In place uses the folder where it is, with no copy, and " +
                "loads slower. Android cannot use the root of storage or of Download: pick a folder of its own.",
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { importing = true; picker.launch(null) }, enabled = busy == null) { Text("Import a folder") }
                OutlinedButton(onClick = { importing = false; picker.launch(null) }, enabled = busy == null) { Text("Use a folder in place") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { work("Rescanning…") { withContext(Dispatchers.IO) { registry.rescan() } } },
                    enabled = busy == null && folders.isNotEmpty()) { Text("Rescan all") }
                onDone?.let { OutlinedButton(onClick = it) { Text("Done") } }
            }
            busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val shown = folders.filter { current in it.kinds }
            if (shown.isEmpty() && busy == null)
                Text("No $label folders yet.", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { folder ->
                    FolderCard(folder, current, counts[folder.id]?.get(current) ?: 0, enabled = busy == null,
                        onShare = shareIntents[folder.id]?.let { intent -> { context.startActivity(intent) } },
                        onImported = { imported -> work(if (imported) IMPORTING else "Deleting the copy…") {
                            withContext(Dispatchers.IO) { registry.setImported(folder.id, imported) } } },
                        onRescan = { work("Rescanning…") { withContext(Dispatchers.IO) { registry.rescan(folder.id) } } },
                        onRemove = { work("Removing…") { withContext(Dispatchers.IO) { registry.removeKind(folder.id, current) } } })
                }
            }
        }
    }
}

private const val IMPORTING = "Importing… A large folder can take minutes."

@Composable
private fun FolderCard(
    folder: FolderRegistry.Folder,
    kind: SoundpackKind,
    count: Int,
    enabled: Boolean,
    onImported: (Boolean) -> Unit,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
    /** "Share with Soundpack Manager"; null when the hub is not installed (§3.9). */
    onShare: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(folder.displayName, style = MaterialTheme.typography.titleMedium)
            Text(DocumentsContract.getTreeDocumentId(folder.treeUri), style = MaterialTheme.typography.bodySmall)
            Text(if (folder.available)
                "$count ${kind.label} · ${folder.fileCount} files · " + if (folder.imported) "imported" else "in place"
            else "Not available: ${folder.scanError}. Add it again.",
                color = if (folder.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            if (folder.imported && folder.scanError != null)
                Text("The folder cannot be read (${folder.scanError}); its imported copy is used.", style = MaterialTheme.typography.bodySmall)
            folder.importError?.let { Text("$it. Those files are read from the folder.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (folder.available && count == 0)
                Text("No ${kind.label} files here; check that you picked the right folder.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRescan, enabled = enabled) { Text("Rescan") }
                TextButton(onClick = onRemove, enabled = enabled) { Text("Remove") }
                TextButton(onClick = { onImported(!folder.imported) }, enabled = enabled) { Text(if (folder.imported) "Use in place" else "Import") }
                onShare?.let { TextButton(onClick = it, enabled = enabled && folder.available) { Text("Share with Soundpack Manager") } }
            }
        }
    }
}

/** [SoundpackFoldersScreen] as an activity, for apps without Compose screens of their own; it
 * shows the first kind of [FolderRegistry.EXTRA_KINDS] first, if given. A plugin UI opens it
 * with FLAG_ACTIVITY_NEW_TASK, as AAP Sfizz's "SFZ folders" button does.
 * On Android 14+, Android blocks activity starts from a plugin UI embedded in another app's
 * window (docs/spikes.md, S5). */
class SoundpackFoldersActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val registry = FolderRegistry.get(this)
        val kind = intent.getStringArrayExtra(FolderRegistry.EXTRA_KINDS)?.firstNotNullOfOrNull { registry.kind(it) }
        setContent {
            MaterialTheme {
                SoundpackFoldersScreen(registry, initialKind = kind, onDone = { finish() })
            }
        }
    }
}
