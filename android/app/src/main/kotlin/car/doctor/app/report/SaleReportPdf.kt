package car.doctor.app.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import car.doctor.core.care.SaleReport
import java.io.File
import java.io.FileOutputStream

/** Receipt photos: private app storage, copied in and shrunk so the report stays small. */
object Receipts {
    private const val MAX_SIDE = 1600

    fun dir(context: Context): File = File(context.filesDir, "receipts").apply { mkdirs() }

    /** Copies the picked photo into private storage. Returns the stored file name, or null. */
    fun importPhoto(context: Context, uri: Uri, serviceId: String): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        val name = "$serviceId-${System.currentTimeMillis()}.jpg"
        FileOutputStream(File(dir(context), name)).use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        return name
    }

    fun file(context: Context, name: String): File = File(dir(context), name)
}

/** Renders the sale report as an A4 PDF (Hebrew, right to left), receipts on their own pages. */
object SaleReportPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 40

    fun write(context: Context, report: SaleReport, version: String): File {
        val out = File(File(context.cacheDir, "reports").apply { mkdirs() }, "car-report.pdf")
        val doc = PdfDocument()
        val title = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 18f; isFakeBoldText = true; color = Color.BLACK }
        val head = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 13f; isFakeBoldText = true; color = Color.BLACK }
        val body = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = Color.rgb(30, 30, 30) }
        val small = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.rgb(90, 90, 90) }

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M.toFloat()
        }
        fun text(t: String, paint: TextPaint, gapAfter: Float = 4f) {
            val layout = StaticLayout.Builder.obtain(t, 0, t.length, paint, W - 2 * M)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                .build()
            if (page == null || y + layout.height > H - M) newPage()
            val c = page!!.canvas
            c.save()
            c.translate(M.toFloat(), y)
            layout.draw(c)
            c.restore()
            y += layout.height + gapAfter
        }
        fun section(name: String) {
            y += 8f
            text(name, head, 6f)
        }

        newPage()
        text("דו״ח רכב למכירה · רופא רכב", title, 6f)
        text(report.vehicleHe, head, 2f)
        text("הופק: ${report.generatedAt.take(16).replace('T', ' ')} · גרסה $version", small)
        text("מבוסס על בדיקה מעמיקה מ-${report.basisDate} · ${report.realScans} סריקות ממתאם אמיתי בהיסטוריה", small)

        section("זיהוי")
        text("מספר שלדה (VIN) שנקרא מהרכב: ${report.vinRead ?: "הרכב לא דיווח"}", body)
        report.vinNoteHe?.let { text(it, small) }
        report.odometerHe?.let { text("קילומטראז׳: $it", body) }

        section("מצב נוכחי")
        text("אין קודי תקלה שמורים, ממתינים או קבועים.", body)
        report.readinessHe.forEach { text("• $it", body, 2f) }
        report.modulesHe.forEach { text("• $it", body, 2f) }

        section("היסטוריית תקלות ותיקונים")
        if (report.codes.isEmpty()) text("לא נרשמו קודי תקלה בסריקות שנעשו באפליקציה.", body)
        report.codes.forEach { c ->
            text("${c.code} · ${c.descriptionHe}", body, 1f)
            val fix = c.repair?.let { "תוקן: טיפול ב-${it.date}${it.garageName.takeIf { g -> g.isNotBlank() }?.let { g -> " · $g" } ?: ""} · קבלה מצורפת" } ?: ""
            text("הופיע ${c.firstSeen}–${c.lastSeen}. $fix", small)
        }

        section("היסטוריית טיפולים")
        if (report.services.isEmpty()) text("לא נרשמו טיפולים.", body)
        report.services.forEach { s ->
            val parts = listOfNotNull(
                s.date, s.kind.labelHe,
                s.reading?.let { "%,d %s".format(it, s.unit.shortHe) },
                s.garageName.takeIf { it.isNotBlank() },
                if (s.receiptFile != null) "קבלה מצורפת" else null,
            )
            text("• " + parts.joinToString(" · "), body, 1f)
            if (s.noteHe.isNotBlank()) text(s.noteHe, small)
        }

        if (report.visitsHe.isNotEmpty()) {
            section("ביקורי מוסך עם סריקה לפני ואחרי")
            report.visitsHe.forEach { text("• $it", body, 2f) }
        }

        section("הבהרות")
        report.disclaimersHe.forEach { text("• $it", small, 2f) }

        // Receipts, one per page, scaled to fit.
        report.services.filter { it.receiptFile != null }.forEach { s ->
            val bitmap = BitmapFactory.decodeFile(Receipts.file(context, s.receiptFile!!).path) ?: return@forEach
            newPage()
            text("קבלה · ${s.date} · ${s.kind.labelHe}${s.garageName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}", head, 8f)
            val maxW = W - 2 * M
            val maxH = (H - M - y).toInt()
            val scale = minOf(maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height)
            val w = (bitmap.width * scale).toInt()
            val h = (bitmap.height * scale).toInt()
            val left = (W - w) / 2
            page!!.canvas.drawBitmap(bitmap, null, Rect(left, y.toInt(), left + w, y.toInt() + h), null)
        }

        page?.let { doc.finishPage(it) }
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }
}
