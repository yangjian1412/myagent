package com.mynok.myagent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mynok.myagent.core.agent.AgentEvent
import com.mynok.myagent.core.agent.SystemPrompt
import com.mynok.myagent.core.context.AssembledContext
import com.mynok.myagent.core.context.ContextBudget
import com.mynok.myagent.core.context.MessageNode
import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.ReasoningEffort
import com.mynok.myagent.core.model.Role
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.data.db.ModelProfileEntity
import com.mynok.myagent.di.AppContainer
import com.mynok.myagent.di.AndroidToolContext
import com.mynok.myagent.data.repo.Mappers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** 一次工具调用的可视化记录。 */
data class ToolTrace(
    val id: String,
    val toolName: String,
    val arguments: String,
    val status: Status,
    val resultPreview: String = "",
    val error: String? = null,
    val durationMs: Long = 0,
) {
    enum class Status { RUNNING, DONE, FAILED, DENIED }
}

data class ChatUiState(
    val conversationId: String? = null,
    val title: String = "新会话",
    val nodes: List<MessageNode> = emptyList(),
    val streamingText: String = "",
    val isStreaming: Boolean = false,
    val traces: List<ToolTrace> = emptyList(),
    val error: String? = null,
    val contextInfo: AssembledContext? = null,
    val models: List<ModelProfileEntity> = emptyList(),
    val currentModelId: String? = null,
    val pendingAttachments: List<ContentPart.Image> = emptyList(),
    val systemPrompt: String? = null,
    val skills: List<com.mynok.myagent.data.db.SkillEntity> = emptyList(),
    val activeSkillId: String? = null,
    /** 会话级思考强度。 */
    val reasoningEffort: ReasoningEffort = ReasoningEffort.OFF,
    /** 是否展示思考过程。 */
    val showReasoning: Boolean = true,
    /** 是否展示工具调用过程与结果。 */
    val showToolCalls: Boolean = true,
    /** 正在流式输出的思考内容。 */
    val streamingReasoning: String = "",
) {
    /** 当前分支上的可见消息。 */
    val messages: List<ChatMessage> get() = nodes.map { it.message }

    /** 当前启用的技能。 */
    val activeSkill: com.mynok.myagent.data.db.SkillEntity?
        get() = activeSkillId?.let { id -> skills.firstOrNull { it.id == id } }

    /** 当前模型档案。 */
    val currentModel: ModelProfileEntity?
        get() = models.firstOrNull { it.id == currentModelId }

    /** 当前模型支持的思考强度；空表示不支持。 */
    val supportedReasoningEfforts: List<ReasoningEffort>
        get() = currentModel?.let { ReasoningEffort.parseList(it.reasoningLevels) }.orEmpty()
}

class ChatViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val repo = container.repository

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _showContext = MutableStateFlow(false)

    private var job: Job? = null
    private var currentParentId: String? = null

    init {
        viewModelScope.launch {
            repo.observeProfiles().collect { profiles ->
                _state.update { s ->
                    val modelId = s.currentModelId
                        ?: profiles.firstOrNull { !it.builtin }?.id
                        ?: profiles.firstOrNull()?.id
                    val supported = profiles.firstOrNull { it.id == modelId }
                        ?.let { ReasoningEffort.parseList(it.reasoningLevels) }
                        .orEmpty()
                    // 当前强度若不被该模型支持，回落到关闭
                    val effort = if (s.reasoningEffort in supported) {
                        s.reasoningEffort
                    } else {
                        ReasoningEffort.OFF
                    }
                    s.copy(models = profiles, currentModelId = modelId, reasoningEffort = effort)
                }
            }
        }
        viewModelScope.launch {
            repo.observeSkills().collect { skills ->
                _state.update { s ->
                    s.copy(
                        skills = skills,
                        // 技能被删除后自动取消选中
                        activeSkillId = s.activeSkillId?.takeIf { id -> skills.any { it.id == id } },
                    )
                }
            }
        }
    }

    /** 切换当前会话启用的技能；传 null 表示不使用技能。 */
    fun selectSkill(id: String?) {
        _state.update { s -> s.copy(activeSkillId = if (s.activeSkillId == id) null else id) }
    }

    // ------------------------------------------------------------ 会话

    fun openConversation(id: String) {
        viewModelScope.launch {
            val conv = repo.observeConversation(id).first()
            val path = repo.activePath(id)
            currentParentId = path.lastOrNull()?.id
            _state.update {
                val modelId = conv?.modelProfileId ?: it.currentModelId
                val supported = it.models.firstOrNull { m -> m.id == modelId }
                    ?.let { m -> ReasoningEffort.parseList(m.reasoningLevels) }
                    .orEmpty()
                val effort = ReasoningEffort.from(conv?.reasoningEffort)
                it.copy(
                    conversationId = id,
                    title = conv?.title ?: "会话",
                    nodes = path,
                    currentModelId = modelId,
                    systemPrompt = conv?.systemPrompt,
                    reasoningEffort = if (effort in supported) effort else ReasoningEffort.OFF,
                    showReasoning = conv?.showReasoning ?: true,
                    showToolCalls = conv?.showToolCalls ?: true,
                    streamingReasoning = "",
                    traces = emptyList(),
                    error = null,
                )
            }
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            val modelId = _state.value.currentModelId
            val id = repo.createConversation(modelProfileId = modelId)
            currentParentId = null
            _state.update {
                it.copy(
                    conversationId = id,
                    title = "新会话",
                    nodes = emptyList(),
                    streamingText = "",
                    streamingReasoning = "",
                    traces = emptyList(),
                    error = null,
                    contextInfo = null,
                    systemPrompt = null,
                    reasoningEffort = ReasoningEffort.OFF,
                    showReasoning = true,
                    showToolCalls = true,
                )
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            repo.deleteConversation(id)
            if (_state.value.conversationId == id) newConversation()
        }
    }

    fun renameConversation(title: String) {
        val id = _state.value.conversationId ?: return
        viewModelScope.launch {
            repo.renameConversation(id, title)
            _state.update { it.copy(title = title) }
        }
    }

    fun selectModel(profileId: String) {
        _state.update { s ->
            val supported = s.models.firstOrNull { it.id == profileId }
                ?.let { ReasoningEffort.parseList(it.reasoningLevels) }
                .orEmpty()
            // 新模型不支持当前强度时回落到关闭，避免发出对方不认识的参数
            val effort = if (s.reasoningEffort in supported) s.reasoningEffort else ReasoningEffort.OFF
            s.copy(currentModelId = profileId, reasoningEffort = effort)
        }
        val convId = _state.value.conversationId ?: return
        val effort = _state.value.reasoningEffort
        viewModelScope.launch {
            repo.setConversationModel(convId, profileId)
            repo.setConversationReasoningEffort(convId, effort.name)
        }
    }

    /** 设置本会话的思考强度。只接受当前模型支持的等级。 */
    fun setReasoningEffort(effort: ReasoningEffort) {
        val supported = _state.value.supportedReasoningEfforts
        if (effort != ReasoningEffort.OFF && effort !in supported) return
        _state.update { it.copy(reasoningEffort = effort) }
        val convId = _state.value.conversationId ?: return
        viewModelScope.launch { repo.setConversationReasoningEffort(convId, effort.name) }
    }

    /** 是否在对话里展示思考过程。 */
    fun setShowReasoning(show: Boolean) {
        _state.update { it.copy(showReasoning = show) }
        val convId = _state.value.conversationId ?: return
        viewModelScope.launch { repo.setConversationShowReasoning(convId, show) }
    }

    /** 是否在对话里展示工具调用过程与结果（关闭后只留最终回复）。 */
    fun setShowToolCalls(show: Boolean) {
        _state.update { it.copy(showToolCalls = show) }
        val convId = _state.value.conversationId ?: return
        viewModelScope.launch { repo.setConversationShowToolCalls(convId, show) }
    }

    fun setSystemPrompt(prompt: String?) {
        _state.update { it.copy(systemPrompt = prompt) }
        val convId = _state.value.conversationId
        if (convId != null) {
            viewModelScope.launch { repo.setConversationSystemPrompt(convId, prompt) }
        }
    }

    // ------------------------------------------------------------ 发送

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _state.value.isStreaming) return
        viewModelScope.launch {
            val convId = _state.value.conversationId ?: run {
                val id = repo.createConversation(modelProfileId = _state.value.currentModelId)
                _state.update { it.copy(conversationId = id) }
                id
            }

            val pathBefore = repo.activePath(convId)
            val parent = pathBefore.lastOrNull()?.id
            val userMessage = ChatMessage(
                role = Role.USER,
                parts = listOf(ContentPart.Text(content)) + _state.value.pendingAttachments,
            )
            val userId = repo.appendMessage(convId, parent, userMessage)
            currentParentId = userId

            if (_state.value.nodes.isEmpty()) {
                repo.renameConversation(convId, Mappers.conversationTitleOf(content))
                _state.update { it.copy(title = Mappers.conversationTitleOf(content)) }
            }
            _state.update { it.copy(pendingAttachments = emptyList()) }

            val refreshed = repo.activePath(convId)
            _state.update { it.copy(nodes = refreshed) }

            runAgent(convId, pathBefore, userMessage)
        }
    }

    fun retry() {
        val nodes = _state.value.nodes
        val lastUser = nodes.lastOrNull { it.message.role == Role.USER } ?: return
        val lastAssistant = nodes.lastOrNull { it.message.role == Role.ASSISTANT }
        viewModelScope.launch {
            val convId = _state.value.conversationId ?: return@launch
            val path = repo.activePath(convId)
            val userIdx = path.indexOfFirst { it.id == lastUser.id }
            if (userIdx < 0) return@launch
            // 删掉这条助手消息及其后续，重新生成
            lastAssistant?.let { repo.deleteBranch(convId, it.id) }
            val history = path.subList(0, userIdx)
            currentParentId = lastUser.id
            _state.update { it.copy(nodes = repo.activePath(convId), traces = emptyList(), error = null) }
            runAgent(convId, history, lastUser.message)
        }
    }

    /** 从某条助手消息重新生成（保留分支）。 */
    fun regenerateFrom(messageId: String) {
        viewModelScope.launch {
            val convId = _state.value.conversationId ?: return@launch
            val path = repo.activePath(convId)
            val idx = path.indexOfFirst { it.id == messageId }
            if (idx < 0) return@launch
            val userNode = path.take(idx).lastOrNull { it.message.role == Role.USER } ?: return@launch
            val userIdx = path.indexOf(userNode)
            repo.deleteBranch(convId, messageId)
            currentParentId = userNode.id
            _state.update { it.copy(nodes = repo.activePath(convId), traces = emptyList(), error = null) }
            runAgent(convId, path.subList(0, userIdx), userNode.message)
        }
    }

    /** 编辑一条用户消息并重发（产生新分支，旧分支保留）。 */
    fun editAndResend(messageId: String, newText: String) {
        viewModelScope.launch {
            val convId = _state.value.conversationId ?: return@launch
            val path = repo.activePath(convId)
            val target = path.firstOrNull { it.id == messageId } ?: return@launch
            val newId = repo.editMessage(convId, messageId, newText)
                ?: return@launch
            // 删掉原分支（保留用户可切回去）
            repo.deleteBranch(convId, messageId)
            val newPath = repo.activePath(convId)
            val idx = newPath.indexOfFirst { it.id == newId }
            val history = if (idx > 0) newPath.subList(0, idx) else emptyList()
            currentParentId = newId
            _state.update { it.copy(nodes = newPath, traces = emptyList(), error = null) }
            runAgent(convId, history, newPath[idx].message)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(isStreaming = false, streamingText = "") }
    }

    private suspend fun runAgent(
        conversationId: String,
        history: List<MessageNode>,
        userMessage: ChatMessage,
    ) {
        val profileEntity = _state.value.models.firstOrNull { it.id == _state.value.currentModelId }
        if (profileEntity == null) {
            _state.update { it.copy(error = "还没有配置模型，请先到「模型」页添加。") }
            return
        }
        val provider = runCatching { repo.providerFor(profileEntity) }.getOrElse { error ->
            _state.update { it.copy(error = "无法创建模型连接：${error.message}") }
            return
        }

        val profile = repo.toProfile(profileEntity)
        // Agent 身份与长期偏好来自设置页，每次都现取，改完下一次发送即生效
        val appSettings = container.settingsStore.settings.first()
        // 技能 = 额外系统提示 + 可选的工具白名单
        val skill = _state.value.activeSkill
        val system = SystemPrompt.build(
            agentName = appSettings.agentName,
            preferences = appSettings.agentPreferences,
            extraPrompts = listOf(_state.value.systemPrompt, skill?.prompt),
        )
        val allowedTools = skill?.forceTools
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
        val budget = ContextBudget(
            contextWindow = profile.contextWindow,
            reserveForOutput = (profile.maxOutputTokens + 256).coerceAtMost(profile.contextWindow / 4),
        )

        val toolContext = AndroidToolContext(
            http = container.http,
            workspace = container.workspace,
            approver = container.approvalCoordinator,
            logger = { },
        )

        _state.update {
            it.copy(
                isStreaming = true,
                streamingText = "",
                streamingReasoning = "",
                error = null,
            )
        }

        job = viewModelScope.launch {
            var parent = currentParentId
            try {
                container.agentLoop.run(
                    provider = provider,
                    history = history,
                    system = system,
                    userMessage = userMessage,
                    toolContext = toolContext,
                    budget = budget,
                    allowedTools = allowedTools,
                    reasoningEffort = _state.value.reasoningEffort
                        .takeIf { it in _state.value.supportedReasoningEfforts }
                        ?: ReasoningEffort.OFF,
                ).collect { event ->
                    when (event) {
                        is AgentEvent.ContextReady ->
                            _state.update { it.copy(contextInfo = event.assembled) }

                        is AgentEvent.AssistantDelta ->
                            _state.update { it.copy(streamingText = it.streamingText + event.text) }

                        is AgentEvent.ReasoningDelta ->
                            _state.update {
                                it.copy(streamingReasoning = it.streamingReasoning + event.text)
                            }

                        is AgentEvent.ToolCallStarted -> {
                            val trace = ToolTrace(
                                id = event.call.id,
                                toolName = event.call.name,
                                arguments = event.call.arguments,
                                status = ToolTrace.Status.RUNNING,
                            )
                            _state.update { it.copy(traces = it.traces + trace) }
                        }

                        is AgentEvent.ToolCallFinished -> {
                            _state.update { s ->
                                s.copy(
                                    traces = s.traces.map { t ->
                                        if (t.id == event.call.id) {
                                            t.copy(
                                                status = if (event.result.isError) {
                                                    ToolTrace.Status.FAILED
                                                } else {
                                                    ToolTrace.Status.DONE
                                                },
                                                resultPreview = event.result.content.take(400),
                                                error = if (event.result.isError) event.result.content else null,
                                                durationMs = event.durationMs,
                                            )
                                        } else {
                                            t
                                        }
                                    },
                                )
                            }
                        }

                        is AgentEvent.MessageProduced -> {
                            val id = repo.appendMessage(
                                conversationId = conversationId,
                                parentId = parent,
                                message = event.message,
                            )
                            parent = id
                            currentParentId = id
                            _state.update {
                                it.copy(
                                    nodes = repo.activePath(conversationId),
                                    // 助手消息已经落库，流式气泡要清空，否则文字会重复显示
                                    streamingText = "",
                                    streamingReasoning = "",
                                )
                            }
                        }

                        is AgentEvent.Failed -> {
                            _state.update { it.copy(error = event.error.message ?: "执行失败") }
                        }

                        is AgentEvent.Done -> Unit
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "执行失败") }
            } finally {
                _state.update { it.copy(isStreaming = false, streamingText = "", streamingReasoning = "") }
                job = null
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** 打开上下文检查器。 */
    fun showContextInspector() {
        _showContext.value = true
    }

    fun dismissContextInspector() {
        _showContext.value = false
    }

    val showContext: StateFlow<Boolean> = _showContext.asStateFlow()

    fun attachImage(image: ContentPart.Image) {
        _state.update { it.copy(pendingAttachments = it.pendingAttachments + image) }
    }

    fun removeAttachment(index: Int) {
        _state.update { s ->
            s.copy(pendingAttachments = s.pendingAttachments.filterIndexed { i, _ -> i != index })
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(container) as T
        }
    }
}
