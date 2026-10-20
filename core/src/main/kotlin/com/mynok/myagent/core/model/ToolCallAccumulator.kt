package com.mynok.myagent.core.model

/**
 * 工具调用的分片累加器。
 *
 * 三家协议在流式返回工具调用时都是「分片下发」：
 * - OpenAI：`tool_calls[i].id` / `.function.name` / `.function.arguments` 分块
 * - Anthropic：`content_block_start` 给 id+name，`input_json_delta` 给参数分片
 * - Gemini：一次性给完整 functionCall
 *
 * 统一在这里拼成完整的 [ToolCall]。
 */
class ToolCallAccumulator {

    private class Slot(
        var id: String? = null,
        var name: String? = null,
        val args: StringBuilder = StringBuilder(),
    )

    private val slots = linkedMapOf<Int, Slot>()

    fun onDelta(delta: ModelEvent.ToolCallDelta) {
        val slot = slots.getOrPut(delta.index) { Slot() }
        delta.id?.takeIf { it.isNotBlank() }?.let { slot.id = it }
        delta.name?.takeIf { it.isNotBlank() }?.let { slot.name = it }
        delta.argumentsDelta?.let { slot.args.append(it) }
    }

    /** 产出一组完整的工具调用。 */
    fun build(): List<ToolCall> = slots.entries
        .sortedBy { it.key }
        .mapIndexed { seq, (index, slot) ->
            ToolCall(
                id = slot.id?.takeIf { it.isNotBlank() } ?: "call_${index}_${seq}",
                name = slot.name.orEmpty(),
                arguments = slot.args.toString().ifBlank { "{}" },
            )
        }
        .filter { it.name.isNotBlank() }

    fun isEmpty(): Boolean = slots.isEmpty()
}
