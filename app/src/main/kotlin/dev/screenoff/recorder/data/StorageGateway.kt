package dev.screenoff.recorder.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import dev.screenoff.core.RecMode
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * All file work. Clips are recorded into a private staging folder and moved
 * into the library when finalized: either the app's own movies folder or the
 * folder the owner allocated with the system picker.
 *
 * This class only ever deletes URIs that it handed out itself.
 */
class StorageGateway(private val context: Context) {

    data class Published(val uri: String, val fellBackToAppFolder: Boolean)

    private val moviesRoot: File
        get() = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "Movies")

    private val stagingDir: File get() = File(moviesRoot, "staging").apply { mkdirs() }

    fun newStagingFile(mode: RecMode, startedAtWallMs: Long): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAtWallMs))
        return File(stagingDir, "${stamp}_${mode.name.lowercase(Locale.US)}.mp4")
    }

    /** Moves a finalized staging file into the library and returns its URI. */
    @Throws(IOException::class)
    fun publish(staging: File, mode: RecMode, folderTreeUri: String?): Published {
        if (folderTreeUri != null) {
            try {
                return Published(copyToTree(staging, mode, Uri.parse(folderTreeUri)), false)
            } catch (e: Exception) {
                // The allocated folder is unavailable. Keep the clip rather than lose it.
                return Published(moveToAppFolder(staging, mode), true)
            }
        }
        return Published(moveToAppFolder(staging, mode), false)
    }

    private fun moveToAppFolder(staging: File, mode: RecMode): String {
        val dir = File(moviesRoot, mode.name.lowercase(Locale.US)).apply { mkdirs() }
        val target = File(dir, staging.name)
        if (!staging.renameTo(target)) {
            staging.copyTo(target, overwrite = true)
            staging.delete()
        }
        return Uri.fromFile(target).toString()
    }

    private fun copyToTree(staging: File, mode: RecMode, treeUri: Uri): String {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Folder not reachable")
        if (!tree.canWrite()) throw IOException("Folder not writable")
        val sub = mode.name.lowercase(Locale.US)
        val dir = tree.findFile(sub)?.takeIf { it.isDirectory } ?: tree.createDirectory(sub)
            ?: throw IOException("Cannot create $sub")
        val doc = dir.createFile("video/mp4", staging.name) ?: throw IOException("Cannot create file")
        try {
            val out = context.contentResolver.openOutputStream(doc.uri) ?: throw IOException("Cannot open file")
            out.use { o -> staging.inputStream().use { it.copyTo(o, 1 shl 16) } }
        } catch (e: Exception) {
            runCatching { doc.delete() }
            throw e
        }
        staging.delete()
        return doc.uri.toString()
    }

    fun delete(uri: String): Boolean = try {
        val u = Uri.parse(uri)
        if (u.scheme == "file") File(requireNotNull(u.path)).delete()
        else DocumentsContract.deleteDocument(context.contentResolver, u)
    } catch (e: Exception) {
        false
    }

    fun exists(uri: String): Boolean = try {
        val u = Uri.parse(uri)
        if (u.scheme == "file") File(requireNotNull(u.path)).exists()
        else DocumentFile.fromSingleUri(context, u)?.exists() == true
    } catch (e: Exception) {
        false
    }

    /** A URI another app (a video player) can open. */
    fun viewUri(uri: String): Uri {
        val u = Uri.parse(uri)
        return if (u.scheme == "file") {
            FileProvider.getUriForFile(context, context.packageName + ".files", File(requireNotNull(u.path)))
        } else u
    }

    fun freeBytes(): Long = try {
        StatFs(moviesRoot.apply { mkdirs() }.path).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    /** Staging only ever holds the file being written, so leftovers are unplayable crash debris. */
    fun clearStaging() {
        stagingDir.listFiles()?.forEach { it.delete() }
    }

    /** Human readable name of the allocated folder, for the settings screen. */
    fun folderLabel(folderTreeUri: String?): String? =
        folderTreeUri?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull() }
}
