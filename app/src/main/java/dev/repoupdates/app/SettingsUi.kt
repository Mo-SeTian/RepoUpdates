package dev.repoupdates.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val intervals = listOf(1, 3, 6, 12, 24)
private val appearanceNames = linkedMapOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
private val preferencePadding = PaddingValues(horizontal = 15.dp, vertical = 9.dp)

@Composable
internal fun SettingsPage(
    state: TrackerState,
    tracker: Tracker,
    notificationsAllowed: Boolean,
    requestNotifications: () -> Unit,
    onEdit: (Editor) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = state.preferences
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        PreferenceGroup("检查更新", if (state.checkedAt == 0L) "尚未检查" else "上次检查 ${dateLabel(state.checkedAt, time = true)}") {
            SuperSwitch(
                checked = config.background, onCheckedChange = { scope.launch { tracker.setBackground(it) } },
                title = "后台检查", summary = "定期检查已订阅仓库", insideMargin = preferencePadding,
                modifier = Modifier.heightIn(min = 56.dp),
            )
            RowDivider()
            PreferenceArrow("检查间隔", "${config.intervalHours} 小时", enabled = config.background) { onEdit(Editor.Interval) }
            RowDivider()
            SuperSwitch(
                checked = config.prereleases, onCheckedChange = { scope.launch { tracker.setPrereleases(it) } },
                title = "包含预发布版本", insideMargin = preferencePadding, modifier = Modifier.heightIn(min = 49.dp),
            )
        }
        PreferenceGroup("通知") {
            SuperSwitch(
                checked = config.notifications, onCheckedChange = { enabled ->
                    scope.launch { tracker.setNotifications(enabled) }
                    if (enabled) requestNotifications()
                },
                title = "新版本通知", insideMargin = preferencePadding, modifier = Modifier.heightIn(min = 49.dp),
            )
            RowDivider()
            PreferenceArrow("系统通知设置", if (notificationsAllowed) "已允许" else "未允许") {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
        PreferenceGroup("GitHub 连接") {
            PreferenceArrow("GitHub 代理", if (config.proxy.isBlank()) "未配置" else "已配置") { onEdit(Editor.Proxy) }
            RowDivider()
            PreferenceArrow("GitHub Token", if (config.hasToken) "已配置" else "未配置") { onEdit(Editor.Token) }
        }
        PreferenceGroup("外观") {
            PreferenceArrow("深色模式", appearanceNames.getValue(config.appearance)) { onEdit(Editor.Appearance) }
        }
    }
}

@Composable
private fun PreferenceGroup(title: String, value: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        GroupTitle(title, value)
        Card(Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(0.dp), content = content)
    }
}

@Composable
private fun PreferenceArrow(title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    SuperArrow(
        title = title, modifier = Modifier.heightIn(min = 49.dp), insideMargin = preferencePadding,
        endActions = { Meta(value) }, enabled = enabled, onClick = onClick,
    )
}

@Composable
internal fun SettingsSheet(
    editor: Editor,
    state: TrackerState,
    tracker: Tracker,
    subscription: Subscription?,
    onClose: () -> Unit,
    onAdded: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val config = state.preferences
    var value by remember { mutableStateOf(if (editor == Editor.Proxy) config.proxy else "") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var visibleToken by remember { mutableStateOf(false) }
    var confirmingRemoval by remember { mutableStateOf(false) }

    if (editor == Editor.Token) {
        DisposableEffect(context) {
            val window = (context as? Activity)?.window
            val secure = WindowManager.LayoutParams.FLAG_SECURE
            val alreadySecure = window?.attributes?.flags?.and(secure) == secure
            window?.addFlags(secure)
            onDispose { if (!alreadySecure) window?.clearFlags(secure) }
        }
    }

    val submit: () -> Unit = {
        if (!busy) {
            keyboard?.hide()
            scope.launch {
                busy = true
                try {
                    val result = when (editor) {
                        Editor.Add -> tracker.add(value)
                        Editor.Proxy -> tracker.setProxy(value)
                        Editor.Token -> if (value.isBlank() && config.hasToken) null else tracker.setToken(value)
                        else -> null
                    }
                    if (result != null) error = result
                    else if (editor == Editor.Add) onAdded() else onClose()
                } finally {
                    busy = false
                }
            }
        }
    }

    SuperBottomSheet(
        show = true, onDismissRequest = { if (!busy) onClose() }, allowDismiss = !busy,
        backgroundColor = MiuixTheme.colorScheme.surfaceContainer, cornerRadius = 22.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            val title = when (editor) {
                Editor.Add -> "添加订阅"
                Editor.Proxy -> "GitHub 代理"
                Editor.Token -> "GitHub Token"
                Editor.Interval -> "检查间隔"
                Editor.Appearance -> "深色模式"
                Editor.Repository -> if (confirmingRemoval) "取消订阅" else subscription?.repo?.name.orEmpty()
            }
            Text(title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
            when (editor) {
                Editor.Add, Editor.Proxy, Editor.Token -> {
                    val isToken = editor == Editor.Token
                    val label = when (editor) {
                        Editor.Add -> "仓库链接"
                        Editor.Proxy -> "HTTP 代理地址（可选）"
                        else -> "访问令牌（可选）"
                    }
                    val placeholder = when (editor) {
                        Editor.Add -> "github.com/owner/repo"
                        Editor.Proxy -> "http://proxy.example.com:7890"
                        else -> if (config.hasToken) "粘贴新 Token 以替换" else "粘贴 GitHub Token"
                    }
                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    TextField(
                        value = value, onValueChange = { value = it; error = null }, label = placeholder,
                        useLabelAsPlaceholder = true, labelColor = secondary(), singleLine = true, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp),
                        insideMargin = androidx.compose.ui.unit.DpSize(12.dp, 10.dp),
                        cornerRadius = 12.dp,
                        textStyle = MiuixTheme.textStyles.main.copy(fontSize = 15.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = if (isToken) KeyboardType.Password else KeyboardType.Uri),
                        visualTransformation = if (isToken && !visibleToken) PasswordVisualTransformation() else VisualTransformation.None,
                        leadingIcon = {
                            Icon(if (isToken) MiuixIcons.Lock else MiuixIcons.Link, contentDescription = null,
                                modifier = Modifier.padding(start = 8.dp, end = 6.dp).size(19.dp), tint = secondary())
                        },
                        trailingIcon = if (isToken) {
                            {
                                IconButton(onClick = { visibleToken = !visibleToken }, modifier = Modifier.size(48.dp)) {
                                    Icon(if (visibleToken) MiuixIcons.Show else MiuixIcons.Hide,
                                        contentDescription = if (visibleToken) "隐藏 Token" else "显示 Token",
                                        modifier = Modifier.size(20.dp), tint = secondary())
                                }
                            }
                        } else null,
                    )
                    Meta(when (editor) {
                        Editor.Add -> "支持公开的 GitHub 仓库，也可输入 owner/repo"
                        Editor.Proxy -> "留空时直接连接 GitHub"
                        else -> "提高 GitHub API 请求限额\n留空也可检查公开仓库"
                    })
                    error?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
                    if (editor == Editor.Add) {
                        TextButton(if (busy) "正在验证仓库…" else "添加订阅", submit,
                            modifier = Modifier.fillMaxWidth(), enabled = value.isNotBlank() && !busy,
                            minHeight = 48.dp, colors = ButtonDefaults.textButtonColorsPrimary())
                    } else {
                        SheetActions("保存", !busy, onClose, submit)
                        if (isToken && config.hasToken) {
                            TextButton(
                                "清除 Token", onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val result = tracker.setToken("")
                                            if (result == null) onClose() else error = result
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }, enabled = !busy, minHeight = 48.dp, modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error),
                            )
                        }
                    }
                }
                Editor.Interval -> Column(Modifier.selectableGroup()) {
                    intervals.forEach { hours ->
                        ChoiceRow("$hours 小时", hours == config.intervalHours) {
                            scope.launch { tracker.setInterval(hours); onClose() }
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Meta("实际检查时间取决于 Android 的后台调度。")
                }
                Editor.Appearance -> Column(Modifier.selectableGroup()) {
                    appearanceNames.forEach { (key, name) ->
                        ChoiceRow(name, key == config.appearance) {
                            scope.launch { tracker.setAppearance(key); onClose() }
                        }
                    }
                }
                Editor.Repository -> if (subscription != null) {
                    Meta(subscription.repo.fullName)
                    if (confirmingRemoval) {
                        Meta("将移除此仓库在本机的订阅与版本记录。")
                        SheetActions("确认取消", !busy, { confirmingRemoval = false }) {
                            scope.launch {
                                busy = true
                                try {
                                    tracker.remove(subscription.id)
                                    onClose()
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    } else {
                        val latest = state.feed.filter { it.repo.key == subscription.repo.key }.maxByOrNull { it.publishedAt }
                        if (latest != null) PreferenceArrow("最新版本", latest.tag) { onOpen(latest.id) }
                        TextButton("在 GitHub 查看", { openWeb(context, Uri.parse(subscription.repo.webUrl)) },
                            modifier = Modifier.fillMaxWidth(), minHeight = 48.dp)
                        TextButton("取消订阅", { confirmingRemoval = true }, modifier = Modifier.fillMaxWidth(),
                            minHeight = 48.dp, colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error))
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetActions(title: String, enabled: Boolean, cancel: () -> Unit, action: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton("取消", cancel, Modifier.weight(1f), enabled = enabled, minHeight = 48.dp)
        TextButton(title, action, Modifier.weight(1f), enabled = enabled, minHeight = 48.dp,
            colors = ButtonDefaults.textButtonColorsPrimary())
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 49.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        RadioButton(selected, onClick = null)
    }
}
