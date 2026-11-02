package xx.photocollage.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Offers the saved collage [file] to whichever app the user picks, readable
 * through the app's FileProvider: another app cannot read a path of ours.
 */
fun shareImage(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/jpeg")
        .putExtra(Intent.EXTRA_STREAM, uri)
    // The read grant goes with the clip, not the extra: set here, the chooser hands it on to the app picked.
    send.clipData = ClipData.newRawUri(null, uri)
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (e: ActivityNotFoundException) {
        // The system chooser is always there; nothing to say if it is not.
    }
}
