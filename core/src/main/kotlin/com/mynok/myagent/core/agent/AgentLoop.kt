package com.mynok.myagent.core.agent

import com.mynok.myagent.core.context.AssembledContext
import com.mynok.myagent.core.context.ContextBudget
import com.mynok.myagent.core.context.ContextEngine
import com.mynok.myagent.core.context.MessageNode
import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.FinishReason
import com.mynok.myagent.core.model.ModelEvent
import com.mynok.myagent.core.model.ModelProvider
import com.mynok.myagent.core.model.ModelRequest
import com.mynok.myagent.core.model.ReasoningEffort
import com.mynok.myagent.core.model.Role
import com.mynok.myagent.core.model.ToolCall
import com.mynok.myagent.core.model.ToolCallAccumulator
import com.mynok.myagent.core.tools.ToolApproval
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolPolicy
import com.mynok.myagent.core.tools.ToolRegistry
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.util.MyAgentJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Agent 运行过程中的事件。 */
sealed interface AgentEvent {

    /** 上下文已组装（可以给「上下文检查器」用）。 */
    data class ContextReady(val assembled: AssembledContext) : AgentEvent

    /** 模型输出的文本增量。 */
    data class AssistantDelta(val text: String) : AgentEvent

    /** 模型的思考过程增量。 */
    data class ReasoningDelta(val text: String) : AgentEvent

    /** 开始调用工具。 */
    data class ToolCallStarted(val call: ToolCall) : AgentEvent

    /** 工具调用结束。 */
    data class ToolCallFinished(
        val call: ToolCall,
        val result: ToolResult,
        val durationMs: Long,
    ) : AgentEvent

    /** 产生了一条应当被持久化的消息（助手消息或工具结果消息）。 */
    data class MessageProduced(val message: ChatMessage) : AgentEvent

    /** 失败。 */
    data class Failed(val error: Throwable) : AgentEvent

    /** 正常结束。 */
    data class Done(val steps: Int) : AgentEvent
}

/**
 * Agent 主循环。
 *
 * 流程：组装上下文 → 调模型 → 若有 tool_call 则执行并回灌 → 继续，直到模型给出最终文本。
 */
class AgentLoop(
    private val registry: ToolRegistry,
    private val contextEngine: ContextEngine = ContextEngine(),
    private val maxSteps: Int = 8,
    private val policy: ToolPolicy = ToolPolicy.Permissive,
) {

    fun run(
        provider: ModelProvider,
        history: List<MessageNode>,
        system: String?,
        userMessage: ChatMessage,
        toolContext: ToolContext,
        budget: ContextBudget,
        /** 限定只允许使用这些工具（技能功能用）；null 表示不限制。 */
        allowedTools: Set<String>? = null,
        /** 思考强度。 */
        reasoningEffort: ReasoningEffort = ReasoningEffort.OFF,
        /** 本次执行的最大轮数；默认沿用构造时的设置，对话页可按会话覆盖。 */
        stepsLimit: Int = maxSteps,
    ): Flow<AgentEvent> = flow {
        val working = history.map { it.message }.toMutableList()
        working += userMessage

        val supportsTools = provider.profile.supportsTools && !registry.isEmpty()
        // 被用户禁用的工具不对模型暴露；技能若限定工具集，再取一次交集
        val toolSpecs = if (supportsTools) {
            registry.specs().filter { spec ->
                policy.isEnabled(spec.name) &&
                    (allowedTools == null || allowedTools.isEmpty() || spec.name in allowedTools)
            }
        } else {
            emptyList()
        }

        var step = 0
        // requestMessages 是「真正发给模型的消息」，每轮都会带上本轮新增的
        // 助手消息与工具结果——否则模型永远看不到工具输出，闭环就是断的。
        var requestMessages = contextEngine.assemble(
            path = history + MessageNode(
                id = "__pending_user__",
                parentId = null,
                message = userMessage,
            ),
            system = system,
            budget = budget,
            summarizer = { messages -> summarize(provider, messages, system) },
        ).messages

        while (step < stepsLimit) {
            emit(
                AgentEvent.ContextReady(
                    AssembledContext(
                        messages = requestMessages,
                        system = system,
                        estimatedTokens = 0,
                        budget = budget.available,
                    ),
                ),
            )

            val request = ModelRequest(
                messages = requestMessages,
                system = system,
                tools = toolSpecs,
                temperature = provider.profile.temperature,
                topP = provider.profile.topP,
                maxTokens = provider.profile.maxOutputTokens,
                stream = true,
                reasoningEffort = reasoningEffort,
                extra = provider.profile.extraBody,
            )

            val accumulator = ToolCallAccumulator()
            val text = StringBuilder()
            val reasoning = StringBuilder()
            var finish = FinishReason.STOP
            var failure: Throwable? = null

            provider.stream(request).collect { event ->
                when (event) {
                    is ModelEvent.TextDelta -> {
                        text.append(event.text)
                        emit(AgentEvent.AssistantDelta(event.text))
                    }

                    is ModelEvent.ReasoningDelta -> {
                        reasoning.append(event.text)
                        emit(AgentEvent.ReasoningDelta(event.text))
                    }

                    is ModelEvent.ToolCallDelta -> accumulator.onDelta(event)

                    is ModelEvent.Completed -> finish = event.finishReason

                    is ModelEvent.Failed -> failure = event.error

                    is ModelEvent.Usage -> Unit
                }
            }

            failure?.let {
                emit(AgentEvent.Failed(it))
                return@flow
            }

            val toolCalls = accumulator.build()
            val assistantMessage = ChatMessage(
                role = Role.ASSISTANT,
                parts = if (text.isNotEmpty()) listOf(ContentPart.Text(text.toString())) else emptyList(),
                toolCalls = toolCalls,
                provider = provider.profile.providerType.name,
                modelId = provider.profile.modelId,
                reasoning = reasoning.toString(),
            )
            emit(AgentEvent.MessageProduced(assistantMessage))
            working += assistantMessage
            val appended = mutableListOf(assistantMessage)

            if (toolCalls.isEmpty()) {
                emit(AgentEvent.Done(step + 1))
                return@flow
            }

            for (call in toolCalls) {
                emit(AgentEvent.ToolCallStarted(call))
                val startedAt = System.currentTimeMillis()
                val result = executeTool(call, toolContext)
                val duration = System.currentTimeMillis() - startedAt
                emit(AgentEvent.ToolCallFinished(call, result, duration))

                val toolMessage = ChatMessage(
                    role = Role.TOOL,
                    parts = listOf(ContentPart.Text(result.content)),
                    name = call.name,
                    toolCallId = call.id,
                )
                emit(AgentEvent.MessageProduced(toolMessage))
                working += toolMessage
                appended += toolMessage
            }

            // 关键：把本轮的助手消息与工具结果并入下一次请求，
            // 否则模型看不到工具输出，多步工具链无法闭环。
            requestMessages = contextEngine.fit(requestMessages + appended, budget)

            step++
        }

        emit(
            AgentEvent.Failed(
                IllegalStateException("已达到最大执行步数（$stepsLimit），任务中止。可提高步数上限或缩小任务范围。"),
            ),
        )
    }

    /** 执行一次工具调用，失败时转成错误结果而不是抛出。 */
    private suspend fun executeTool(call: ToolCall, ctx: ToolContext): ToolResult {
        val tool = registry.get(call.name)
            ?: return ToolResult.error("未知工具：${call.name}")

        if (!policy.isEnabled(call.name)) {
            return ToolResult.error("工具「${call.name}」已被用户禁用，无法调用。")
        }

        val args: JsonObject = runCatching {
            MyAgentJson.parseToJsonElement(call.arguments.ifBlank { "{}" }) as JsonObject
        }.getOrNull() ?: return ToolResult.error("工具参数不是合法的 JSON 对象：${call.arguments.take(200)}")

        if (policy.requiresApproval(tool)) {
            val approved = ctx.approver.request(
                ToolApproval(
                    toolName = call.name,
                    reason = tool.spec.description,
                    risk = tool.risk,
                    detail = call.arguments.take(500),
                ),
            )
            if (!approved) return ToolResult.error("用户拒绝执行该工具（${call.name}）")
        }

        return runCatching { tool.execute(args, ctx) }
            .getOrElse { ToolResult.error("工具执行异常：${it.message ?: it::class.simpleName}") }
    }

    /** 用同一个模型把一段旧消息压缩成摘要。 */
    private suspend fun summarize(
        provider: ModelProvider,
        messages: List<ChatMessage>,
        system: String?,
    ): String? {
        if (messages.isEmpty()) return null
        val transcript = messages.joinToString("\n\n") { m ->
            val role = when (m.role) {
                Role.USER -> "用户"
                Role.ASSISTANT -> "助手"
                Role.TOOL -> "工具(${m.name.orEmpty()})"
                Role.SYSTEM -> "系统"
            }
            "$role: ${m.text.take(2000)}"
        }
        val prompt = buildString {
            appendLine("请把下面的对话压缩成一份简明摘要，保留关键事实、结论、待办和重要数据，丢弃寒暄与重复内容。")
            appendLine("用要点式中文输出，不要超过 400 字。")
            appendLine()
            appendLine(transcript)
        }
        val request = ModelRequest(
            messages = listOf(ChatMessage.user(prompt)),
            system = system,
            tools = emptyList(),
            temperature = 0.2,
            maxTokens = 800,
            stream = false,
        )
        return runCatching { provider.complete(request).message.text }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}

/** 便捷构造：把一段文本解析成 JsonObject，失败返回空对象。 */
fun jsonObjectOf(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to (JsonPrimitive(v) as kotlinx.serialization.json.JsonElement) })
