package com.example.docscanner

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private var bitmap: Bitmap? = null
    private var lastFile: File? = null
    private var lastMime: String = "application/pdf"
    private var cameraUri: Uri? = null
    private lateinit var preview: ImageView
    private lateinit var btnShare: Button

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) cameraUri?.let { loadImage(it) }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) loadImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        btnShare = findViewById(R.id.btnShare)
        btnShare.isEnabled = false

        findViewById<Button>(R.id.btnCamera).setOnClickListener { openCamera() }
        findViewById<Button>(R.id.btnGallery).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.btnPdf).setOnClickListener { export("pdf") }
        findViewById<Button>(R.id.btnWord).setOnClickListener { export("docx") }
        btnShare.setOnClickListener { share() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- اختيار الصورة ----------

    private fun openCamera() {
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            cameraUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            takePicture.launch(cameraUri)
        } catch (e: ActivityNotFoundException) {
            toast("ما لكيت تطبيق كاميرا")
        } catch (e: Exception) {
            toast("خطأ بالكاميرا: ${e.message}")
        }
    }

    private fun loadImage(uri: Uri) {
        val bmp = decodeUri(uri)
        if (bmp == null) {
            toast("ما كدرت أفتح الصورة")
            return
        }
        bitmap = bmp
        preview.setImageBitmap(bmp)
        lastFile = null
        btnShare.isEnabled = false
    }

    private fun decodeUri(uri: Uri): Bitmap? {
        val maxSide = 2000
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val orientation = try {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        return if (m.isIdentity) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // ---------- التصدير ----------

    private fun export(type: String) {
        val bmp = bitmap ?: run {
            toast("اختار صورة أول")
            return
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "scan_$stamp.$type")
        try {
            if (type == "pdf") {
                saveAsPdf(bmp, file)
                lastMime = "application/pdf"
            } else {
                saveAsDocx(bmp, file)
                lastMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            }
            lastFile = file
            btnShare.isEnabled = true
            toast("تم الحفظ ✅ اضغط مشاركة لفتح الملف")
        } catch (e: Exception) {
            toast("صار خطأ: ${e.message}")
        }
    }

    private fun share() {
        val f = lastFile ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = lastMime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "مشاركة الملف"))
    }

    private fun saveAsPdf(bmp: Bitmap, out: File) {
        val pageW = 595
        val pageH = 842
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        val scale = min(pageW.toFloat() / bmp.width, pageH.toFloat() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (pageW - w) / 2f
        val top = (pageH - h) / 2f
        page.canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), null)
        doc.finishPage(page)
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }

    // ملف Word (docx) بدون مكتبات: هو ملف zip فيه XML وصورة
    private fun saveAsDocx(bmp: Bitmap, out: File) {
        val jpg = ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()

        val maxW = 6_600_000.0
        val maxH = 9_700_000.0
        val ratio = min(maxW / bmp.width, maxH / bmp.height)
        val cx = (bmp.width * ratio).toLong()
        val cy = (bmp.height * ratio).toLong()

        ZipOutputStream(FileOutputStream(out)).use { zip ->
            fun add(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            add("[Content_Types].xml", CONTENT_TYPES.toByteArray())
            add("_rels/.rels", ROOT_RELS.toByteArray())
            add("word/document.xml", documentXml(cx, cy).toByteArray())
            add("word/_rels/document.xml.rels", DOC_RELS.toByteArray())
            add("word/media/image1.jpg", jpg)
        }
    }

    private val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

    private val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

    private val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.jpg"/></Relationships>"""

    private fun documentXml(cx: Long, cy: Long) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body><w:p><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="1" name="Picture 1"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="0" name="image1.jpg"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p><w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="720" w:right="720" w:bottom="720" w:left="720" w:header="0" w:footer="0" w:gutter="0"/></w:sectPr></w:body></w:document>"""
}