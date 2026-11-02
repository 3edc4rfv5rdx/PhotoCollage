package xx.photocollage.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.updater.Updater
import xx.photocollage.AppSettings
import xx.photocollage.DEFAULT_FOLDER_LABEL
import xx.photocollage.R
import xx.photocollage.ThemeMode
import xx.photocollage.currentLanguageTag
import xx.photocollage.folderLabel
import xx.photocollage.setLanguageTag
import xx.photocollage.supportedLanguages

/** Which editor is open; only one can be at a time. */
private enum class Editing { NONE, THEME, ACCENT, LANGUAGE, SAVE_FOLDER }

/** The lines of the save-folder dialog: the default folder, the one picked before if any, and a new pick. */
private enum class FolderOption { DEFAULT, CURRENT, PICK }

/** Theme, accent colour, language, the start-up update check, and the folder a collage is saved into. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val themeMode by AppSettings.themeMode.collectAsState()
    val accentIndex by AppSettings.accentIndex.collectAsState()
    val saveFolder by AppSettings.saveFolder.collectAsState()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        // A grant that cannot be kept is no folder to save into later: the setting stays as it was.
        uri?.let { runCatching { AppSettings.setSaveFolder(context, it) } }
    }

    val systemLabel = stringResource(R.string.language_system)
    val languages = remember(context, systemLabel) { supportedLanguages(context, systemLabel) }
    val languageTag = remember(context) { currentLanguageTag(context) }
    val languageLabel = languages.firstOrNull { it.tag == languageTag }?.label ?: systemLabel

    // Saveable: a new theme or language recreates the activity under an open dialog.
    var editing by rememberSaveable { mutableStateOf(Editing.NONE) }
    // The updater keeps this flag in its own preferences file.
    var updateCheck by remember { mutableStateOf(Updater.isEnabled(context)) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SettingRow(
            label = stringResource(R.string.setting_theme),
            value = stringResource(themeMode.labelRes()),
            onClick = { editing = Editing.THEME },
        )
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = Editing.ACCENT }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setting_accent),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            AccentSwatch(accentAt(accentIndex), selected = false, onClick = { editing = Editing.ACCENT })
        }
        HorizontalDivider()
        SettingRow(
            label = stringResource(R.string.setting_language),
            value = languageLabel,
            onClick = { editing = Editing.LANGUAGE },
        )
        HorizontalDivider()
        SwitchRow(stringResource(R.string.setting_update_check), updateCheck) {
            updateCheck = it
            Updater.setEnabled(context, it)
        }
        HorizontalDivider()
        SettingRow(
            label = stringResource(R.string.setting_save_folder),
            value = folderLabel(saveFolder),
            onClick = { editing = Editing.SAVE_FOLDER },
        )
    }

    when (editing) {
        Editing.THEME -> ChoiceDialog(
            title = stringResource(R.string.setting_theme),
            options = ThemeMode.entries,
            selected = themeMode,
            label = { stringResource(it.labelRes()) },
            onDismiss = { editing = Editing.NONE },
            onPick = {
                AppSettings.setThemeMode(context, it)
                editing = Editing.NONE
            },
        )

        Editing.ACCENT -> AlertDialog(
            onDismissRequest = { editing = Editing.NONE },
            title = { Text(stringResource(R.string.setting_accent)) },
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    AccentPalette.forEachIndexed { index, color ->
                        AccentSwatch(color, selected = index == accentIndex, onClick = {
                            AppSettings.setAccentIndex(context, index)
                            editing = Editing.NONE
                        })
                    }
                }
            },
            confirmButton = { DialogDismissButton(stringResource(R.string.cancel)) { editing = Editing.NONE } },
        )

        Editing.LANGUAGE -> ChoiceDialog(
            title = stringResource(R.string.setting_language),
            options = languages,
            selected = languages.firstOrNull { it.tag == languageTag },
            label = { it.label },
            onDismiss = { editing = Editing.NONE },
            onPick = {
                editing = Editing.NONE
                // Persisted, and the activity recreated in the new language.
                activity?.let { a -> setLanguageTag(a, it.tag) }
            },
        )

        Editing.SAVE_FOLDER -> ChoiceDialog(
            title = stringResource(R.string.setting_save_folder),
            options = if (saveFolder == null) {
                listOf(FolderOption.DEFAULT, FolderOption.PICK)
            } else {
                FolderOption.entries
            },
            selected = if (saveFolder == null) FolderOption.DEFAULT else FolderOption.CURRENT,
            label = {
                when (it) {
                    FolderOption.DEFAULT -> DEFAULT_FOLDER_LABEL
                    FolderOption.CURRENT -> folderLabel(saveFolder)
                    FolderOption.PICK -> stringResource(R.string.choose_folder)
                }
            },
            onDismiss = { editing = Editing.NONE },
            onPick = {
                editing = Editing.NONE
                when (it) {
                    FolderOption.DEFAULT -> AppSettings.setSaveFolder(context, null)
                    FolderOption.CURRENT -> Unit
                    FolderOption.PICK -> pickFolder.launch(saveFolder)
                }
            },
        )

        Editing.NONE -> Unit
    }
}

/** A setting that is on or off. The whole row is not clickable: the switch is the control. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
