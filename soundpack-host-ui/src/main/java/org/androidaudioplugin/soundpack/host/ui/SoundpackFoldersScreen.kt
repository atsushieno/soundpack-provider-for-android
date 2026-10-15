package org.androidaudioplugin.soundpack.host.ui

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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

/** Lists the folders this app has linked, and adds, re-kinds, rescans and removes them
 * (docs/PLAN.md §3.9, D2). A Compose front end for [FolderRegistry]; apps with another UI
 * toolkit use the registry directly. */
@Composable
fun SoundpackFoldersScreen(registry: FolderRegistry, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val served = remember { registry.servedKinds() }
    var revision by remember { mutableIntStateOf(0) }
    var folders by remember { mutableStateOf(emptyList<FolderRegistry.Folder>()) }
    var counts by remember { mutableStateOf(emptyMap<String, Map<SoundpackKind, Int>>()) }
    var shareIntents by remember { mutableStateOf(emptyMap<String, android.content.Intent>()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingFolder?>(null) }

    DisposableEffect(registry) {
        val observation = registry.observe { revision++ }
        onDispose { observation.close() }
    }
    LaunchedEffect(revision) {
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

    fun link(tree: Uri, kinds: Set<SoundpackKind>) = work("Scanning…") {
        withContext(Dispatchers.IO) { registry.add(tree, kinds) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        work("Looking at the folder…") {
            val detected = withContext(Dispatchers.IO) { registry.detectKinds(tree) }
            pending = PendingFolder(tree, detected)
        }
    }

    Surface(modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Folders", style = MaterialTheme.typography.headlineSmall)
            Text("Link a folder you put on this device (adb push, USB, a file manager) that holds " +
                served.joinToString(", ") { it.id.uppercase() } + " content. Subfolders are included. " +
                "Android cannot link the root of storage or of Download: use a folder of its own, such as Music/SFZ.",
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { picker.launch(null) }, enabled = busy == null) { Text("Add folder") }
                OutlinedButton(onClick = { work("Rescanning…") { withContext(Dispatchers.IO) { registry.rescan() } } },
                    enabled = busy == null && folders.isNotEmpty()) { Text("Rescan all") }
                onDone?.let { OutlinedButton(onClick = it) { Text("Done") } }
            }
            busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (folders.isEmpty() && busy == null)
                Text("No folders linked yet.", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(folders, key = { it.id }) { folder ->
                    FolderCard(folder, served, counts[folder.id].orEmpty(), enabled = busy == null,
                        onShare = shareIntents[folder.id]?.let { intent -> { context.startActivity(intent) } },
                        onKinds = { kinds -> work("Rescanning…") { withContext(Dispatchers.IO) { registry.setKinds(folder.id, kinds) } } },
                        onRescan = { work("Rescanning…") { withContext(Dispatchers.IO) { registry.rescan(folder.id) } } },
                        onRemove = { work("Removing…") { withContext(Dispatchers.IO) { registry.remove(folder.id) } } })
                }
            }
        }
    }

    pending?.let { folder ->
        KindsDialog(folder, served,
            onConfirm = { kinds -> pending = null; link(folder.tree, kinds) },
            onDismiss = { pending = null })
    }
}

private class PendingFolder(val tree: Uri, val detected: Map<SoundpackKind, Int>)

/** D2: kinds with entries are pre-checked; an app serving one kind only asks when the folder
 * has nothing of that kind, which usually means the wrong level of folder was picked. */
@Composable
private fun KindsDialog(folder: PendingFolder, served: List<SoundpackKind>, onConfirm: (Set<SoundpackKind>) -> Unit, onDismiss: () -> Unit) {
    val name = DocumentsContract.getTreeDocumentId(folder.tree).substringAfterLast(':')
    if (served.size == 1) {
        val kind = served.single()
        if ((folder.detected[kind] ?: 0) > 0) {
            LaunchedEffect(folder) { onConfirm(setOf(kind)) }
            return
        }
        AlertDialog(onDismissRequest = onDismiss,
            title = { Text("No ${kind.id.uppercase()} files found") },
            text = { Text("$name has no ${kind.id.uppercase()} entries. Add it anyway?") },
            confirmButton = { TextButton(onClick = { onConfirm(setOf(kind)) }) { Text("Add anyway") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
        return
    }
    var chosen by remember(folder) { mutableStateOf(served.filter { (folder.detected[it] ?: 0) > 0 }.toSet()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Use $name as") },
        text = {
            Column {
                served.forEach { kind ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = kind in chosen, onCheckedChange = { chosen = if (it) chosen + kind else chosen - kind })
                        Text("${kind.id.uppercase()} (${folder.detected[kind] ?: 0} found)")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen) }, enabled = chosen.isNotEmpty()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun FolderCard(
    folder: FolderRegistry.Folder,
    served: List<SoundpackKind>,
    counts: Map<SoundpackKind, Int>,
    enabled: Boolean,
    onKinds: (Set<SoundpackKind>) -> Unit,
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
                "${folder.fileCount} files · " + folder.kinds.joinToString(" · ") { "${counts[it] ?: 0} ${it.id.uppercase()}" }
            else "Not available: ${folder.scanError}. Link it again.",
                color = if (folder.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            if (folder.available && folder.kinds.any { (counts[it] ?: 0) == 0 })
                Text("Nothing of " + folder.kinds.filter { (counts[it] ?: 0) == 0 }.joinToString { it.id.uppercase() } +
                    " here; check that you linked the right level of folder.", style = MaterialTheme.typography.bodySmall)
            if (served.size > 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    served.forEach { kind ->
                        FilterChip(selected = kind in folder.kinds, enabled = enabled,
                            onClick = {
                                val kinds = if (kind in folder.kinds) folder.kinds - kind else folder.kinds + kind
                                if (kinds.isNotEmpty()) onKinds(kinds)
                            },
                            label = { Text(kind.id.uppercase()) })
                    }
                }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRescan, enabled = enabled) { Text("Rescan") }
                TextButton(onClick = onRemove, enabled = enabled) { Text("Remove") }
                onShare?.let { TextButton(onClick = it, enabled = enabled && folder.available) { Text("Share with Soundpack Manager") } }
            }
        }
    }
}

/** [SoundpackFoldersScreen] as an activity, for apps without Compose screens of their own.
 * Start it from one of the app's own activities, never from a UI another app is showing (an
 * AAP plugin UI in a host): Android blocks that (docs/PLAN.md §3.9, "Where setup happens"). */
class SoundpackFoldersActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val registry = FolderRegistry.get(this)
        setContent {
            MaterialTheme {
                SoundpackFoldersScreen(registry, onDone = { finish() })
            }
        }
    }
}
