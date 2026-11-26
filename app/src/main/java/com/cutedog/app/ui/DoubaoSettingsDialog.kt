package com.cutedog.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.cutedog.app.voice.doubao.DoubaoSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DoubaoSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { DoubaoSettings(context) }
    val scope = rememberCoroutineScope()
    var voiceKey by remember { mutableStateOf("") }
    var searchKey by remember { mutableStateOf("") }
    var hasVoiceKey by remember { mutableStateOf(settings.hasVoiceKey) }
    var hasSearchKey by remember { mutableStateOf(settings.hasSearchKey) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Surface(
            Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("豆包实时语音", style = MaterialTheme.typography.headlineSmall)
                Text("用豆包语音控制台的密钥连接 Seeduplex。填写豆包搜索 Custom 版密钥后，小狗可以按需查网。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    voiceKey,
                    { voiceKey = it.take(512) },
                    label = { Text("豆包语音 API Key") },
                    supportingText = { if (hasVoiceKey) Text("已保存。留空沿用。") },
                    modifier = Modifier.fillMaxWidth().testTag("doubao_voice_key"),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !busy,
                )
                OutlinedTextField(
                    searchKey,
                    { searchKey = it.take(512) },
                    label = { Text("豆包搜索 API Key（可选）") },
                    supportingText = { if (hasSearchKey) Text("已保存。留空沿用。") },
                    modifier = Modifier.fillMaxWidth().testTag("doubao_search_key"),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !busy,
                )
                Text("密钥在这台设备上加密保存。", style = MaterialTheme.typography.labelMedium)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        if (!busy) {
                            val voice = voiceKey
                            val search = searchKey
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { settings.save(voice, search) }
                                    voiceKey = ""
                                    searchKey = ""
                                    onDismiss()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (invalid: IllegalArgumentException) {
                                    error = invalid.message ?: "API Key 格式不正确。"
                                } catch (_: Exception) {
                                    error = "无法加密保存密钥，请重试。"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && (voiceKey.isNotBlank() || hasVoiceKey),
                ) { Text(if (busy) "正在保存…" else "保存豆包设置") }
                if (hasVoiceKey || hasSearchKey) {
                    TextButton(
                        onClick = {
                            settings.clear()
                            hasVoiceKey = false
                            hasSearchKey = false
                            voiceKey = ""
                            searchKey = ""
                            error = null
                        },
                        enabled = !busy,
                        modifier = Modifier.align(Alignment.Start),
                    ) { Text("清除本机豆包密钥") }
                }
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                    Text("关闭")
                }
            }
        }
    }
}
