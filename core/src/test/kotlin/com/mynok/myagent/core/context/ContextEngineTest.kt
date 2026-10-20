package com.mynok.myagent.core.context

import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文引擎测试：压缩、裁剪、pinned 保护、工具结果截断、预算计算。
 */
class ContextEngineTest {

    private val engine = ContextEngine()

    private fun node(
        id: String,
        text: String,
        pinned: Boolean = false,
        role: Role = Role.USER,
        kind: MessageNode.Kind = MessageNode.Kind.MESSAGE,
    ) = MessageNode(
        id = id,
        parentId = null,
        message = ChatMessage(role, listOf(ContentPart.Text(text))),
        pinned = pinned,
        kind = kind,
    )

    private fun toolNode(id: String, text: String) = node(id, text, role = Role.TOOL)

    /** 一个够大但不会触发裁剪的预算。 */
    private val roomy = ContextBudget(contextWindow = 4000, reserveForOutput = 1000)

    /** available = 900 的预算。 */
    private val tight = ContextBudget(contextWindow = 1000, reserveForOutput = 100)

    @Test
    fun `未超预算时原样返回不压缩不裁剪`() = runTest {
        val path = listOf(node("n1", "第一条"), node("n2", "第二条"))
        var summarizerCalled = false

        val result = engine.assemble(path, system = null, budget = roomy) {
            summarizerCalled = true
            "不该被调用的摘要"
        }

        assertFalse("未超预算时不应调用 summarizer", summarizerCalled)
        assertEquals(2, result.messages.size)
        assertEquals(listOf("第一条", "第二条"), result.messages.map { it.text })
        assertTrue(result.droppedNodeIds.isEmpty())
        assertTrue(result.summarizedNodeIds.isEmpty())
        assertFalse(result.compacted)
    }

    @Test
    fun `超预算时触发 summarizer 并把摘要放进结果`() = runTest {
        val long = "文".repeat(500)
        val path = listOf(node("n0", long), node("n1", long), node("n2", long))
        var summarizerCalls = 0
        var summarized: List<ChatMessage> = emptyList()

        val result = engine.assemble(path, system = null, budget = tight) { messages ->
            summarizerCalls++
            summarized = messages
            "摘要内容"
        }

        assertEquals("summarizer 应被调用一次", 1, summarizerCalls)
        assertEquals("summarizer 应拿到最早的两条消息", 2, summarized.size)
        assertEquals(long, summarized[0].text)

        assertEquals(2, result.messages.size)
        assertTrue(
            "结果里应出现摘要消息，实际：${result.messages[0].text}",
            result.messages[0].text.contains("摘要内容"),
        )
        assertTrue(result.messages[0].text.contains("[以下是更早对话的摘要]"))
        assertEquals(long, result.messages[1].text)

        assertEquals(listOf("n0", "n1"), result.summarizedNodeIds)
        assertTrue(result.compacted)
    }

    @Test
    fun `摘要之后仍然超预算会继续裁剪并记录 droppedNodeIds`() = runTest {
        val long = "文".repeat(600)
        val path = (0..5).map { node("n$it", long) }

        val result = engine.assemble(path, system = null, budget = tight) { "短摘要" }

        assertTrue("裁剪后 droppedNodeIds 不应为空", result.droppedNodeIds.isNotEmpty())
        assertTrue("n2 应在裁剪列表里，实际：${result.droppedNodeIds}", "n2" in result.droppedNodeIds)
        assertTrue(result.compacted)
        assertTrue("裁剪后消息数量应变少", result.messages.size < path.size)
    }

    @Test
    fun `摘要不应该比原始消息更早被丢弃`() = runTest {
        // TODO 被测代码已知问题：裁剪阶段用 indexOfFirst { !it.pinned } 找要丢的条目，
        // 而压缩产生的摘要条目排在最前面且没有 pinned 标记，于是摘要会先被丢掉，
        // 压缩就白做了。这里按正确行为断言，用例当前会失败。
        val long = "文".repeat(600)
        val path = (0..5).map { node("n$it", long) }

        val result = engine.assemble(path, system = null, budget = tight) { "短摘要" }

        assertTrue(
            "裁剪后摘要仍应保留在结果里，实际结果：${result.messages.map { it.text.take(20) }}",
            result.messages.any { it.text.contains("短摘要") },
        )
    }

    @Test
    fun `pinned 消息永远不会被裁剪`() = runTest {
        val pinnedText = "钉".repeat(300)
        val long = "文".repeat(600)
        val path = listOf(node("pinned", pinnedText, pinned = true)) +
            (0..7).map { node("n$it", long) }

        val result = engine.assemble(path, system = null, budget = tight) { "短摘要" }

        assertTrue(
            "pinned 消息必须留在结果里，实际结果：${result.messages.map { it.text.take(10) }}",
            result.messages.any { it.text == pinnedText },
        )
        assertTrue("pinned 节点不应出现在 droppedNodeIds", "pinned" !in result.droppedNodeIds)
    }

    @Test
    fun `pinned 消息即使单独就超预算也不会被裁剪`() = runTest {
        // TODO 被测代码已知问题：MessageNode 的文档写明「钉住的消息永远不参与压缩与裁剪」，
        // 但裁剪循环在找不到非 pinned 条目时会把 target 退化成 0，从而删掉 pinned 消息。
        // 这里按正确行为断言，用例当前会失败。
        val pinnedText = "钉".repeat(2000)
        val path = listOf(node("pinned", pinnedText, pinned = true), node("n1", "普通消息"))

        val result = engine.assemble(path, system = null, budget = tight) { "摘要" }

        assertTrue(
            "pinned 消息必须留在结果里，实际结果条数：${result.messages.size}",
            result.messages.any { it.text == pinnedText },
        )
    }

    @Test
    fun `超长的工具结果会被截断`() = runTest {
        val huge = "文".repeat(1100)
        val path = listOf(node("n1", "hi"), toolNode("t1", huge))

        val result = engine.assemble(path, system = null, budget = tight) { "摘要" }

        val toolMessage = result.messages.last()
        assertEquals(Role.TOOL, toolMessage.role)
        assertTrue(
            "工具结果应带截断标记，实际结尾：${toolMessage.text.takeLast(40)}",
            toolMessage.text.contains("内容过长已截断"),
        )
        assertTrue("原始内容不应该原样保留", toolMessage.text.length < huge.length)
        assertTrue(toolMessage.text.startsWith("文"))
    }

    @Test
    fun `不太长的工具结果保持原样`() = runTest {
        val path = listOf(node("n1", "hi"), toolNode("t1", "短结果"))

        val result = engine.assemble(path, system = null, budget = tight) { "摘要" }

        assertEquals("短结果", result.messages.last().text)
    }

    @Test
    fun `ContextBudget available 等于窗口减去输出预留`() {
        assertEquals(6976, ContextBudget(contextWindow = 8000, reserveForOutput = 1024).available)
        assertEquals(900, ContextBudget(contextWindow = 1000, reserveForOutput = 100).available)
        // 预留超过窗口时实现会兜底到 256，这里记录实际行为
        assertEquals(256, ContextBudget(contextWindow = 100, reserveForOutput = 50).available)
    }

    @Test
    fun `system 提示不参与压缩但会占用预算`() = runTest {
        val path = listOf(node("n1", "第一条"), node("n2", "第二条"))

        val result = engine.assemble(path, system = "很长的系统提示".repeat(10), budget = roomy) { "摘要" }

        assertEquals("系统提示单独传递，不混进 messages", 2, result.messages.size)
        assertEquals("很长的系统提示".repeat(10), result.system)
    }

    // ------------------------------------------------------------------ 消息树

    @Test
    fun `MessageTree 追加消息后能取到根到叶子的路径`() {
        val tree = MessageTree()
        val root = tree.append(null, ChatMessage.user("第一句"), id = "m1")
        val second = tree.append(root.id, ChatMessage.assistant("第二句"), id = "m2")

        val path = tree.pathTo(second.id)

        assertEquals(listOf("m1", "m2"), path.map { it.id })
        assertEquals("第二句", path.last().message.text)
        assertEquals(listOf("m2"), tree.childrenOf("m1").map { it.id })
        assertEquals(listOf("m2"), tree.leaves().map { it.id })
        assertEquals(2, tree.size)
    }

    @Test
    fun `MessageTree 分叉会复制出新的兄弟节点`() {
        val tree = MessageTree()
        val root = tree.append(null, ChatMessage.user("第一句"), id = "m1")
        tree.append(root.id, ChatMessage.assistant("第二句"), id = "m2")

        val copied = tree.forkFrom("m1", "m1", "copy")

        assertEquals(1, copied.size)
        assertEquals("copy-m2", copied[0].id)
        assertEquals("m1", copied[0].parentId)
        assertEquals("第二句", copied[0].message.text)
        assertEquals(listOf("m2", "copy-m2"), tree.childrenOf("m1").map { it.id })
        assertEquals(3, tree.size)
    }

    @Test
    fun `MessageTree 删除节点会连带删除子节点`() {
        val tree = MessageTree()
        val root = tree.append(null, ChatMessage.user("第一句"), id = "m1")
        tree.append(root.id, ChatMessage.assistant("第二句"), id = "m2")

        tree.remove(root.id)

        assertEquals(0, tree.size)
        assertNull(tree.get("m1"))
        assertNull(tree.get("m2"))
    }
}
