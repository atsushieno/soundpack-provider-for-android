package org.androidaudioplugin.soundpack.manager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.host.FolderRegistry

/** Consent (docs/PLAN.md §3.6): an app asks to read one kind. On "Allow", the result carries
 * `content://<this app>.soundpack.<kind>/` with READ | PERSISTABLE | PREFIX, which the app
 * persists (verified in spike S2). */
class ConnectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        val kind = intent.getStringExtra(SoundpackContract.Hub.EXTRA_KIND)?.let { SoundpackKind.builtIn(it) }
        val label = caller?.let { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() } ?: caller
        val folders = kind?.let { k -> FolderRegistry.get(this).folders().count { k in it.kinds } } ?: 0
        setResult(RESULT_CANCELED)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (caller == null || kind == null) {
                            Text("Nothing to connect", style = MaterialTheme.typography.headlineSmall)
                            Text("Open this from the app that wants to use your soundpacks.")
                            Button(onClick = { finish() }) { Text("Close") }
                            return@Column
                        }
                        val name = kind.id.uppercase()
                        Text("Connect $label?", style = MaterialTheme.typography.headlineSmall)
                        Text("$label wants to read the $name content shared in Soundpack Manager " +
                            "($folders folder${if (folders == 1) "" else "s"} now, and any you share as $name later).")
                        // D2: say plainly what a grant covers.
                        Text(if (kind.readScope == SoundpackKind.ReadScope.ENTRIES_ONLY)
                            "It will be able to read every $name file in folders shared as $name."
                        else "It will be able to read every file in folders shared as $name, not only the instruments.")
                        Text("You can disconnect it any time under Connected apps.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { allow(caller, kind, label ?: caller) }) { Text("Allow") }
                            OutlinedButton(onClick = { finish() }) { Text("Don't allow") }
                        }
                    }
                }
            }
        }
    }

    private fun allow(caller: String, kind: SoundpackKind, label: String) {
        HubConnections.add(this, caller, kind, label)
        setResult(RESULT_OK, Intent().setData(HubConnections.rootUri(this, kind)).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        finish()
    }
}
