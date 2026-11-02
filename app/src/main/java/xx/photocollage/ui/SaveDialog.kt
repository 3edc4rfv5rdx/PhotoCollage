package xx.photocollage.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import xx.photocollage.R
import xx.photocollage.defaultImageName
import xx.photocollage.jpegFileName

/**
 * The name a collage is saved under, ready to take as it is: the app's and
 * the moment. Typed as the user likes; [jpegFileName] makes a file name of it.
 */
@Composable
fun SaveDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(defaultImageName()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_image)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.file_name)) },
                suffix = { Text(".jpg") },
            )
        },
        confirmButton = { DialogConfirmButton(stringResource(R.string.save_image)) { onSave(name) } },
        dismissButton = { DialogDismissButton(stringResource(R.string.cancel), onDismiss) },
    )
}
