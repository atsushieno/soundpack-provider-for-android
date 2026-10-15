package org.androidaudioplugin.soundpack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackIdentity
import org.androidaudioplugin.soundpack.client.SoundpackClient

/** Every entry of [client]'s kind, from every provider the app can read, as a collapsible tree:
 * pack, then the entry's group path, then the entry. A generalisation of sfizz's SfzTree. It
 * updates when packs are installed or folders change. */
@Composable
fun SoundpackPicker(
    client: SoundpackClient,
    selected: SoundpackIdentity?,
    onSelect: (SoundpackClient.Entry) -> Unit,
    modifier: Modifier = Modifier,
    /** Change it to reload the catalog. */
    refreshKey: Any? = null,
) {
    var packs by remember(client) { mutableStateOf<List<PackEntries>?>(null) }
    var failures by remember(client) { mutableStateOf(emptyList<SoundpackClient.Failure>()) }
    var expanded by remember(client) { mutableStateOf<Set<String>?>(null) }
    var seen by remember(client) { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(client, refreshKey) {
        client.catalogFlow()
            .map { catalog -> catalog to catalog.packs.map { pack -> PackEntries(pack, runCatching { client.entries(pack) }.getOrDefault(emptyList())) } }
            .flowOn(Dispatchers.IO)
            .collect { (catalog, loaded) ->
                packs = loaded
                failures = catalog.failures
                // New packs start expanded; what the user collapsed stays collapsed.
                val newPacks = loaded.map { packKey(it.pack) }.toSet() - seen
                expanded = expanded.orEmpty() + newPacks
                seen = seen + newPacks
            }
    }

    val rows = remember(packs, expanded) { flatten(buildTree(packs.orEmpty()), expanded.orEmpty()) }
    Column(modifier) {
        when {
            packs == null -> Text("Looking for ${client.kind.id.uppercase()} content…", Modifier.padding(12.dp))
            rows.isEmpty() -> Text("No ${client.kind.id.uppercase()} content found. Install a pack, or link a folder.", Modifier.padding(12.dp))
        }
        failures.forEach {
            Text("${it.endpoint.authority}: ${it.error.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        LazyColumn {
            items(rows, key = { it.key }) { row ->
                val entry = (row.node as? Node.Leaf)?.entry
                val isSelected = entry != null && selected != null && selected.packId == entry.pack.id && selected.entryPath == entry.path
                Text(
                    text = when (row.node) {
                        is Node.Leaf -> "• "
                        else -> if (row.key in expanded.orEmpty()) "▼ " else "▶ "
                    } + row.node.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (row.node is Node.Leaf) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable {
                            if (entry != null) onSelect(entry)
                            else expanded = expanded.orEmpty().let { if (row.key in it) it - row.key else it + row.key }
                        }
                        .padding(start = (12 + row.depth * 16).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                )
                HorizontalDivider()
            }
        }
    }
}

private class PackEntries(val pack: SoundpackClient.Pack, val entries: List<SoundpackClient.Entry>)

private sealed interface Node {
    val title: String

    class Group(override val title: String, val key: String) : Node {
        val children = mutableListOf<Node>()
    }

    class Leaf(override val title: String, val entry: SoundpackClient.Entry) : Node
}

private class Row(val depth: Int, val node: Node, val key: String)

private fun packKey(pack: SoundpackClient.Pack) = "/${pack.id}"

private fun buildTree(packs: List<PackEntries>): List<Node.Group> = packs.map { (pack, entries) ->
    val source = if (pack.source == SoundpackContract.SOURCE_FOLDER) "folder" else "app"
    val root = Node.Group("${pack.label} ($source)", packKey(pack))
    for (entry in entries) {
        var group = root
        for (segment in entry.groupPath.split('/').filter { it.isNotEmpty() }) {
            val key = "${group.key}/$segment"
            group = group.children.filterIsInstance<Node.Group>().firstOrNull { it.key == key }
                ?: Node.Group(segment, key).also { group.children += it }
        }
        group.children += Node.Leaf(entry.label, entry)
    }
    root.sort()
    root
}

private operator fun PackEntries.component1() = pack
private operator fun PackEntries.component2() = entries

private fun Node.Group.sort() {
    children.sortWith(compareBy({ it is Node.Leaf }, { it.title.lowercase() }))
    children.filterIsInstance<Node.Group>().forEach { it.sort() }
}

private fun flatten(roots: List<Node.Group>, expanded: Set<String>): List<Row> {
    val rows = mutableListOf<Row>()
    fun visit(node: Node, depth: Int) {
        when (node) {
            is Node.Group -> {
                rows += Row(depth, node, node.key)
                if (node.key in expanded) node.children.forEach { visit(it, depth + 1) }
            }
            is Node.Leaf -> rows += Row(depth, node, "${node.entry.pack.id}:${node.entry.path}")
        }
    }
    roots.forEach { visit(it, 0) }
    return rows
}
