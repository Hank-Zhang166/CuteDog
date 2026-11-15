package com.cutedog.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class DocumentTransferModel : ViewModel() { var exportText: String? = null; var importText: String? = null }

@Composable
internal fun rememberJsonExporter(key: String, fileName: String, contents: suspend () -> String): () -> Unit {
    val context = LocalContext.current
    val model = remember(context, key) { ViewModelProvider(context as ComponentActivity).get("document_$key", DocumentTransferModel::class.java) }
    val scope = rememberCoroutineScope()
    val currentContents by rememberUpdatedState(contents)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = model.exportText ?: error("导出已中断，请重新选择导出。")
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("无法写入这个位置，请选择其他文件夹。") }
                context.message("文件已保存到你选择的位置。")
            } catch (e: Exception) { context.message(e.message ?: "文件保存失败，请重试。") }
            finally { model.exportText = null }
        } else model.exportText = null
    }
    return {
        scope.launch {
            try { model.exportText = withContext(Dispatchers.IO) { currentContents() }; launcher.launch(fileName) }
            catch (e: Exception) { context.message(e.message ?: "无法准备导出文件，请重试。") }
        }
    }
}

internal fun readBoundedJson(context: Context, uri: Uri, maximum: Int = 2 * 1024 * 1024): String {
    return context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= maximum) { "文件大于 2 MB，请选择 CuteDog 导出的事项备份。" }
            output.write(buffer, 0, count)
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
    } ?: error("无法读取文件，请重新选择。")
}
