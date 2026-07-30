package dev.bill.app.notification

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bill.app.BillApplication
import dev.bill.core.designsystem.theme.BillTheme
import dev.bill.source.contract.NotificationField
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class NotificationTemplateSamplerActivity : ComponentActivity() {
    private val hasNotificationAccess = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as BillApplication)
            .container
            .notificationTemplateSamplingController
        val listenerHealth = (application as BillApplication)
            .container
            .notificationCaptureHealth
        setContent {
            BillTheme {
                val health by listenerHealth.state.collectAsStateWithLifecycle()
                NotificationTemplateSamplerScreen(
                    controller = controller,
                    hasNotificationAccess = hasNotificationAccess.value,
                    hasListenerConnection = health.hasListenerConnection,
                    onOpenNotificationAccess = {
                        openNotificationListenerSettings(this)
                    },
                    onRequestListenerRebind = {
                        requestNotificationListenerRebind(this)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasNotificationAccess.value = AndroidNotificationListenerAccess(this).isGranted()
    }
}

@Composable
private fun NotificationTemplateSamplerScreen(
    controller: NotificationTemplateSamplingController,
    hasNotificationAccess: Boolean,
    hasListenerConnection: Boolean,
    onOpenNotificationAccess: () -> Boolean,
    onRequestListenerRebind: () -> Boolean,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val initialTargets = remember { state.targetPackages }
    val useDefaultTargets = initialTargets.isEmpty()
    var captureAlipay by rememberSaveable {
        mutableStateOf(useDefaultTargets || ALIPAY_PACKAGE in initialTargets)
    }
    var captureWechat by rememberSaveable {
        mutableStateOf(useDefaultTargets || WECHAT_PACKAGE in initialTargets)
    }
    var captureCmbBank by rememberSaveable {
        mutableStateOf(useDefaultTargets || CMB_BANK_PACKAGE in initialTargets)
    }
    var captureSamsungMessages by rememberSaveable {
        mutableStateOf(SAMSUNG_MESSAGES_PACKAGE in initialTargets)
    }
    var additionalPackages by rememberSaveable {
        mutableStateOf(
            initialTargets
                .minus(KNOWN_PACKAGES)
                .sorted()
                .joinToString("\n"),
        )
    }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var previewItems by remember {
        mutableStateOf<List<NotificationTemplateSamplePreview>>(emptyList())
    }
    var previewHasOlder by remember { mutableStateOf(false) }
    var previewBeforeSequence by remember { mutableStateOf<Int?>(null) }
    var newerPageCursors by remember { mutableStateOf<List<Int?>>(emptyList()) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewMessage by remember { mutableStateOf<String?>(null) }
    var previewGeneration by remember { mutableStateOf(0L) }
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }

    suspend fun loadPreviewPage(
        beforeSequenceExclusive: Int?,
        newerCursors: List<Int?>,
    ) {
        if (previewLoading) return
        val requestGeneration = previewGeneration + 1L
        previewGeneration = requestGeneration
        previewLoading = true
        try {
            val page = controller.loadSamplePreviews(
                beforeSequenceExclusive = beforeSequenceExclusive,
                limit = PREVIEW_PAGE_SIZE,
            )
            if (previewGeneration != requestGeneration) return
            when (page.status) {
                NotificationTemplateSamplePreviewStatus.AVAILABLE -> {
                    previewItems = page.samples
                    previewHasOlder = page.hasOlderSamples
                    previewBeforeSequence = beforeSequenceExclusive
                    newerPageCursors = newerCursors
                    previewMessage = null
                }

                NotificationTemplateSamplePreviewStatus.STORAGE_UNAVAILABLE ->
                    previewMessage =
                        "无法读取本地样本预览；采样文件仍保留，下方若有内容是上次成功读取的结果。请先停止后导出检查。"

                NotificationTemplateSamplePreviewStatus.UNAVAILABLE_IN_BUILD ->
                    previewMessage = "当前构建不提供样本预览。"
            }
        } finally {
            if (previewGeneration == requestGeneration) {
                previewLoading = false
            }
        }
    }

    LaunchedEffect(controller) {
        loadPreviewPage(
            beforeSequenceExclusive = null,
            newerCursors = emptyList(),
        )
    }
    LaunchedEffect(state.latestSample?.sequence) {
        val latest = state.latestSample ?: return@LaunchedEffect
        if (previewBeforeSequence == null) {
            val updated = listOf(latest) + previewItems.filterNot {
                it.sequence == latest.sequence
            }
            previewHasOlder = previewHasOlder || updated.size > PREVIEW_PAGE_SIZE
            previewItems = updated.take(PREVIEW_PAGE_SIZE)
        }
    }

    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "通知模板采样（仅 Debug）",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "只记录按下“开始”以后、来自所选精确包名的新通知。不会读取历史通知；One UI 重建进程后会继续，直到你手动停止。",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "警告：样本含通知原文，只保存在 Bill Debug 私有目录。完成后必须停止、脱敏并清除。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()
            PackageChoice(
                label = "支付宝",
                packageName = ALIPAY_PACKAGE,
                checked = captureAlipay,
                enabled = !state.isActive,
                onCheckedChange = { captureAlipay = it },
            )
            PackageChoice(
                label = "微信",
                packageName = WECHAT_PACKAGE,
                checked = captureWechat,
                enabled = !state.isActive,
                onCheckedChange = { captureWechat = it },
            )
            PackageChoice(
                label = "招商银行",
                packageName = CMB_BANK_PACKAGE,
                checked = captureCmbBank,
                enabled = !state.isActive,
                onCheckedChange = { captureCmbBank = it },
            )
            PackageChoice(
                label = "三星短信（只在采集银行短信时开启）",
                packageName = SAMSUNG_MESSAGES_PACKAGE,
                checked = captureSamsungMessages,
                enabled = !state.isActive,
                onCheckedChange = { captureSamsungMessages = it },
            )
            if (captureSamsungMessages) {
                Text(
                    text = "三星短信按包过滤，开启后会记录它产生的所有新通知，不只银行短信；不用时请关闭。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = additionalPackages,
                onValueChange = { additionalPackages = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isActive,
                label = { Text("其他银行 App 精确包名") },
                supportingText = { Text("多个包名用逗号或换行分隔；留空不会捕获其他 App。") },
            )

            Text(
                text = if (hasNotificationAccess) {
                    "系统通知使用权：已授予"
                } else {
                    "系统通知使用权：未授予"
                },
            )
            Text(
                text = if (hasListenerConnection) {
                    "监听服务：已连接"
                } else {
                    "监听服务：尚未连接；开始时会请求系统重绑"
                },
                color = if (hasListenerConnection) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            OutlinedButton(
                onClick = {
                    operationMessage = if (onOpenNotificationAccess()) {
                        "请在系统页面为 Bill 开启通知使用权，然后返回。"
                    } else {
                        "无法打开系统页面，请手动搜索“通知使用权”。"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开通知使用权设置")
            }

            HorizontalDivider()
            Text("状态：${if (state.isActive) "采样中" else "已停止"}")
            if (state.isActive) {
                Text(
                    text = "当前包名：${state.targetPackages.sorted().joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "跨进程已写入 ${state.sampleCount} 条；本进程队列漏记 ${state.droppedCount} 条；本进程正文不可读取 ${state.rejectedContentCount} 条",
            )
            if (state.droppedCount > 0L || state.rejectedContentCount > 0L) {
                Text(
                    text = "本进程已确认存在未保存项目；对应操作需要重做，或改用截图/手工补录。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.isActive && !hasListenerConnection) {
                Text(
                    text = "采样开关仍开着，但监听服务当前未连接；这段时间可能漏记，恢复连接后请重做关键操作。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "系统未向 Bill 交付的通知无法反推出原文或精确数量。支付、收款或动账后若列表里没有对应记录，请把那次操作视为可能漏采并重做，或用截图/手工补录。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.failure != NotificationTemplateSamplingFailure.NONE) {
                Text(
                    text = "错误：${state.failure}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            operationMessage?.let { Text(it) }

            Button(
                onClick = {
                    val targets = buildTargetPackages(
                        captureAlipay = captureAlipay,
                        captureWechat = captureWechat,
                        captureCmbBank = captureCmbBank,
                        captureSamsungMessages = captureSamsungMessages,
                        additionalPackages = additionalPackages,
                    )
                    coroutineScope.launch {
                        val result = controller.start(targets)
                        operationMessage = when (result) {
                            NotificationTemplateSamplingOperationResult.APPLIED -> {
                                val rebindRequested =
                                    hasListenerConnection || onRequestListenerRebind()
                                if (rebindRequested) {
                                    "持续采样已开启，监听服务已连接或已请求系统重绑；只有你手动停止、撤销权限或卸载才会结束。"
                                } else {
                                    "采样开关已保存，但系统重绑请求失败；请打开通知使用权设置重新授权。"
                                }
                            }

                            NotificationTemplateSamplingOperationResult.INVALID_TARGET_PACKAGES ->
                                "包名为空或格式不合法，采样未开启。"

                            NotificationTemplateSamplingOperationResult.STORAGE_UNAVAILABLE ->
                                "私有样本文件不可写，采样未开启。"

                            NotificationTemplateSamplingOperationResult.UNAVAILABLE_IN_BUILD ->
                                "当前构建不提供采样。"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isActive && hasNotificationAccess,
            ) {
                Text("开始采样（保留已有样本）")
            }
            Button(
                onClick = {
                    coroutineScope.launch {
                        operationMessage = when (controller.stop()) {
                            NotificationTemplateSamplingOperationResult.APPLIED ->
                                "采样已停止，原始样本已保留，可以导出脱敏。"

                            else ->
                                "当前进程已停止采样，但停止状态写盘失败；请立即撤销 Bill 的通知使用权。"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.isActive,
            ) {
                Text("停止采样")
            }
            Text(
                text = "停止前先确认刚才的操作已经出现在“已采样内容”里；停止会拒绝仍在内存队列中、尚未写盘的项目。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { showClearConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isActive && !previewLoading,
            ) {
                Text("清除设备端原始样本")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "不设时间、条数或文件大小限制，也不会自动清理；只有真实磁盘写入失败才会停止。Release 构建没有此入口。",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text(
                text = "已采样内容",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "最新记录在上。正文默认折叠，点“展开完整内容”查看；翻页只读取本机私有文件，不会上传。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "每条展示来源包名、系统时间、通知 channel/category，以及标题、正文、副标题、展开正文、摘要这五个允许字段；未提供的字段也会明确标出。不会读取通知历史、键值、按钮、RemoteViews 或链接。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (previewLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("正在读取本地样本…")
                }
            }
            previewMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!previewLoading && previewItems.isEmpty() && previewMessage == null) {
                Text(
                    text = "还没有样本。开始采样后，新的目标通知会在这里出现。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            previewItems.forEach { preview ->
                SamplePreviewCard(preview)
            }
            if (previewItems.isNotEmpty()) {
                Text(
                    text = "第 ${newerPageCursors.size + 1} 页",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val target = newerPageCursors.lastOrNull()
                        val remaining = newerPageCursors.dropLast(1)
                        coroutineScope.launch {
                            loadPreviewPage(
                                beforeSequenceExclusive = target,
                                newerCursors = remaining,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = newerPageCursors.isNotEmpty() && !previewLoading,
                ) {
                    Text("较新一页")
                }
                OutlinedButton(
                    onClick = {
                        val oldestSequence = previewItems.lastOrNull()?.sequence
                            ?: return@OutlinedButton
                        val history = newerPageCursors + listOf(previewBeforeSequence)
                        coroutineScope.launch {
                            loadPreviewPage(
                                beforeSequenceExclusive = oldestSequence,
                                newerCursors = history,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = previewHasOlder && !previewLoading,
                ) {
                    Text("更早一页")
                }
            }
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        loadPreviewPage(
                            beforeSequenceExclusive = null,
                            newerCursors = emptyList(),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !previewLoading,
            ) {
                Text("刷新到最新记录")
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("确认清除原始样本？") },
            text = {
                Text("此操作会删除当前设备内全部通知原文样本，无法撤销。请先确认已经停止采样并完成所需导出。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        coroutineScope.launch {
                            val result = controller.clear()
                            operationMessage = when (result) {
                                NotificationTemplateSamplingOperationResult.APPLIED -> {
                                    previewGeneration += 1L
                                    previewLoading = false
                                    previewItems = emptyList()
                                    previewHasOlder = false
                                    previewBeforeSequence = null
                                    newerPageCursors = emptyList()
                                    previewMessage = null
                                    "设备端原始样本已清除。"
                                }

                                else -> "原始样本清除失败，请勿保留此 Debug 包。"
                            }
                        }
                    },
                ) {
                    Text("确认清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SamplePreviewCard(
    preview: NotificationTemplateSamplePreview,
) {
    var expanded by rememberSaveable(preview.sequence) { mutableStateOf(false) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${sourceLabel(preview.packageName)} · #${preview.sequence}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = formatSampleTime(preview.postedAtEpochMillis),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "包名：${preview.packageName}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "channel：${preview.channelId ?: "—"} · category：${preview.category ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            NotificationField.entries.forEach { field ->
                val value = preview.fields[field]
                Text(
                    text = field.displayLabel(),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = value ?: "本条通知未提供",
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_TEXT_LINES,
                    overflow = TextOverflow.Ellipsis,
                    color = if (value == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (expanded) "收起内容" else "展开完整内容")
            }
        }
    }
}

@Composable
private fun PackageChoice(
    label: String,
    packageName: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            Text(
                text = packageName,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun buildTargetPackages(
    captureAlipay: Boolean,
    captureWechat: Boolean,
    captureCmbBank: Boolean,
    captureSamsungMessages: Boolean,
    additionalPackages: String,
): Set<String> = buildSet {
    if (captureAlipay) add(ALIPAY_PACKAGE)
    if (captureWechat) add(WECHAT_PACKAGE)
    if (captureCmbBank) add(CMB_BANK_PACKAGE)
    if (captureSamsungMessages) add(SAMSUNG_MESSAGES_PACKAGE)
    additionalPackages
        .split(',', ';', '；', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach(::add)
}

private fun requestNotificationListenerRebind(context: Context): Boolean = try {
    NotificationListenerService.requestRebind(
        ComponentName(context, BillNotificationListenerService::class.java),
    )
    true
} catch (_: RuntimeException) {
    false
}

private fun sourceLabel(packageName: String): String = when (packageName) {
    ALIPAY_PACKAGE -> "支付宝"
    WECHAT_PACKAGE -> "微信"
    CMB_BANK_PACKAGE -> "招商银行"
    SAMSUNG_MESSAGES_PACKAGE -> "三星短信"
    else -> "自定义来源"
}

private fun NotificationField.displayLabel(): String = when (this) {
    NotificationField.TITLE -> "标题"
    NotificationField.TEXT -> "正文"
    NotificationField.SUB_TEXT -> "副标题"
    NotificationField.BIG_TEXT -> "展开正文"
    NotificationField.SUMMARY_TEXT -> "摘要"
}

private fun formatSampleTime(epochMillis: Long): String = runCatching {
    SAMPLE_TIME_FORMATTER.format(
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
    )
}.getOrDefault("时间不可用")

private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
private const val WECHAT_PACKAGE = "com.tencent.mm"
private const val CMB_BANK_PACKAGE = "cmb.pb"
private const val SAMSUNG_MESSAGES_PACKAGE = "com.samsung.android.messaging"
private const val PREVIEW_PAGE_SIZE = 10
private const val COLLAPSED_TEXT_LINES = 3
private val SAMPLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val KNOWN_PACKAGES = setOf(
    ALIPAY_PACKAGE,
    WECHAT_PACKAGE,
    CMB_BANK_PACKAGE,
    SAMSUNG_MESSAGES_PACKAGE,
)
