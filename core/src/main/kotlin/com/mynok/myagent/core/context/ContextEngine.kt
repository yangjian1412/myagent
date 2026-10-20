package com.mynok.myagent.core.context

import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.Role
import com.mynok.myagent.core.model.ToolCall

/** 摘要消息的前缀标记。带这个前缀的消息在裁剪阶段受保护。 */
const val SUMMARY_MARKER = "[以下是更早对话的摘要]"

/**
 * 消息树节点。
 *
 * 用树而不是线性列表，是为了让「编辑历史消息」「从第 N 条重新生成」这类操作
 * 自然地产生分支而不是破坏历史。
 */
data class MessageNode(
    val id: String,
    val parentId: String?,
    val message: ChatMessage,
    val children: List<String> = emptyList(),
    /** 钉住的消息永远不参与压缩与裁剪。 */
    val pinned: Boolean = false,
    val kind: Kind = Kind.MESSAGE,
    val createdAt: Long = 0L,
) {
    enum class Kind {
        /** 普通消息。 */
        MESSAGE,

        /** 历史摘要（由压缩生成），本身也是一条消息。 */
        SUMMARY,
    }
}

/**
 * 消息树。
 *
 * 树结构由 app 层持久化（Room），core 只提供算法。
 */
class MessageTree(initial: List<MessageNode> = emptyList()) {

    private val nodes: MutableMap<String, MessageNode> = initial.associateBy { it.id }.toMutableMap()

    val size: Int get() = nodes.size

    fun all(): List<MessageNode> = nodes.values.toList()

    fun get(id: String): MessageNode? = nodes[id]

    fun add(node: MessageNode) {
        nodes[node.id] = node
        val parentId = node.parentId ?: return
        val parent = nodes[parentId] ?: return
        if (node.id !in parent.children) {
            nodes[parentId] = parent.copy(children = parent.children + node.id)
        }
    }

    fun remove(id: String) {
        val node = nodes.remove(id) ?: return
        node.children.forEach { remove(it) }
        val parentId = node.parentId ?: return
        val parent = nodes[parentId] ?: return
        nodes[parentId] = parent.copy(children = parent.children.filter { it != id })
    }

    /** 根到 [leafId] 的路径（含自身），也就是当前激活的分支。 */
    fun pathTo(leafId: String): List<MessageNode> {
        val path = mutableListOf<MessageNode>()
        var current = nodes[leafId] ?: return path
        while (true) {
            path += current
            current = current.parentId?.let { nodes[it] } ?: break
        }
        return path.reversed()
    }

    /** 某个节点的所有子分支（用于"重新生成"产生兄弟节点）。 */
    fun childrenOf(id: String): List<MessageNode> =
        nodes[id]?.children?.mapNotNull { nodes[it] }.orEmpty()

    /** 树中所有叶子。 */
    fun leaves(): List<MessageNode> = nodes.values.filter { it.children.isEmpty() }

    /** 最新的叶子（没有则取根）。 */
    fun latestLeaf(): MessageNode? = leaves().maxByOrNull { it.createdAt }

    /**
     * 追加一条消息到 [parentId] 之下，返回新节点。
     */
    fun append(parentId: String?, message: ChatMessage, id: String, kind: MessageNode.Kind = MessageNode.Kind.MESSAGE): MessageNode {
        val node = MessageNode(
            id = id,
            parentId = parentId,
            message = message,
            children = emptyList(),
            pinned = false,
            kind = kind,
            createdAt = System.currentTimeMillis(),
        )
        add(node)
        return node
    }

    /** 复制 [sourceId] 之后的所有消息到 [newParentId] 之下（用于分支）。 */
    fun forkFrom(sourceId: String, newParentId: String?, idPrefix: String): List<MessageNode> {
        val source = nodes[sourceId] ?: return emptyList()
        val copied = mutableListOf<MessageNode>()
        var parent = newParentId
        for (childId in source.children) {
            val child = nodes[childId] ?: continue
            val newId = "$idPrefix-$childId"
            val newNode = child.copy(
                id = newId,
                parentId = parent,
                children = emptyList(),
                createdAt = System.currentTimeMillis(),
            )
            add(newNode)
            copied += newNode
            parent = newId
            copied += forkFrom(childId, newId, idPrefix)
        }
        return copied
    }
}

/**
 * 上下文预算。
 */
data class ContextBudget(
    /** 模型上下文窗口。 */
    val contextWindow: Int,
    /** 给模型输出预留的 token。 */
    val reserveForOutput: Int = 1_024,
    /** 工具结果最多可占用的比例（防止一个大网页吃掉整个窗口）。 */
    val toolResultShare: Double = 0.30,
    /** 压缩摘要的目标 token 数。 */
    val summaryTargetTokens: Int = 600,
) {
    /** 可用于历史消息的 token 数。 */
    val available: Int get() = (contextWindow - reserveForOutput).coerceAtLeast(256)
}

/**
 * 组装好的上下文。
 */
data class AssembledContext(
    val messages: List<ChatMessage>,
    val system: String?,
    val estimatedTokens: Int,
    val budget: Int,
    /** 被裁剪掉的消息 id。 */
    val droppedNodeIds: List<String> = emptyList(),
    /** 被压缩成摘要的节点 id。 */
    val summarizedNodeIds: List<String> = emptyList(),
    /** 触发压缩/裁剪时为 true，UI 可以给出提示。 */
    val compacted: Boolean = false,
)

/**
 * 上下文引擎：把消息树路径压成一组真正要发给模型的消息。
 *
 * 流程：
 * 1. 展开分支路径
 * 2. 统计 token
 * 3. 超预算 → 把最早的一段非 pinned 消息交给 summarizer 压缩成一条摘要
 * 4. 仍超预算 → 从旧到新逐条丢弃非 pinned 消息
 * 5. 工具结果单独按 [ContextBudget.toolResultShare] 截断
 */
class ContextEngine(
    private val tokenCounter: TokenCounter = TokenCounter.Heuristic,
) {

    /**
     * @param path 当前分支的消息节点（根 → 叶子）
     * @param system 系统提示
     * @param budget 预算
     * @param summarizer 压缩器：给它一段消息，返回摘要文本；返回 null 表示不压缩
     */
    suspend fun assemble(
        path: List<MessageNode>,
        system: String?,
        budget: ContextBudget,
        summarizer: suspend (List<ChatMessage>) -> String?,
    ): AssembledContext {
        val dropped = mutableListOf<String>()
        val summarized = mutableListOf<String>()

        // 节点 id 与消息一一对应，方便裁剪时同步维护
        val entries = path.map { Entry(it.id, it.pinned, it.kind, it.message) }.toMutableList()

        var compacted = false
        fun totalTokens(): Int =
            tokenCounter.countMessages(entries.map { it.message }) +
                (system?.let { tokenCounter.countText(it) } ?: 0)

        var tokens = totalTokens()

        // --- 1. 压缩：把最早的一段非 pinned 消息压成一条摘要 ---
        if (tokens > budget.available) {
            val compressible = entries.withIndex()
                .filter { (i, e) ->
                    !e.pinned && e.kind != MessageNode.Kind.SUMMARY && e.message.role != Role.SYSTEM
                }
                .map { it.index }

            if (compressible.size >= 2) {
                val target = (budget.available * 0.4).toInt().coerceAtLeast(budget.summaryTargetTokens)
                var acc = 0
                val cutPos = compressible.indexOfFirst { idx ->
                    acc += tokenCounter.countMessage(entries[idx].message)
                    acc >= target
                }.let { if (it < 0) compressible.lastIndex else it }

                val sliceIdx = compressible.take(cutPos + 1)
                val sliceMessages = sliceIdx.map { entries[it].message }
                val summary = runCatching { summarizer(sliceMessages) }.getOrNull()
                if (!summary.isNullOrBlank()) {
                    val firstIdx = sliceIdx.first()
                    val lastIdx = sliceIdx.last()
                    val summaryMessage = ChatMessage(
                        role = Role.USER,
                        parts = listOf(ContentPart.Text("$SUMMARY_MARKER\n$summary")),
                    )
                    val removed = entries.subList(firstIdx, lastIdx + 1).mapNotNull { it.nodeId }
                    entries.subList(firstIdx, lastIdx + 1).clear()
                    // 摘要本身要受保护：它是压缩的成果，不能在裁剪阶段被优先丢掉
                    entries.add(firstIdx, Entry(null, true, MessageNode.Kind.SUMMARY, summaryMessage))
                    summarized.addAll(removed)
                    dropped.addAll(removed)
                    compacted = true
                    tokens = totalTokens()
                }
            }
        }

        // --- 2. 裁剪：从旧到新丢弃非 pinned 消息 ---
        // pinned（含摘要）永远不参与裁剪；即使只剩 pinned，也不再删，宁可超预算
        while (tokens > budget.available && entries.isNotEmpty()) {
            val idx = entries.indexOfFirst { !it.pinned }
            if (idx < 0) break
            val removed = entries.removeAt(idx)
            removed.nodeId?.let { dropped += it }
            compacted = true
            tokens = totalTokens()
        }

        // --- 3. 工具结果单独配额 ---
        val toolBudget = (budget.available * budget.toolResultShare).toInt()
        val messages = entries.map { truncateToolResult(it.message, toolBudget) }

        return AssembledContext(
            messages = messages,
            system = system,
            estimatedTokens = tokens,
            budget = budget.available,
            droppedNodeIds = dropped.distinct(),
            summarizedNodeIds = summarized.distinct(),
            compacted = compacted,
        )
    }

    /**
     * 只裁剪不压缩。
     *
     * agent 循环里每轮会追加「助手消息 + 工具结果」，需要腾出空间，
     * 但不应该反复触发摘要（那会重复调用模型）。
     */
    fun fit(messages: List<ChatMessage>, budget: ContextBudget): List<ChatMessage> {
        val entries = messages
            .map { Entry(null, isProtected(it), MessageNode.Kind.MESSAGE, it) }
            .toMutableList()
        var tokens = tokenCounter.countMessages(messages)
        while (tokens > budget.available && entries.isNotEmpty()) {
            val idx = entries.indexOfFirst { !it.pinned }
            if (idx < 0) break
            entries.removeAt(idx)
            tokens = tokenCounter.countMessages(entries.map { it.message })
        }
        val toolBudget = (budget.available * budget.toolResultShare).toInt()
        return entries.map { truncateToolResult(it.message, toolBudget) }
    }

    /** 摘要消息受保护。 */
    private fun isProtected(message: ChatMessage): Boolean =
        message.parts.filterIsInstance<ContentPart.Text>()
            .any { it.text.startsWith(SUMMARY_MARKER) }

    private data class Entry(
        val nodeId: String?,
        val pinned: Boolean,
        val kind: MessageNode.Kind,
        val message: ChatMessage,
    )

    /** 截断过长的工具结果文本。 */
    private fun truncateToolResult(message: ChatMessage, maxTokens: Int): ChatMessage {
        if (message.role != Role.TOOL) return message
        if (tokenCounter.countMessage(message) <= maxTokens) return message
        val limitChars = (maxTokens * 4).coerceAtLeast(200)
        val newParts = message.parts.map { part ->
            if (part is ContentPart.Text && part.text.length > limitChars) {
                ContentPart.Text(part.text.take(limitChars) + "\n…（内容过长已截断）")
            } else {
                part
            }
        }
        return message.copy(parts = newParts)
    }
}

private fun Boolean?.orFalse(): Boolean = this ?: false
