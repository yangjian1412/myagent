package com.mynok.myagent.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.mynok.myagent.core.model.ChatMessage
import com.mynok.myagent.core.model.ContentPart
import com.mynok.myagent.core.model.Role
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 打开会话应该停在最新一条，而不是从顶上开始。
 *
 * 语义树上只会有屏幕附近那几条消息：最晚一条在、第一条不在，
 * 就说明列表一开局就锚定到了末尾。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageListAnchorTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `打开会话就停在最新一条`() {
        val messages = (1..80).map { i ->
            ChatMessage(role = Role.USER, parts = listOf(ContentPart.Text("会话内容第 $i 条")))
        }
        compose.setContent {
            MaterialTheme {
                MessageList(
                    messages = messages,
                    streamingText = "",
                    isStreaming = false,
                    traces = emptyList(),
                    onRegenerate = {},
                    onEdit = {},
                )
            }
        }
        compose.onNodeWithText("会话内容第 80 条").assertExists()
        compose.onNodeWithText("会话内容第 1 条").assertDoesNotExist()
    }
}
