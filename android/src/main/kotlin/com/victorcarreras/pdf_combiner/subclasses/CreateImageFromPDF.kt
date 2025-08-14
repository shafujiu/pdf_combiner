package com.victorcarreras.pdf_combiner.subclasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.graphics.Color
import kotlinx.coroutines.withContext

class ImageFromPdfConfig(
    val rescale: ImageScale,
    val compression: CompressionLevel,
    val createOneImage: Boolean,
    val imageFormat: String,
    val pageNumbers: List<Int>?
)

class CreateImageFromPDF(getContext: Context, getResult: MethodChannel.Result) {

    private var context: Context = getContext
    private var result: MethodChannel.Result = getResult

    @OptIn(DelicateCoroutinesApi::class)
    fun create(
        inputPath: String, outputPath: String, config: ImageFromPdfConfig
    ) {
        val pdfImagesPath: MutableList<String> = mutableListOf()
        var format = Bitmap.CompressFormat.PNG
        when (config.imageFormat) {
            "png" -> format = Bitmap.CompressFormat.PNG
            "jpg" -> format = Bitmap.CompressFormat.JPEG
        }
        
        val pdfFromMultipleImage = GlobalScope.launch(Dispatchers.IO) {
            try {
                val fileDescriptor = ParcelFileDescriptor.open(File(inputPath), ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fileDescriptor)
                val pageNumbers = config.pageNumbers ?: (0 until renderer.pageCount).toList()
                // check if pageNumbers is valid
                if (pageNumbers.any { it < 0 || it >= renderer.pageCount }) {
                    withContext(Dispatchers.Main) {
                        result.error("INVALID_ARGUMENTS", "pageNumbers is invalid", null)
                    }
                    renderer.close()
                    fileDescriptor.close()
                    return@launch
                }

                val pdfImages: MutableList<Bitmap> = mutableListOf()

                for (pageIndex in pageNumbers) {
                    val page = renderer.openPage(pageIndex)
                    val scale = 3.0f
                    val width = (page.width * scale).toInt()
                    val height = (page.height * scale).toInt()
                    // TODO: width height can't use page.width and page.height will be deformed
                    Log.d("pdf_combiner", "width: $width, height: $height")
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    
                    val imageName = "image_${pageIndex + 1}.${config.imageFormat}"
                    pdfImagesPath.add("$outputPath/$imageName")
                    val outputFile = File(outputPath, "$imageName")
                    FileOutputStream(outputFile).use { out ->
                        bitmap.compress(format, config.compression.value, out)
                        pdfImages.add(bitmap)
                    }
                    // bitmap.recycle()
                    page.close()
                }

                if (config.createOneImage) {
                    val filepath = "$outputPath/image.${config.imageFormat}"
                    pdfImagesPath.clear()
                    pdfImagesPath.add(filepath)
                    Log.d("pdf_combiner", "pathfile: $filepath")
                    val bitmap = mergeThemAll(pdfImages, config.rescale.maxWidth, config.rescale.maxHeight)
                    FileOutputStream(filepath).use { out ->
                        bitmap?.compress(format, config.compression.value, out)
                        // bitmap?.let { pdfImages.add(it) }
                    }
                    bitmap?.recycle()
                }
                renderer.close()
                fileDescriptor.close()
                pdfImages.forEach { it?.recycle() }
                pdfImages.clear()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        pdfFromMultipleImage.invokeOnCompletion {
            GlobalScope.launch(Dispatchers.Main) {
                result.success(pdfImagesPath)
            }
        }
    }
    
    private fun mergeThemAll(orderImagesList: List<Bitmap>?, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (orderImagesList.isNullOrEmpty()) {
            this.result.error("400", "MergeError", "Couldn't merge bitmaps")
            return null
        }
        val targetWidth = if (maxWidth == 0) orderImagesList[0].width else maxWidth

        val chunkHeightCal = orderImagesList.sumOf { it.height }
        val targetHeight = if (maxHeight == 0) chunkHeightCal else maxHeight * orderImagesList.size

        Log.d("pdf_combiner", "mergeThemAll targetWidth: $targetWidth, targetHeight: $targetHeight")
        val resultBitmap =
                Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG) // antialiasing
        paint.isFilterBitmap = true              // high quality scaling
        paint.isDither = true                    // reduce color band
        canvas.drawColor(Color.WHITE)
        var currentHeight = 0
        for (bitmap in orderImagesList) {
            // if maxWidth or maxHeight is 0, use original image
            val scaledBitmap = if (maxWidth == 0 && maxHeight == 0) {
                bitmap
            } else {
                val scaledWidth = if (maxWidth == 0) bitmap.width else maxWidth
                val scaledHeight = if (maxHeight == 0) bitmap.height else maxHeight
                Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            }

            canvas.drawBitmap(scaledBitmap, 0f, currentHeight.toFloat(), paint)
            currentHeight += scaledBitmap.height

            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle() // recycle temporary scaled image
            }
        }
        return resultBitmap
    }
}