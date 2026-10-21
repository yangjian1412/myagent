package com.mynok.myagent.core.agent

import app.cash.turbine.test
import com.mynok.myagent.core.context.ContextBudget
import com.mynok.myagent.core.context.MessageNode
import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.FinishReason
import com.mynok.myagent.core.model.ModelEvent
import com.mynok.myagent.core.model.ModelProfile
import com.mynok.myagent.core.model.ModelProvider
import com.mynok.myagent.core.model.ModelRequest
import com.mynok.myagent.core.model.ModelResult
import com.mynok.myagent.core.model.ProviderType
import com.mynok.myagent.core.model.Role
import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.net.Http
import com.mynok.myagent.core.net.HttpRequest
import com.mynok.myagent.core.net.HttpResponse
import com.mynok.myagent.core.tools.FileWorkspace
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolApproval
import com.mynok.myagent.core.tools.ToolApprover
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolRegistry
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.WorkspaceEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent 主循环测试。
 *
 * 用脚本化的假 ModelProvider（按次序返回预设事件）与记录调用的假 Tool，
 * 覆盖单工具闭环、多步工具链、工具异常、非法参数、步数上限与审批分支。
 */
class AgentLoopTest {

    // ------------------------------------------------------------------ 测试替身

    private class ScriptedProvider(
        private val script: List<List<ModelEvent>>,
        override val profile: ModelProfile = ModelProfile(
            id = "fake",
            name = "fake",
            providerType = ProviderType.OPENAI_COMPAT,
            baseUrl = "http://localhost",
            modelId = "fake-model",
            supportsTools = true,
        ),
    ) : ModelProvider {

        val requests = mutableListOf<ModelRequest>()
        val callCount: Int get() = requests.size

        override suspend fun stream(request: ModelRequest): Flow<ModelEvent> {
            requests += request
            val events = script.getOrElse(requests.size - 1) { script.lastOrNull().orEmpty() }
            return flow { events.forEach { emit(it) } }
        }

        override suspend fun complete(request: ModelRequest): ModelResult =
            ModelResult(message = ChatMessage(Role.ASSISTANT, listOf(ContentPart.Text("摘要"))))
    }

    private class RecordingTool(
        override val spec: ToolSpec,
        override val risk: ToolRisk = ToolRisk.READ_ONLY,
        override val requiresApproval: Boolean = false,
        private val handler: suspend (JsonObject) -> ToolResult,
    ) : Tool {
        val calls = mutableListOf<JsonObject>()
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            calls += args
            return handler(args)
        }
    }

    private class FakeToolContext(
        override val approver: ToolApprover,
        override val http: Http = EmptyHttp,
        override val workspace: FileWorkspace = EmptyWorkspace,
    ) : ToolContext {
        val logs = mutableListOf<String>()
        override fun log(message: String) {
            logs += message
        }
    }

    private object EmptyHttp : Http {
        override suspend fun execute(request: HttpRequest): HttpResponse = HttpResponse(200)
        override suspend fun streamText(request: HttpRequest, onChunk: (String) -> Unit): HttpResponse =
            HttpResponse(200)

        override suspend fun downloadBase64(request: HttpRequest): Pair<String, String> = "" to ""
    }

    private object EmptyWorkspace : FileWorkspace {
        override suspend fun list(path: String): List<WorkspaceEntry> = emptyList()
        override suspend fun readText(path: String, maxBytes: Int): String = ""
        override suspend fun writeText(path: String, content: String) = Unit
        override suspend fun exists(path: String): Boolean = false
        override suspend fun delete(path: String): Boolean = false
        override suspend fun mkdir(path: String): Boolean = true
    }

    // ------------------------------------------------------------------ 辅助

    private val budget = ContextBudget(contextWindow = 4096, reserveForOutput = 512)

    private fun toolSpec(name: String = "echo") = ToolSpec(
        name = name,
        description = "回声工具",
        parameters = JsonObject(emptyMap()),
    )

    private fun toolCall(
        name: String = "echo",
        arguments: String = "{}",
        id: String = "call_1",
    ) = ModelEvent.ToolCallDelta(index = 0, id = id, name = name, argumentsDelta = arguments)

    private fun text(text: String) = ModelEvent.TextDelta(text)

    private fun tag(event: AgentEvent): String = when (event) {
        is AgentEvent.ContextReady -> "ContextReady"
        is AgentEvent.AssistantDelta -> "AssistantDelta"
        is AgentEvent.ReasoningDelta -> "ReasoningDelta"
        is AgentEvent.ToolCallStarted -> "ToolCallStarted"
        is AgentEvent.ToolCallFinished -> "ToolCallFinished"
        is AgentEvent.MessageProduced -> "MessageProduced"
        is AgentEvent.Failed -> "Failed"
        is AgentEvent.Done -> "Done"
    }

    private fun argOf(args: JsonObject, name: String): String? =
        (args[name] as? JsonPrimitive)?.contentOrNull

    private suspend fun run(
        loop: AgentLoop,
        provider: ModelProvider,
        ctx: ToolContext,
        userMessage: ChatMessage = ChatMessage.user("开始"),
        history: List<MessageNode> = emptyList(),
    ): List<AgentEvent> = loop.run(
        provider = provider,
        history = history,
        system = "系统提示",
        userMessage = userMessage,
        toolContext = ctx,
        budget = budget,
    ).toList()

    // ------------------------------------------------------------------ 用例

    @Test
    fun `单工具闭环产生正确的事件序列`() = runTest {
        val tool = RecordingTool(toolSpec()) { args ->
            ToolResult.ok("echo:" + argOf(args, "value"))
        }
        val registry = ToolRegistry().apply { register(tool) }
        val provider = ScriptedProvider(
            listOf(
                listOf(toolCall(arguments = "{\"value\":\"hi\"}"), ModelEvent.Completed(FinishReason.TOOL_CALLS)),
                listOf(text("完成"), ModelEvent.Completed(FinishReason.STOP)),
            ),
        )
        val ctx = FakeToolContext(approver = ToolApprover { true })

        val events = run(AgentLoop(registry, maxSteps = 5), provider, ctx)

        assertEquals(
            listOf(
                "ContextReady",
                "MessageProduced",
                "ToolCallStarted",
                "ToolCallFinished",
                "MessageProduced",
                "ContextReady",
                "AssistantDelta",
                "MessageProduced",
                "Done",
            ),
            events.map { tag(it) },
        )

        // 发起工具调用的助手消息
        val produced = events.filterIsInstance<AgentEvent.MessageProduced>()
        val assistantWithCall = produced.first().message
        assertEquals(Role.ASSISTANT, assistantWithCall.role)
        val call = assistantWithCall.toolCalls.single()
        assertEquals("echo", call.name)
        assertEquals("call_1", call.id)
        assertEquals("{\"value\":\"hi\"}", call.arguments)

        // 工具结果消息
        val toolMessage = produced[1].message
        assertEquals(Role.TOOL, toolMessage.role)
        assertEquals("echo", toolMessage.name)
        assertEquals("call_1", toolMessage.toolCallId)
        assertEquals("echo:hi", toolMessage.text)

        // 最终回复
        assertEquals("完成", produced.last().message.text)
        assertEquals(2, (events.last() as AgentEvent.Done).steps)
        assertEquals(1, tool.calls.size)
    }

    @Test
    fun `多步工具链会按顺序调用工具两次`() = runTest {
        val tool = RecordingTool(toolSpec()) { args -> ToolResult.ok("v=" + argOf(args, "v")) }
        val registry = ToolRegistry().apply { register(tool) }
        val provider = ScriptedProvider(
            listOf(
                listOf(toolCall(arguments = "{\"v\":1}", id = "call_1")),
                listOf(toolCall(arguments = "{\"v\":2}", id = "call_2")),
                listOf(text("都完成了")),
            ),
        )
        val ctx = FakeToolContext(approver = ToolApprover { true })

        val events = run(AgentLoop(registry, maxSteps = 8), provider, ctx)

        assertEquals(2, tool.calls.size)
        assertEquals(listOf("1", "2"), tool.calls.map { argOf(it, "v") })
        val started = events.filterIsInstance<AgentEvent.ToolCallStarted>()
        assertEquals(listOf("call_1", "call_2"), started.map { it.call.id })
        assertEquals(3, (events.last() as AgentEvent.Done).steps)
        assertEquals(3, provider.callCount)
    }

    @Test
    fun `工具抛异常时结果被转成错误 ToolResult 回灌且循环不中断`() = runTest {
        val tool = RecordingTool(toolSpec()) { throw IllegalStateException("工具炸了") }
        val registry = ToolRegistry().apply { register(tool) }
        val provider = ScriptedProvider(
            listOf(
                listOf(toolCall(arguments = "{}")),
                listOf(text("继续")),
            ),
        )
        val ctx = FakeToolContext(approver = ToolApprover { true })

        val events = run(AgentLoop(registry, maxSteps = 5), provider, ctx)

        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertTrue("工具异常应产生错误结果", finished.result.isError)
        assertTrue(
            "错误信息应包含异常原因，实际：${finished.result.content}",
            finished.result.content.contains("工具炸了"),
        )

        val toolMessage = events.filterIsInstance<AgentEvent.MessageProduced>()[1].message
        assertEquals(Role.TOOL, toolMessage.role)
        assertTrue(toolMessage.text.contains("工具炸了"))

        // 循环没有被中断
        assertTrue(events.last() is AgentEvent.Done)
        assertEquals("继续", events.filterIsInstance<AgentEvent.MessageProduced>().last().message.text)
    }

    @Test
    fun `模型返回非法 JSON 参数时工具收到错误提示且循环不中断`() = runTest {
        val tool = RecordingTool(toolSpec()) { ToolResult.ok("不该被调用") }
        val registry = ToolRegistry().apply { register(tool) }
        val provider = ScriptedProvider(
            listOf(
                listOf(toolCall(arguments = "{不是合法 JSON")),
                listOf(text("我换个说法")),
            ),
        )
        val ctx = FakeToolContext(approver = ToolApprover { true })

        val events = run(AgentLoop(registry, maxSteps = 5), provider, ctx)

        assertTrue("参数非法时不应调用工具", tool.calls.isEmpty())
        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertTrue(finished.result.isError)
        assertTrue(
            "错误信息应说明参数非法，实际：${finished.result.content}",
            finished.result.content.contains("工具参数不是合法的 JSON 对象"),
        )
        val toolMessage = events.filterIsInstance<AgentEvent.MessageProduced>()[1].message
        assertEquals(Role.TOOL, toolMessage.role)
        assertTrue(toolMessage.text.contains("工具参数不是合法的 JSON 对象"))
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun `达到最大步数时以 Failed 结束`() = runTest {
        val tool = RecordingTool(toolSpec()) { ToolResult.ok("ok") }
        val registry = ToolRegistry().apply { register(tool) }
        val provider = ScriptedProvider(listOf(listOf(toolCall(arguments = "{}"))))
        val ctx = FakeToolContext(approver = ToolApprover { true })

        val events = run(AgentLoop(registry, maxSteps = 2), provider, ctx)

        assertTrue("最后应是 Failed 事件", events.last() is AgentEvent.Failed)
        val failed = events.last() as AgentEvent.Failed
        assertTrue(
            "失败原因应说明达到步数上限，实际：${failed.error.message}",
            failed.error.message!!.contains("已达到最大执行步数"),
        )
        assertEquals(2, tool.calls.size)
        assertEquals(2, provider.callCount)
    }

    @Test
    fun `单次执行可以覆盖构造时的最大步数`() = runTest {
        val tool = RecordingTool(toolSpec()) { ToolResult.ok("ok") }
        val registry = ToolRegistry().apply { register(tool) }
        val provider = ScriptedProvider(
            listOf(
                listOf(toolCall(arguments = "{}")),
                listOf(toolCall(arguments = "{}")),
                listOf(text("完成"), ModelEvent.Completed(FinishReason.STOP)),
            ),
        )

        val events = AgentLoop(registry, maxSteps = 1).run(
            provider = provider,
            history = emptyList(),
            system = "系统提示",
            userMessage = ChatMessage.user("开始"),
            toolContext = FakeToolContext(approver = ToolApprover { true }),
            budget = budget,
            stepsLimit = 3,
        ).toList()

        assertEquals(2, tool.calls.size)
        assertTrue("最后应是 Done 事件", events.last() is AgentEvent.Done)
        assertEquals(3, (events.last() as AgentEvent.Done).steps)
    }

    @Test
    fun `工具需要审批且审批被拒时不执行工具`() = runTest {
        val tool = RecordingTool(
            spec = toolSpec(),
            requiresApproval = true,
        ) { ToolResult.ok("不该执行") }
        val registry = ToolRegistry().apply { register(tool) }
        val provider = ScriptedProvider(
            listOf(
                listOf(toolCall(arguments = "{}")),
                listOf(text("好的")),
            ),
        )
        val approvals = mutableListOf<ToolApproval>()
        val ctx = FakeToolContext(
            approver = ToolApprover { approval ->
                approvals += approval
                false
            },
        )

        val events = run(AgentLoop(registry, maxSteps = 5), provider, ctx)

        assertEquals("审批应当被请求一次", 1, approvals.size)
        assertEquals("echo", approvals[0].toolName)
        assertTrue("审批被拒时工具不应执行", tool.calls.isEmpty())

        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertTrue(finished.result.isError)
        assertTrue(
            "错误信息应说明用户拒绝，实际：${finished.result.content}",
            finished.result.content.contains("用户拒绝执行该工具"),
        )
        val toolMessage = events.filterIsInstance<AgentEvent.MessageProduced>()[1].message
        assertEquals(Role.TOOL, toolMessage.role)
        assertTrue(toolMessage.text.contains("用户拒绝"))
        assertTrue("循环不中断", events.last() is AgentEvent.Done)
    }

    @Test
    fun `工具需要审批且审批通过时工具被执行`() = runTest {
        val tool = RecordingTool(
            spec = toolSpec(),
            requiresApproval = true,
        ) { ToolResult.ok("执行成功") }
        val registry = ToolRegistry().apply { register(tool) }
        val provider = ScriptedProvider(
            listOf(
                listOf(toolCall(arguments = "{}")),
                listOf(text("好的")),
            ),
        )
        val approvals = mutableListOf<ToolApproval>()
        val ctx = FakeToolContext(
            approver = ToolApprover { approval ->
                approvals += approval
                true
            },
        )

        val events = run(AgentLoop(registry, maxSteps = 5), provider, ctx)

        assertEquals(1, approvals.size)
        assertEquals(1, tool.calls.size)
        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertFalse(finished.result.isError)
        assertEquals("执行成功", finished.result.content)
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun `破坏性工具即使不声明 requiresApproval 也会走审批`() = runTest {
        val tool = RecordingTool(
            spec = toolSpec("rm"),
            risk = ToolRisk.DESTRUCTIVE,
        ) { ToolResult.ok("删了") }
        val registry = ToolRegistry().apply { register(tool) }
        val provider = ScriptedProvider(
            listOf(
                listOf(toolCall(name = "rm", arguments = "{}")),
                listOf(text("好了")),
            ),
        )
        var requested = 0
        val ctx = FakeToolContext(
            approver = ToolApprover {
                requested++
                true
            },
        )

        run(AgentLoop(registry, maxSteps = 5), provider, ctx)

        assertEquals("破坏性工具应触发审批", 1, requested)
        assertEquals(1, tool.calls.size)
    }

    @Test
    fun `事件流可以逐个消费并以 Done 收尾`() = runTest {
        val tool = RecordingTool(toolSpec()) { ToolResult.ok("ok") }
        val registry = ToolRegistry().apply { register(tool) }
        val provider = ScriptedProvider(
            listOf(
                listOf(toolCall(arguments = "{}")),
                listOf(text("收尾")),
            ),
        )
        val ctx = FakeToolContext(approver = ToolApprover { true })

        AgentLoop(registry, maxSteps = 5).run(
            provider = provider,
            history = emptyList(),
            system = "系统提示",
            userMessage = ChatMessage.user("开始"),
            toolContext = ctx,
            budget = budget,
        ).test {
            val seen = mutableListOf<String>()
            while (true) {
                val event = awaitItem()
                seen += tag(event)
                if (event is AgentEvent.Done) break
            }
            awaitComplete()
            assertEquals(
                listOf(
                    "ContextReady",
                    "MessageProduced",
                    "ToolCallStarted",
                    "ToolCallFinished",
                    "MessageProduced",
                    "ContextReady",
                    "AssistantDelta",
                    "MessageProduced",
                    "Done",
                ),
                seen,
            )
        }
    }

    @Test
    fun `工具结果没有回灌给下一次模型请求`() = runTest {
        // TODO 被测代码已知问题：AgentLoop 把工具结果消息放进了局部变量 working，
        // 但构造下一次 ModelRequest 时用的是 contextEngine.assemble() 的结果，
        // 而 assemble 只吃 history + 本轮 userMessage，因此工具结果从未进入模型上下文。
        // 这里按正确行为（第二次请求应带上 role=tool 的消息）断言，用例当前会失败。
        val tool = RecordingTool(toolSpec()) { ToolResult.ok("工具输出") }
        val registry = ToolRegistry().apply { register(tool) }
        val provider = ScriptedProvider(
            listOf(
                listOf(toolCall(arguments = "{}")),
                listOf(text("完成")),
            ),
        )
        val ctx = FakeToolContext(approver = ToolApprover { true })

        run(AgentLoop(registry, maxSteps = 5), provider, ctx)

        assertEquals(2, provider.requests.size)
        val second = provider.requests[1]
        val toolMessages = second.messages.filter { it.role == Role.TOOL }
        assertNotNull("第二次模型请求应当带上工具结果消息", toolMessages)
        assertEquals(1, toolMessages.size)
        assertEquals("工具输出", toolMessages.single().text)
        assertEquals("call_1", toolMessages.single().toolCallId)
    }
}
