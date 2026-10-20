package com.mynok.myagent.core.context

import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart

/**
 * token 估算器。
 *
 * 没有真 tokenizer 时用启发式：中文约 1 字 ≈ 1 token，英文约 4 字符 ≈ 1 token。
 * API 返回真实 usage 后会用真实值校准（见 [TokenCounter.calibrate]）。
 */
fun interface TokenCounter {

    fun countText(text: String): Int

    fun countMessage(message: ChatMessage): Int {
        var total = 4 // 每条消息的结构性开销
        for (part in message.parts) {
            total += when (part) {
                is ContentPart.Text -> countText(part.text)
                // 图片按固定成本估算（不同模型差异大，这里取中性值）
                is ContentPart.Image -> 800
                is ContentPart.File -> countText(part.name) + 200
            }
        }
        for (call in message.toolCalls) {
            total += countText(call.name) + countText(call.arguments) + 8
        }
        return total
    }

    fun countMessages(messages: List<ChatMessage>): Int = messages.sumOf { countMessage(it) }

    companion object {
        /** 启发式实现。 */
        val Heuristic: TokenCounter = TokenCounter { text ->
            if (text.isEmpty()) return@TokenCounter 0
            var cjk = 0
            var other = 0
            for (ch in text) {
                if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F ||
                    ch.code in 0xFF00..0xFFEF
                ) {
                    cjk++
                } else {
                    other++
                }
            }
            // 英文/符号约 4 字符 1 token，中文约 1.4 字 1 token（中文 token 更省）
            cjk * 3 / 4 + (other + 3) / 4
        }
    }
}
