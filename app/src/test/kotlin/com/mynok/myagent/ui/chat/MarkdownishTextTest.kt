package com.mynok.myagent.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 消息正文渲染的交互。
 *
 * 跑在 Robolectric 上（不需要真机）：验证代码块的复制按钮确实能写剪贴板。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownishTextTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(text: String) {
        compose.setContent {
            MaterialTheme {
                MarkdownishText(text = text, color = Color.Black)
            }
        }
    }

    @Test
    fun `代码块右上角有复制按钮`() {
        setContent("前面这段\n\n```kotlin\nfun main() {}\n```\n\n后面那段")
        compose.onNodeWithContentDescription("复制代码").assertExists()
    }

    @Test
    fun `点复制按钮把代码原样写进剪贴板`() {
        setContent("```kotlin\nfun main() {}\n```")
        compose.onNodeWithContentDescription("复制代码").performClick()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        assertNotNull("点复制后剪贴板应该有内容", clip)
        assertEquals("fun main() {}", clip!!.getItemAt(0).text.toString())
    }

    @Test
    fun `没有代码块时不出现复制按钮`() {
        setContent("就是一段普通文字，没有代码块")
        compose.onNodeWithContentDescription("复制按钮").assertDoesNotExist()
        compose.onNodeWithContentDescription("复制代码").assertDoesNotExist()
    }

    @Test
    fun `代码块里的内容照常显示`() {
        setContent("```kotlin\nval x = 1\n```")
        compose.onNodeWithText("val x = 1").assertExists()
    }

    @Test
    fun `语言标记不当成代码 显示成标签`() {
        setContent("```kotlin\nval x = 1\n```")
        // 标签单独一行，正文里不该混进 "kotlin"
        compose.onNodeWithText("kotlin").assertExists()
        compose.onNodeWithText("val x = 1").assertExists()
    }

    @Test
    fun `没有语言标记时不显示标签`() {
        setContent("```\nval x = 1\n```")
        compose.onNodeWithText("代码").assertExists()
        compose.onNodeWithText("val x = 1").assertExists()
    }

    @Test
    fun `代码第一行是单个词时不会被误当成语言标记`() {
        // 开栏后面直接换行，说明没有语言标记，"echo" 是代码
        setContent("```\necho hi\n```")
        compose.onNodeWithText("echo hi").assertExists()
    }

    @Test
    fun `正文在可选中容器里`() {
        // 走的是 SelectionContainer：长按能选中、能复制
        setContent("这一段应该可以被选中复制")
        compose.onNodeWithText("这一段应该可以被选中复制").assertExists()
    }
}
