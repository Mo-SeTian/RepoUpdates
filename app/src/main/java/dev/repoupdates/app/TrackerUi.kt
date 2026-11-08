package dev.repoupdates.app

import android.Manifest
import android.net.Uri
import android.os.Build
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

internal enum class Tab(val title: String) { Updates("更新"), Subscriptions("订阅"), Settings("设置") }
internal enum class Editor { Add, Proxy, Token, Interval, Appearance, Repository }

@Composable
internal fun TrackerUi(
    tracker: Tracker,
    openRelease: MutableStateFlow<Long?>,
    notificationsAllowed: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    onPermissionChanged: () -> Unit,
) {
    val state by tracker.state.collectAsState()
    val incoming by openRelease.collectAsState()
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    var tab by rememberSaveable { mutableStateOf(Tab.Updates) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var historyId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editor by remember { mutableStateOf<Editor?>(null) }
    var repository by remember { mutableStateOf<Subscription?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onPermissionChanged() }
    val requestNotifications = {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(tracker) { tracker.load() }
    LaunchedEffect(incoming) {
        incoming?.let {
            tab = Tab.Updates
            editor = null
            historyId = null
            detailId = it
            openRelease.value = null
        }
    }

    val dark = when (state.preferences.appearance) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    SideEffect { onThemeChanged(dark) }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF73A9FF),
        onPrimary = Color(0xFF10254A),
        background = Color(0xFF111216),
        surface = Color(0xFF111216),
        surfaceContainer = Color(0xFF1F2025),
        onSurfaceVariantSummary = Color(0xFFA9AEB9),
    ) else lightColorScheme(
        primary = Color(0xFF165FDC),
        background = Color(0xFFF5F6F8),
        surface = Color(0xFFF5F6F8),
        surfaceContainer = Color.White,
        onSurfaceVariantSummary = Color(0xFF565E70),
    )

    MiuixTheme(colors = colors) {
        val back: () -> Unit = {
            when {
                detailId != null -> detailId = null
                historyId != null -> historyId = null
                else -> tab = Tab.Updates
            }
        }
        BackHandler(enabled = editor == null && (detailId != null || historyId != null || tab != Tab.Updates), onBack = back)
        Scaffold(
            containerColor = colors.background,
            topBar = {
                if (detailId != null || historyId != null) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                            Icon(MiuixIcons.Back, contentDescription = "返回")
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(if (detailId != null) "版本详情" else "历史版本",
                                fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(48.dp))
                    }
                } else {
                    PageHeader(tab.title, if (tab == Tab.Updates) state.checkedAt else null) {
                        when (tab) {
                            Tab.Updates -> IconButton(
                                onClick = { scope.launch { tracker.refresh() } },
                                modifier = Modifier.size(48.dp),
                                enabled = !state.refreshing && state.subscriptions.isNotEmpty(),
                            ) {
                                if (state.refreshing) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                                else Icon(MiuixIcons.Refresh, contentDescription = "检查更新")
                            }
                            Tab.Subscriptions -> IconButton(onClick = { editor = Editor.Add }, modifier = Modifier.size(48.dp)) {
                                Icon(MiuixIcons.Add, contentDescription = "添加订阅")
                            }
                            Tab.Settings -> Unit
                        }
                    }
                }
            },
            bottomBar = {
                if (detailId == null && historyId == null) AppNavigation(tab) { tab = it }
            },
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                val id = detailId
                if (id != null) {
                    ReleasePageContent(id, tracker)
                } else if (historyId != null) {
                    val subscriptionId = requireNotNull(historyId)
                    holder.SaveableStateProvider("history:$subscriptionId") {
                        RepositoryHistoryPage(
                            subscriptionId, tracker, state.preferences.prereleases,
                            state.subscriptions.firstOrNull { it.id == subscriptionId },
                            onOpen = { detailId = it },
                        )
                    }
                } else {
                    holder.SaveableStateProvider(tab.name) {
                        when (tab) {
                            Tab.Updates -> FeedPage(state, onAdd = { editor = Editor.Add }, onOpen = { historyId = it })
                            Tab.Subscriptions -> SubscriptionsPage(
                                state.subscriptions,
                                onAdd = { editor = Editor.Add },
                                onOpen = { repository = it; editor = Editor.Repository },
                            )
                            Tab.Settings -> SettingsPage(
                                state, tracker, notificationsAllowed, requestNotifications, onEdit = { editor = it },
                            )
                        }
                    }
                }
                editor?.let { active ->
                    key(active) {
                        SettingsSheet(
                            editor = active, state = state, tracker = tracker, subscription = repository,
                            onClose = { editor = null; repository = null },
                            onAdded = {
                                editor = null
                                tab = Tab.Subscriptions
                                if (state.preferences.notifications && state.subscriptions.isEmpty()) requestNotifications()
                            },
                            onHistory = { subscriptionId -> editor = null; repository = null; historyId = subscriptionId },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, checkedAt: Long?, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 10.dp, top = 10.dp, bottom = 12.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold)
            if (checkedAt != null) {
                Spacer(Modifier.height(4.dp))
                Meta(if (checkedAt == 0L) "尚未检查" else "上次检查  ${dateLabel(checkedAt, time = true)}")
            }
        }
        action()
    }
}

@Composable
private fun AppNavigation(tab: Tab, onTab: (Tab) -> Unit) {
    NavigationBar(color = MiuixTheme.colorScheme.surfaceContainer) {
        Tab.entries.forEach { item ->
            val selected = item == tab
            val color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
            val icon = when (item) {
                Tab.Updates -> MiuixIcons.Refresh
                Tab.Subscriptions -> MiuixIcons.Favorites
                Tab.Settings -> MiuixIcons.Settings
            }
            Column(
                Modifier.weight(1f).heightIn(min = 58.dp)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onTab(item) })
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(3.dp))
                Text(item.title, fontSize = 12.sp, lineHeight = 15.sp, color = color)
            }
        }
    }
}

@Composable
private fun FeedPage(state: TrackerState, onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    val unread = remember(state.feed) { state.feed.filter { it.unread } }
    val earlier = remember(state.feed) { state.feed.filterNot { it.unread } }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 22.dp)) {
        state.checkMessage?.let { message ->
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 16.dp, insideMargin = PaddingValues(14.dp)) {
                    Text(message, fontSize = 14.sp, color = MiuixTheme.colorScheme.error)
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        if (state.subscriptions.isEmpty()) {
            item {
                EmptyCard(
                    "还没有订阅仓库", "添加一个公开 GitHub 仓库，在这里查看新版本和更新说明。", onAdd,
                )
            }
        }
        if (unread.isNotEmpty()) feedGroup("未读更新", unread, onOpen, count = unread.size)
        if (unread.isNotEmpty() && earlier.isNotEmpty()) item { Spacer(Modifier.height(18.dp)) }
        if (earlier.isNotEmpty()) feedGroup("最新版本", earlier, onOpen)
        val released = state.feed.mapTo(mutableSetOf()) { it.subscriptionId }
        val withoutRelease = state.subscriptions.filterNot { it.id in released }
        if (withoutRelease.isNotEmpty()) {
            if (state.feed.isNotEmpty()) item { Spacer(Modifier.height(18.dp)) }
            item { GroupTitle("暂无版本") }
            itemsIndexed(withoutRelease, key = { _, item -> "empty:${item.id}" }) { index, subscription ->
                GroupRow(index, withoutRelease.lastIndex, onClick = { onOpen(subscription.id) }) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(subscription.repo.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Meta(if (state.preferences.prereleases) "暂无已发布版本" else "暂无正式版本")
                    }
                }
            }
        }
    }
}

private fun LazyListScope.feedGroup(title: String, releases: List<FeedItem>, onOpen: (Long) -> Unit, count: Int? = null) {
    item(key = title) { GroupTitle(title, count?.toString(), highlight = count != null) }
    itemsIndexed(releases, key = { _, item -> item.id }) { index, item ->
        GroupRow(index, releases.lastIndex, onClick = { onOpen(item.subscriptionId) }) {
            ReleaseRow(item, showRepository = true)
        }
    }
}

@Composable
private fun ReleaseRow(item: FeedItem, showRepository: Boolean) {
    val showSummary = item.summary.isNotBlank() && (item.unread || !showRepository)
    Column(
        Modifier.fillMaxWidth().heightIn(min = if (showSummary) 68.dp else 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showRepository) {
                    Text(item.repo.name, Modifier.weight(.75f, fill = !item.unread), fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(item.tag, Modifier.weight(1.25f, fill = false), fontSize = if (showRepository) 13.sp else 16.sp,
                    color = if (showRepository) secondary() else MiuixTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.prerelease) Text("预发布", fontSize = 12.sp, color = secondary())
            Meta(dateLabel(item.publishedAt, time = item.unread))
            if (item.unread) Box(Modifier.size(7.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary))
            Chevron()
        }
        if (showSummary) {
            Spacer(Modifier.height(4.dp))
            Text(item.summary, fontSize = 13.sp, color = secondary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RepositoryHistoryPage(
    subscriptionId: Long,
    tracker: Tracker,
    includePrereleases: Boolean,
    subscription: Subscription?,
    onOpen: (Long) -> Unit,
) {
    var anchors by rememberSaveable(subscriptionId, includePrereleases) { mutableStateOf(listOf<Long?>(null)) }
    var pageIndex by rememberSaveable(subscriptionId, includePrereleases) { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    val page by produceState<HistoryPage?>(null, subscriptionId, includePrereleases, pageIndex, retry) {
        value = null
        value = tracker.history(subscriptionId, anchors[pageIndex], includePrereleases)
    }
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(pageIndex) { scroll.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        subscription?.let {
            Column(Modifier.padding(start = 21.dp, end = 21.dp, top = 10.dp, bottom = 12.dp)) {
                Text(it.repo.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Meta(it.repo.fullName)
            }
        }
        LazyColumn(
            state = scroll, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            val current = page
            if (current == null) {
                item { Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(size = 24.dp)
                } }
            } else {
                current.error?.let { error ->
                    item {
                        Card(Modifier.fillMaxWidth(), cornerRadius = 16.dp, insideMargin = PaddingValues(14.dp)) {
                            Text(error, fontSize = 14.sp, color = MiuixTheme.colorScheme.error)
                            TextButton("重试", onClick = { retry++ }, minHeight = 48.dp)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (current.releases.isEmpty() && current.error == null) {
                    item { EmptyCard("暂无历史版本", "仓库发布版本后会显示在这里。") }
                }
                itemsIndexed(current.releases, key = { _, release -> release.id }) { index, release ->
                    GroupRow(index, current.releases.lastIndex, onClick = { onOpen(release.id) }) {
                        ReleaseRow(release, showRepository = false)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton("上一页", onClick = { pageIndex-- }, enabled = page != null && pageIndex > 0,
                modifier = Modifier.weight(1f), minHeight = 48.dp)
            Text("第 ${pageIndex + 1} 页", fontSize = 13.sp, color = secondary())
            TextButton(
                "下一页",
                onClick = {
                    val last = page?.releases?.lastOrNull()
                    if (last != null) {
                        anchors = anchors.take(pageIndex + 1) + last.id
                        pageIndex++
                    }
                },
                enabled = page?.hasMore == true && page?.releases?.isNotEmpty() == true,
                modifier = Modifier.weight(1f), minHeight = 48.dp,
            )
        }
    }
}

@Composable
private fun SubscriptionsPage(subscriptions: List<Subscription>, onAdd: () -> Unit, onOpen: (Subscription) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 22.dp)) {
        if (subscriptions.isEmpty()) {
            item { EmptyCard("还没有订阅仓库", "粘贴 GitHub 仓库链接即可订阅。", onAdd) }
        }
        itemsIndexed(subscriptions, key = { _, item -> item.id }) { index, item ->
            GroupRow(index, subscriptions.lastIndex, onClick = { onOpen(item) }) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 15.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MiuixIcons.Favorites, contentDescription = null, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.repo.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Meta(item.repo.fullName)
                    }
                    Chevron()
                }
            }
        }
    }
}

@Composable
private fun GroupRow(index: Int, lastIndex: Int, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(
        topStart = if (index == 0) 18.dp else 0.dp,
        topEnd = if (index == 0) 18.dp else 0.dp,
        bottomStart = if (index == lastIndex) 18.dp else 0.dp,
        bottomEnd = if (index == lastIndex) 18.dp else 0.dp,
    )
    Column(Modifier.fillMaxWidth().clip(shape).background(MiuixTheme.colorScheme.surfaceContainer).clickable(onClick = onClick)) {
        content()
        if (index != lastIndex) RowDivider()
    }
}

@Composable
private fun EmptyCard(title: String, explanation: String, onAdd: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(18.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(explanation, fontSize = 14.sp, lineHeight = 21.sp, color = secondary())
        if (onAdd != null) {
            Spacer(Modifier.height(15.dp))
            TextButton("添加订阅", onAdd, minHeight = 48.dp, colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
private fun ReleasePageContent(id: Long, tracker: Tracker) {
    val context = LocalContext.current
    var loaded by remember(id) { mutableStateOf(false) }
    var release by remember(id) { mutableStateOf<ReleaseDetail?>(null) }
    LaunchedEffect(id) {
        release = tracker.open(id)
        loaded = true
    }
    val current = release
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
    ) {
        if (current == null) {
            if (loaded) EmptyCard("未找到版本", "这个版本可能来自已取消订阅的仓库。")
            else CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            return@Column
        }
        val item = current.item
        Column(Modifier.padding(horizontal = 5.dp)) {
            Meta(item.repo.fullName)
            Spacer(Modifier.height(5.dp))
            Text(item.tag, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    if (item.prerelease) "预发布" else "正式版",
                    Modifier.clip(RoundedCornerShape(8.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = .12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 12.sp, color = MiuixTheme.colorScheme.primary,
                )
                Meta(dateLabel(item.publishedAt, time = true, relative = false))
            }
        }
        Spacer(Modifier.height(18.dp))
        Card(Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(16.dp)) {
            Text("更新说明", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (current.notes.isBlank()) Meta("这个版本没有提供更新说明")
            else MarkdownNotes(current.notes, current.webUrl)
        }
        Spacer(Modifier.height(14.dp))
        TextButton(
            "在 GitHub 查看", onClick = { openWeb(context, Uri.parse(current.webUrl)) },
            modifier = Modifier.fillMaxWidth(), minHeight = 48.dp,
            colors = ButtonDefaults.textButtonColors(
                color = MiuixTheme.colorScheme.primary.copy(alpha = .13f),
                textColor = MiuixTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun MarkdownNotes(notes: String, baseUrl: String) {
    val context = LocalContext.current
    val color = MiuixTheme.colorScheme.onSurface.toArgb()
    val linkColor = MiuixTheme.colorScheme.primary.toArgb()
    val markwon = remember(context, baseUrl, linkColor) {
        Markwon.builder(context).usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                val density = context.resources.displayMetrics.density
                builder.headingTextSizeMultipliers(floatArrayOf(1.24f, 1.14f, 1.08f, 1.04f, 1f, 1f))
                    .headingBreakHeight(0)
                    .bulletWidth((4 * density).toInt())
                    .blockMargin((10 * density).toInt())
                    .linkColor(linkColor)
            }

            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver { view, link ->
                    val target = runCatching { URI(baseUrl).resolve(link).toString() }.getOrNull()
                    target?.let { openWeb(view.context, Uri.parse(it)) }
                }
            }
        }).build()
    }
    AndroidView(
        factory = { TextView(it).apply { textSize = 14f; setLineSpacing(0f, 1.18f) } },
        modifier = Modifier.fillMaxWidth(),
        update = { view ->
            view.setTextColor(color)
            view.setLinkTextColor(linkColor)
            if (view.tag != (notes to markwon)) {
                markwon.setMarkdown(view, notes)
                view.tag = notes to markwon
            }
        },
    )
}

@Composable
internal fun GroupTitle(title: String, value: String? = null, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 19.sp,
            color = secondary(), fontWeight = FontWeight.Medium)
        if (value != null) Text(value, fontSize = 12.sp, lineHeight = 16.sp,
            color = if (highlight) MiuixTheme.colorScheme.primary else secondary())
    }
}

@Composable
internal fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(.5.dp).background(secondary().copy(alpha = .19f)))
}

@Composable
private fun Chevron() = Icon(MiuixIcons.ChevronForward, contentDescription = null,
    modifier = Modifier.size(14.dp), tint = secondary())

@Composable
internal fun Meta(text: String) = Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = secondary())

@Composable
internal fun secondary() = MiuixTheme.colorScheme.onSurfaceVariantSummary

internal fun dateLabel(millis: Long, time: Boolean, relative: Boolean = true): String {
    val dateTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val date = dateTime.toLocalDate()
    val today = LocalDate.now()
    val dateText = when {
        relative && date == today -> "今天"
        relative && date == today.minusDays(1) -> "昨天"
        date.year != today.year -> dateTime.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        else -> dateTime.format(DateTimeFormatter.ofPattern("M月d日"))
    }
    return if (time) "$dateText ${dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}" else dateText
}
