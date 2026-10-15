package org.androidaudioplugin.soundpack.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** The Soundpack Manager: folders shared with every soundpack app, and a browser over
 * everything installed. */
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

private val tabs = listOf("Folders", "Browse")

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
            else -> BrowseTab()
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
