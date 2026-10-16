package org.androidaudioplugin.soundpack.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.soundpack.FilePatterns
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.host.FolderRegistry

/** The resource types folders are linked as: the default ones, which can be edited and reset,
 * and ones the user adds, for an app that reads its own file format. Apps find an added type by
 * its file types or label, and read it like any other kind. The definitions are kept in this
 * app's settings ([org.androidaudioplugin.soundpack.host.ResourceTypes]). */
@Composable
fun TypesScreen(registry: FolderRegistry, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var kinds by remember { mutableStateOf(registry.types.all()) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var deleting by remember { mutableStateOf<SoundpackKind?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(registry) {
        val observation = registry.types.observe { revision++ }
        onDispose { observation.close() }
    }
    LaunchedEffect(revision) { kinds = withContext(Dispatchers.IO) { registry.types.all() } }

    /** Saves in the background: folders linked as a changed type are scanned again. */
    fun work(block: () -> String) {
        message = null
        scope.launch {
            message = try {
                val id = withContext(Dispatchers.IO) { block() }
                withContext(Dispatchers.IO) { registry.typeChanged(id) }
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }
    }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Resource types", style = MaterialTheme.typography.headlineSmall)
        Text("A type says what a folder is for, and its file types say which files in it are entries. You choose the " +
            "type before you pick a folder. Add a type for an app that reads a format of its own; apps find it by its " +
            "file types or its label. Default types can be edited and reset.",
            style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { editing = Editing(null) }) { Text("Add a type") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(kinds, key = { it.id }) { kind ->
                val builtIn = kind.isBuiltIn
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(kind.label, style = MaterialTheme.typography.titleMedium)
                        Text("${FilePatterns.format(kind.filePatterns)} · id ${kind.id}" + if (builtIn) " · default" else "",
                            style = MaterialTheme.typography.bodySmall)
                        if (kind.description.isNotEmpty())
                            Text(kind.description, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = Editing(kind) }) { Text("Edit") }
                            if (builtIn && registry.types.isEdited(kind.id))
                                TextButton(onClick = { work { registry.types.reset(kind.id); kind.id } }) { Text("Reset") }
                            if (!builtIn)
                                TextButton(onClick = { deleting = kind }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { target ->
        TypeDialog(target.kind, onDismiss = { editing = null }) { label, description, patterns ->
            editing = null
            work {
                val kind = target.kind
                if (kind == null)
                    registry.types.add(label, description, patterns).id
                else {
                    registry.types.update(kind.withPresentation(label, patterns, description))
                    kind.id
                }
            }
        }
    }

    deleting?.let { kind ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("Delete ${kind.label}?") },
            text = { Text("Folders linked as ${kind.label} stop being used as it, and apps can no longer find it.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { withContext(Dispatchers.IO) { registry.removeType(kind.id) } }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

/** The type being edited; null for a new one. */
private class Editing(val kind: SoundpackKind?)

@Composable
private fun TypeDialog(kind: SoundpackKind?, onDismiss: () -> Unit, onSave: (String, String, List<String>) -> Unit) {
    var label by remember { mutableStateOf(kind?.label.orEmpty()) }
    var description by remember { mutableStateOf(kind?.description.orEmpty()) }
    var patterns by remember { mutableStateOf(kind?.let { FilePatterns.format(it.filePatterns) }.orEmpty()) }
    val parsed = FilePatterns.parse(patterns)
    val valid = label.isNotBlank() && parsed.isNotEmpty() && parsed.all(FilePatterns::isValid)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (kind == null) "Add a type" else "Edit ${kind.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true)
                OutlinedTextField(patterns, { patterns = it }, label = { Text("File types, e.g. *.inst, *.wav") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, minLines = 2)
                if (kind == null)
                    Text("The type's id is made from its label now, and stays when you rename it.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(label, description, parsed) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
