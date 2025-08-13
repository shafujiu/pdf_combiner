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

class ImageFromPdfConfig(
    val rescale: ImageScale,
    val compression: CompressionLevel,
    val createOneImage: Boolean,
    val imageFormat: String
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
                val pdfImages: MutableList<Bitmap> = mutableListOf()

                for (pageIndex in 0 until renderer.pageCount) {
                    val page = renderer.openPage(pageIndex)
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val imageName = "image_${pageIndex + 1}.png"
                    pdfImagesPath.add("$outputPath/$imageName")
                    val outputFile = File(outputPath, "$imageName")
                    FileOutputStream(outputFile).use { out ->
                        bitmap.compress(format, config.compression.value, out)
                        pdfImages.add(bitmap)
                    }
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
                        bitmap?.let { pdfImages.add(it) }
                    }
                }
                renderer.close()
                fileDescriptor.close()
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
        val targetWidth = if (maxWidth == -1) orderImagesList[0].width else maxWidth

        val chunkHeightCal = orderImagesList.sumOf { it.height }
        val targetHeight = if (maxHeight == -1) chunkHeightCal else maxHeight * orderImagesList.size
        val result =
                Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)

        val canvas = Canvas(result)
        val paint = Paint()
        canvas.drawColor(Color.parseColor("#FFFFFF"))
        var currentHeight = 0
        for (bitmap in orderImagesList) {
            // 计算缩放后的宽高
            val scaledWidth = targetWidth
            val scaledHeight = if (maxHeight == -1) bitmap.height else maxHeight
            // 缩放 bitmap
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            canvas.drawBitmap(scaledBitmap, 0f, currentHeight.toFloat(), paint)
            currentHeight += scaledHeight
    
            // 如果不再需要原 bitmap，可以回收 scaledBitmap 的内存（可选）
            scaledBitmap.recycle()
        }

        return result
    }
}