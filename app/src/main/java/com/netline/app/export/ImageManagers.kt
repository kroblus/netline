package com.netline.app.export

import android.content.*
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ImageSaveManager(private val context: Context) {
    suspend fun save(image: ExportImage) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, image.file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/NETLINE/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "无法创建相册图片" }
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output -> image.file.inputStream().use { it.copyTo(output) } }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) { "相册发布失败" }
            uri
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
}
class ImageShareManager(private val context: Context) {
    fun share(file: File) {
        check(file.isFile) { "临时图片已清理，请重新生成" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "NETLINE report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { context.startActivity(Intent.createChooser(send, "SHARE NETLINE REPORT")) }
        catch (e: ActivityNotFoundException) { throw IllegalStateException("没有可接收图片的应用", e) }
    }
}
