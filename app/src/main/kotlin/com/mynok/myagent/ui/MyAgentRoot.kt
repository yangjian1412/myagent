package com.mynok.myagent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynok.myagent.MainActivity
import com.mynok.myagent.data.db.ConversationEntity
import com.mynok.myagent.di.AppContainer
import com.mynok.myagent.tools.android.ImageLoader
import com.mynok.myagent.ui.chat.ApprovalDialog
import com.mynok.myagent.ui.chat.ChatUiState
import com.mynok.myagent.ui.chat.ChatViewModel
import com.mynok.myagent.ui.chat.Composer
import com.mynok.myagent.ui.chat.ContextInspectorDialog
import com.mynok.myagent.ui.chat.ConversationSettingsSheet
import com.mynok.myagent.ui.chat.MessageList
import com.mynok.myagent.ui.chat.SkillChipRow
import com.mynok.myagent.ui.models.ModelsScreen
import com.mynok.myagent.ui.models.ModelsViewModel
import com.mynok.myagent.ui.sessions.SessionsViewModel
import com.mynok.myagent.ui.settings.SettingsScreen
import com.mynok.myagent.ui.settings.SettingsViewModel
import com.mynok.myagent.ui.tools.SkillsScreen
import com.mynok.myagent.ui.tools.ToolsScreen
import com.mynok.myagent.ui.tools.ToolsViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 底部导航 / 侧栏的三个一级页面。 */
private enum class Destination(val label: String) {
    CHAT("对话"),
    MODELS("模型"),
    SETTINGS("设置"),
}

/** 「模型」页里的三个子项。 */
private enum class ModelTab(val label: String) {
    PROFILES("模型配置"),
    TOOLS("工具"),
    SKILLS("技能"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyAgentRoot(container: AppContainer) {
    val chatVm: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    val sessionsVm: SessionsViewModel = viewModel(factory = SessionsViewModel.factory(container))
    val modelsVm: ModelsViewModel = viewModel(factory = ModelsViewModel.factory(container))
    val toolsVm: ToolsViewModel = viewModel(factory = ToolsViewModel.factory(container))
    val settingsVm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))

    val width = rememberWindowWidth()
    val isExpanded = width == WindowWidth.EXPANDED
    val context = LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()

    var destination by rememberSaveable { mutableStateOf(Destination.CHAT) }
    var modelTab by rememberSaveable { mutableStateOf(ModelTab.PROFILES) }
    var pendingDeleteSession by remember { mutableStateOf<ConversationEntity?>(null) }
    var showConversationSettings by remember { mutableStateOf(false) }


    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val chatState by chatVm.state.collectAsStateWithLifecycleCompat()
    val sessionsState by sessionsVm.state.collectAsStateWithLifecycleCompat()
    val modelsState by modelsVm.state.collectAsStateWithLifecycleCompat()
    val toolsState by toolsVm.state.collectAsStateWithLifecycleCompat()
    val settingsState by settingsVm.state.collectAsStateWithLifecycleCompat()
    val showContext by chatVm.showContext.collectAsStateWithLifecycleCompat()
    val pendingApproval by container.approvalCoordinator.pending.collectAsStateWithLifecycleCompat()

    val hideTabLabels = settingsState.settings.hideTabLabels

    LaunchedEffect(Unit) {
        toolsVm.refreshBuiltIn(container.toolRegistry.all())
    }

    // 打开一个会话：记下当前 id，平板下高亮，手机下关抽屉
    val openSession: (String) -> Unit = { id ->
        sessionsVm.select(id)
        chatVm.openConversation(id)
        scope.launch { drawerState.close() }
    }
    val newSession: () -> Unit = {
        chatVm.newConversation()
        scope.launch { drawerState.close() }
    }

    val sessionsPane: @Composable () -> Unit = {
        SessionsPane(
            sessions = sessionsState.conversations,
            currentId = sessionsState.currentId,
            onOpen = openSession,
            onNew = newSession,
            onLongPress = { pendingDeleteSession = it },
            modifier = Modifier.fillMaxHeight(),
        )
    }

    val chatPane: @Composable (Boolean) -> Unit = { expanded ->
        ChatLayout(
            state = chatState,
            isExpanded = expanded,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            sessionsPane = sessionsPane,
            onNewSession = newSession,
            onOpenSettings = { showConversationSettings = true },
            vm = chatVm,
            onPickImage = {
                activity?.requestImage { uri ->
                    scope.launch {
                        ImageLoader.load(context, uri)?.let { chatVm.attachImage(it) }
                    }
                }
            },
        )
    }

    val modelTabContent: @Composable () -> Unit = {
        when (modelTab) {
            ModelTab.PROFILES -> ModelsScreen(
                state = modelsState,
                onSave = { id, name, type, baseUrl, modelId, key, cw, maxOut, tools, vision, temp, levels ->
                    modelsVm.save(
                        id, name, type, baseUrl, modelId, key, cw, maxOut,
                        tools, vision, temp, levels,
                    )
                },
                onDelete = { modelsVm.delete(it) },
                onProbe = { modelsVm.probe(it) },
                catalog = modelsState.catalog,
                onFetchModels = { type, url, key, secretId ->
                    modelsVm.fetchModels(type, url, key, secretId)
                },
                onResetCatalog = { modelsVm.resetCatalog() },
                onClearMessage = { modelsVm.clearMessage() },
            )

            ModelTab.TOOLS -> ToolsScreen(
                state = toolsState,
                onAddMcp = { n, u, t -> toolsVm.addMcpServer(n, u, t) },
                onRemoveMcp = { toolsVm.removeMcpServer(it) },
                onSyncMcp = { toolsVm.syncMcp() },
                onGrantDirectory = {
                    activity?.requestDirectory { uris -> toolsVm.grantDirectories(uris) }
                },
                onRequestScreenCapture = { activity?.requestScreenCapture() },
                onSetToolEnabled = { name, enabled -> toolsVm.setToolEnabled(name, enabled) },
                onSetToolApproval = { name, require, risk ->
                    toolsVm.setToolRequireApproval(name, require, risk)
                },
                authorizedRoots = container.workspace.roots().map { it.displayName },
                onSaveSecret = { name, value -> toolsVm.saveSecret(name, value) },
            )

            ModelTab.SKILLS -> SkillsScreen(
                skills = toolsState.skills,
                onSave = { id, name, desc, prompt, force ->
                    toolsVm.saveSkill(id, name, desc, prompt, force)
                },
                onDelete = { toolsVm.deleteSkill(it) },
            )
        }
    }

    val modelsPane: @Composable () -> Unit = {
        if (isExpanded) {
            // 平板：三个子项做成左侧列表，和「历史对话」一样是常驻侧栏
            Row(modifier = Modifier.fillMaxSize()) {
                ModelTabsPane(
                    selected = modelTab,
                    onSelect = { modelTab = it },
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight(),
                )
                VerticalDivider()
                Box(modifier = Modifier.weight(1f)) { modelTabContent() }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                PrimaryTabRow(selectedTabIndex = modelTab.ordinal) {
                    ModelTab.entries.forEach { tab ->
                        Tab(
                            selected = modelTab == tab,
                            onClick = { modelTab = tab },
                            text = { Text(tab.label) },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) { modelTabContent() }
            }
        }
    }

    val settingsPane: @Composable () -> Unit = {
        SettingsScreen(
            state = settingsState,
            onThemeMode = { settingsVm.setThemeMode(it) },
            onThemeColor = { settingsVm.setThemeColor(it) },
            onDynamicColor = { settingsVm.setDynamicColor(it) },
            onAutoTitle = { settingsVm.setAutoTitle(it) },
            onHideTabLabels = { settingsVm.setHideTabLabels(it) },
            onAgentName = { settingsVm.setAgentName(it) },
            onAgentPreferences = { settingsVm.setAgentPreferences(it) },
            modifier = Modifier.fillMaxSize(),
        )
    }

    val pageContent: @Composable () -> Unit = {
        when (destination) {
            Destination.CHAT -> chatPane(isExpanded)
            Destination.MODELS -> modelsPane()
            Destination.SETTINGS -> settingsPane()
        }
    }

    if (isExpanded) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxHeight(),
            ) {
                Spacer(Modifier.height(8.dp))
                Destination.entries.forEach { d ->
                    NavigationRailItem(
                        selected = destination == d,
                        onClick = { destination = d },
                        icon = { Icon(d.icon(), contentDescription = d.label) },
                        label = if (hideTabLabels) null else {
                            { Text(d.label) }
                        },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    // 平板分支没有 Scaffold，系统栏 inset 要自己处理；
                    // 顶部状态栏、刘海和底部手势条都在这里让开。
                    .windowInsetsPadding(
                        WindowInsets.systemBars.union(WindowInsets.displayCutout),
                    )
                    .imePadding(),
            ) { pageContent() }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // 只有对话页能拉出历史抽屉，避免在设置页误触
            gesturesEnabled = destination == Destination.CHAT,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.width(300.dp),
                ) {
                    sessionsPane()
                }
            },
        ) {
            Scaffold(
                // 系统栏 + 刘海都让开；键盘由下面的 imePadding 处理
                contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Destination.entries.forEach { d ->
                            NavigationBarItem(
                                selected = destination == d,
                                onClick = { destination = d },
                                icon = { Icon(d.icon(), contentDescription = d.label) },
                                label = if (hideTabLabels) null else {
                                    { Text(d.label) }
                                },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .padding(padding)
                        // Scaffold 只处理系统栏；键盘弹出时要把输入框顶到键盘上方
                        .consumeWindowInsets(padding)
                        .imePadding(),
                ) { pageContent() }
            }
        }
    }

    if (showContext) {
        ContextInspectorDialog(
            context = chatState.contextInfo,
            onDismiss = { chatVm.dismissContextInspector() },
        )
    }

    if (showConversationSettings) {
        ConversationSettingsSheet(
            models = chatState.models,
            currentModelId = chatState.currentModelId,
            supportedEfforts = chatState.supportedReasoningEfforts,
            reasoningEffort = chatState.reasoningEffort,
            showReasoning = chatState.showReasoning,
            showToolCalls = chatState.showToolCalls,
            maxSteps = chatState.maxSteps,
            onSelectModel = { chatVm.selectModel(it) },
            onReasoningEffort = { chatVm.setReasoningEffort(it) },
            onShowReasoning = { chatVm.setShowReasoning(it) },
            onShowToolCalls = { chatVm.setShowToolCalls(it) },
            onMaxSteps = { chatVm.setMaxSteps(it) },
            onShowContext = {
                showConversationSettings = false
                chatVm.showContextInspector()
            },
            onDismiss = { showConversationSettings = false },
        )
    }

    pendingDeleteSession?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSession = null },
            title = { Text("删除会话？") },
            text = { Text("「${target.title}」及其全部消息将被永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteSession = null
                    sessionsVm.delete(target.id)
                    if (chatState.conversationId == target.id) chatVm.deleteConversation(target.id)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSession = null }) { Text("取消") }
            },
        )
    }

    ApprovalDialog(
        pending = pendingApproval,
        onApprove = { container.approvalCoordinator.resolve(true) },
        onDeny = { container.approvalCoordinator.resolve(false) },
    )
}

private fun Destination.icon() = when (this) {
    Destination.CHAT -> Icons.AutoMirrored.Filled.Chat
    Destination.MODELS -> Icons.Filled.Insights
    Destination.SETTINGS -> Icons.Filled.Settings
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatLayout(
    state: ChatUiState,
    isExpanded: Boolean,
    onOpenDrawer: () -> Unit,
    sessionsPane: @Composable () -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: ChatViewModel,
    onPickImage: () -> Unit,
) {
    var editingIndex by remember { mutableStateOf(-1) }
    var editingText by remember { mutableStateOf("") }

    Row(modifier = Modifier.fillMaxSize()) {
        if (isExpanded) {
            Box(modifier = Modifier.width(300.dp).fillMaxHeight()) { sessionsPane() }
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            TopAppBar(
                navigationIcon = {
                    if (!isExpanded) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Filled.Menu, contentDescription = "历史对话")
                        }
                    }
                },
                title = {
                    Column {
                        Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val model = state.models.firstOrNull { it.id == state.currentModelId }
                        if (model != null) {
                            Text(
                                model.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Tune, contentDescription = "对话设置")
                    }
                    IconButton(onClick = onNewSession) {
                        Icon(Icons.Filled.Add, contentDescription = "新建会话")
                    }
                },
            )
            state.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
            MessageList(
                messages = state.messages,
                streamingText = state.streamingText,
                isStreaming = state.isStreaming,
                traces = state.traces,
                onRegenerate = { index ->
                    state.nodes.getOrNull(index)?.let { vm.regenerateFrom(it.id) }
                },
                onEdit = { index ->
                    editingIndex = index
                    editingText = state.messages.getOrNull(index)?.text.orEmpty()
                },
                modifier = Modifier.weight(1f),
                streamingReasoning = state.streamingReasoning,
                showReasoning = state.showReasoning,
                showToolCalls = state.showToolCalls,
            )
            if (state.skills.isNotEmpty()) {
                SkillChipRow(
                    skills = state.skills,
                    activeId = state.activeSkillId,
                    onToggle = { vm.selectSkill(it) },
                )
            }
            Composer(
                isStreaming = state.isStreaming,
                attachments = state.pendingAttachments,
                onSend = { vm.send(it) },
                onStop = { vm.stop() },
                onPickImage = onPickImage,
                onRemoveAttachment = { vm.removeAttachment(it) },
            )
        }
    }

    if (editingIndex >= 0) {
        AlertDialog(
            onDismissRequest = { editingIndex = -1 },
            title = { Text("编辑消息") },
            text = {
                OutlinedTextField(
                    value = editingText,
                    onValueChange = { editingText = it },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val node = state.nodes.getOrNull(editingIndex)
                    val text = editingText
                    editingIndex = -1
                    if (node != null && text.isNotBlank()) vm.editAndResend(node.id, text)
                }) { Text("重发") }
            },
            dismissButton = { TextButton(onClick = { editingIndex = -1 }) { Text("取消") } },
        )
    }
}

/** 平板上的「模型」子导航：和会话列表一样常驻在左侧。 */
@Composable
private fun ModelTabsPane(
    selected: ModelTab,
    onSelect: (ModelTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("模型设置", style = MaterialTheme.typography.titleMedium)
            Text(
                "配置、工具与技能",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        ModelTab.entries.forEach { tab ->
            val active = tab == selected
            Surface(
                color = if (active) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(tab) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (active) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionsPane(
    sessions: List<ConversationEntity>,
    currentId: String?,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onLongPress: (ConversationEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("历史对话", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${sessions.size} 个会话 · 按住可删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "新建会话")
            }
        }
        HorizontalDivider()
        if (sessions.isEmpty()) {
            Text(
                "还没有会话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { conv ->
                val selected = conv.id == currentId
                Surface(
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onOpen(conv.id) },
                                onLongClick = { onLongPress(conv) },
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            conv.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            relativeTime(conv.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormatter = DateTimeFormatter.ofPattern("MM-dd")

/** 把时间戳格式化成「刚刚 / 12:30 / 昨天 / 09-28」。 */
private fun relativeTime(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    if (diff in 0..60_000) return "刚刚"
    if (diff in 0..3_600_000) return "${diff / 60_000} 分钟前"
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (time.toLocalDate()) {
        today -> time.format(timeFormatter)
        today.minusDays(1) -> "昨天"
        else -> time.format(dateFormatter)
    }
}
