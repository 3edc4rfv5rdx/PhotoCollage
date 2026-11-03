package xx.photocollage.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xx.photocollage.R

/**
 * Asked before another collage takes the place of one that holds work — a
 * new one by default, or a draft opened, by [question], [saveAndGo] and
 * [goWithout]: the only moment it can be lost, since the collage being
 * edited is kept between starts by itself. The buttons stacked, as their
 * labels are long: the two ways on in the dialog's middle, as wide as the
 * wider of them, and Cancel at the right like any dialog's.
 */
@Composable
fun NewCollageDialog(
    onSaveAndStart: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    @StringRes question: Int = R.string.new_collage_question,
    @StringRes saveAndGo: Int = R.string.save_draft_and_start,
    @StringRes goWithout: Int = R.string.start_without_saving,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(question) + "?") },
        text = { Text(stringResource(R.string.new_collage_warning) + ".") },
        confirmButton = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogConfirmButton(
                        stringResource(saveAndGo),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSaveAndStart,
                    )
                    DialogConfirmButton(
                        stringResource(goWithout),
                        danger = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onStart,
                    )
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    DialogDismissButton(stringResource(R.string.cancel), onDismiss)
                }
            }
        },
    )
}
