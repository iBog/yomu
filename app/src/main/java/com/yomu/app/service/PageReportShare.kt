package com.yomu.app.service

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.FileProvider
import com.yomu.app.R
import com.yomu.pipeline.PipelineResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The page the overlay last showed, in memory only, so the reader can report it. A recalled page
 * carries the result it recalled, with the capture that recalled it.
 */
class PageRecord(val capture: Bitmap, val page: PipelineResult, val settings: PageSettings, val recalled: Boolean)

/**
 * Writes [record]'s report as one zip in app cache, deleting any earlier one, and returns the share
 * sheet for it. Nothing is sent from here: the reader picks where the zip goes (ADR-0018).
 */
fun Context.pageReportShareIntent(record: PageRecord): Intent {
    val png = ByteArrayOutputStream().also { record.capture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    val origin = ReportOrigin(
        appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
        device = "${Build.MANUFACTURER} ${Build.MODEL}"
    )
    val zip = nextReportFile(File(cacheDir, "reports"), System.currentTimeMillis())
    ZipOutputStream(zip.outputStream()).use { out ->
        pageReportFiles(png, record.page, record.settings, record.recalled, origin).forEach { (name, bytes) ->
            out.putNextEntry(ZipEntry(name))
            out.write(bytes)
            out.closeEntry()
        }
    }
    val uri = FileProvider.getUriForFile(this, "$packageName.reports", zip)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The chooser passes the read grant on only through ClipData.
    send.clipData = ClipData.newRawUri(null, uri)
    return Intent.createChooser(send, getString(R.string.service_report_chooser_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Clears [dir] of earlier reports and names the next one. A name per report means an app still
 * holding an earlier report's link finds nothing, never a later page.
 */
internal fun nextReportFile(dir: File, now: Long): File {
    dir.mkdirs()
    dir.listFiles()?.forEach { it.delete() }
    return File(dir, "yomu-page-report-$now.zip")
}
