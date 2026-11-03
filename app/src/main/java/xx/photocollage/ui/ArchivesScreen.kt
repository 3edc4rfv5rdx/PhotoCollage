package xx.photocollage.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xx.photocollage.ARCHIVE_MIME
import xx.photocollage.AppSettings
import xx.photocollage.ArchiveFile
import xx.photocollage.CollageViewModel
import xx.photocollage.R
import xx.photocollage.treeLabel

/**
 * The types an archive of drafts may be given as: its own, and what a file
 * that went through another app or a computer may be called instead.
 */
private val ARCHIVE_TYPES = arrayOf(ARCHIVE_MIME, "application/x-zip-compressed", "application/octet-stream")

/**
 * The drafts' archives in the app's folder, by name, the newest first,
 * when each was made and how large it is: a tap opens one for its drafts to
 * be chosen from. Other file takes one from anywhere else through the
 * system's file picker — one sent from another phone, say.
 */
@Composable
fun ArchivesScreen(vm: CollageViewModel, busy: Boolean, onBack: () -> Unit) {
    val folder by AppSettings.folder.collectAsState()
    // Listed afresh each time this is shown: back from an import, or with a file put there meanwhile.
    val listing by produceState<Loaded<List<ArchiveFile>>>(Loaded.Loading, folder) {
        value = loadedOf(folder?.let { vm.archivesIn(it) })
    }
    // The system's picker, for a file outside the folder: out of the app's sight anywhere else.
    val pickOther = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::openArchive)
    }
    AppScreen(title = stringResource(R.string.import_drafts), onBack = onBack) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        folder?.let {
            Text(
                treeLabel(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val shown = listing) {
                Loaded.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                Loaded.Unavailable -> Unit
                is Loaded.Found -> if (shown.value.isEmpty()) {
                    Text(
                        stringResource(R.string.no_archives),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(shown.value, key = { it.uri.toString() }) { archive ->
                            ArchiveRow(archive, enabled = !busy) { vm.openArchive(archive.uri) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            DialogConfirmButton(stringResource(R.string.other_file), enabled = !busy) { pickOther.launch(ARCHIVE_TYPES) }
        }
    }
}

/** One archive: its name, and under it when it was made and how large it is. */
@Composable
private fun ArchiveRow(archive: ArchiveFile, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(archive.name, style = MaterialTheme.typography.titleMedium)
        Text(
            dateTimeText(archive.modified) + " · " + Formatter.formatShortFileSize(context, archive.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
