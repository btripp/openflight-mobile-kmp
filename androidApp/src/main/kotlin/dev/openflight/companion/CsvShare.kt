// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The CSV export's MIME type. */
internal const val CSV_MIME_TYPE = "text/csv"

private const val EXPORT_DIR = "exports"
private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")

/**
 * Plan R5b: returns a `(csv, filename)` callback that writes the session export to
 * `cacheDir/exports/` (off the main thread) and opens the Android share sheet for it
 * (`ACTION_SEND`, `text/csv`, a read grant on the `FileProvider` URI).
 */
@Composable
fun rememberCsvSharer(): (csv: String, filename: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        { csv, filename ->
            scope.launch {
                val file = withContext(Dispatchers.IO) { writeExport(context, csv, filename) }
                context.startActivity(shareIntent(context, file))
            }
        }
    }
}

/** Writes [csv] as `cacheDir/exports/<filename>`, replacing any earlier export with the same name. */
internal fun writeExport(
    context: Context,
    csv: String,
    filename: String,
): File {
    val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
    val safeName = filename.replace(UNSAFE_FILENAME_CHARS, "_").ifEmpty { "openflight_shots.csv" }
    return File(dir, safeName).apply { writeText(csv) }
}

/** The chooser around an `ACTION_SEND` of [file], readable by whichever app the user picks. */
internal fun shareIntent(
    context: Context,
    file: File,
): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = CSV_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return Intent.createChooser(send, "Export shots").apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
