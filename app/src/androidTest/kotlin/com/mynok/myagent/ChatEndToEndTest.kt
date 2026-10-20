package com.mynok.myagent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mynok.myagent.core.model.ProviderType
import com.mynok.myagent.data.db.ModelProfileEntity
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端到端对话测试（L4）。
 *
 * 依赖：
 * - 手机上跑着 mock OpenAI 兼容服务：
 *   `adb reverse tcp:18080 tcp:18080`，电脑上 `python mock_model_server.py 18080`
 *
 * 测试会自己写入一条指向 mock 的模型档案，不依赖 App 里的手动配置。
 */
@RunWith(AndroidJUnit4::class)
class ChatEndToEndTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: MyAgentApp
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .applicationContext as MyAgentApp

    @Before
    fun setUp() {
        runBlocking {
            app.container.repository.upsertProfile(
                ModelProfileEntity(
                    id = "e2e-mock",
                    name = "E2E Mock",
                    providerType = ProviderType.OPENAI_COMPAT.name,
                    baseUrl = "http://127.0.0.1:18080/v1",
                    modelId = "mock-model",
                    contextWindow = 32_768,
                    maxOutputTokens = 4_096,
                    builtin = false,
                    sortOrder = -1,
                ),
            )
        }
    }

    @Test
    fun sendMessage_getsStreamedReply() {
        composeRule.onNodeWithText("发消息…").performTextInput("hello e2e")
        composeRule.onNodeWithContentDescription("发送").performClick()

        // 等模型回复出现（mock 会回「你好，我是 mock 模型。收到：hello e2e」）
        waitForText("收到：hello e2e")
    }

    @Test
    fun toolCall_isExecutedAndFedBack() {
        composeRule.onNodeWithText("发消息…").performTextInput("please [tool]")
        composeRule.onNodeWithContentDescription("发送").performClick()

        // 工具结果回灌后 mock 会回「收到工具结果了。」
        waitForText("收到工具结果了")
    }

    /** 轮询等待某个文本出现，避免依赖 waitUntil 的重载差异。 */
    private fun waitForText(text: String, timeoutMs: Long = 45_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = try {
                composeRule.onAllNodesWithText(text, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            } catch (e: Throwable) {
                false
            }
            if (found) return
            Thread.sleep(300)
        }
        throw AssertionError("超时：没有等到「$text」")
    }
}
