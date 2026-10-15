package org.androidaudioplugin.soundpack.manager

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.androidaudioplugin.soundpack.SoundpackIdentity
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.client.SoundpackClient
import org.androidaudioplugin.soundpack.host.FolderRegistry
import org.androidaudioplugin.soundpack.host.ui.SoundpackFoldersScreen
import org.androidaudioplugin.soundpack.ui.SoundpackPicker
import java.util.Date

/** The Soundpack Manager: folders shared with all apps, a browser over everything installed,
 * and the apps connected to it. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { HubScreen() }
            }
        }
    }
}

private val tabs = listOf("Folders", "Browse", "Connected apps")

@Composable
private fun HubScreen() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }) }
        }
        when (tab) {
            0 -> SoundpackFoldersScreen(remember { FolderRegistry.get(context) })
            1 -> BrowseTab()
            else -> ConnectionsTab()
        }
    }
}

@Composable
private fun BrowseTab() {
    val context = LocalContext.current
    var kind by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<SoundpackKind, String>({ it.id }, { SoundpackKind.builtIn(it) })) {
        mutableStateOf(SoundpackKind.SFZ)
    }
    val client = remember(kind) { SoundpackClient(context, kind) }
    var selected by remember(kind) { mutableStateOf<SoundpackIdentity?>(null) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Everything installed or shared here, as apps see it.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoundpackKind.BUILT_IN.forEach { FilterChip(selected = kind == it, onClick = { kind = it }, label = { Text(it.id.uppercase()) }) }
        }
        SoundpackPicker(client, selected, onSelect = { selected = client.identityOf(it) }, Modifier.weight(1f).fillMaxWidth())
        selected?.let { Text(it.format(), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ConnectionsTab() {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var connections by remember { mutableStateOf(emptyList<HubConnections.Connection>()) }
    LaunchedEffect(revision) { connections = HubConnections.all(context) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Apps connect from their own screens, one kind at a time. Disconnecting takes effect at once; " +
            "the app has to ask again.", style = MaterialTheme.typography.bodyMedium)
        if (connections.isEmpty())
            Text("No apps connected.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(connections, key = { "${it.packageName}|${it.kind.id}" }) { connection ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${connection.label}: ${connection.kind.id.uppercase()}", style = MaterialTheme.typography.titleMedium)
                        Text("${connection.packageName} · since ${DateFormat.getDateFormat(context).format(Date(connection.connectedAt))}",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            HubConnections.revoke(context, connection)
                            revision++
                        }) { Text("Disconnect") }
                    }
                }
            }
        }
    }
}
