package com.mynok.myagent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示拼装测试。
 *
 * 覆盖：默认名字、自定义名字、偏好顺序、空值容错、会话/技能提示追加。
 */
class SystemPromptTest {

    @Test
    fun `默认名字且无偏好时只包含身份`() {
        val prompt = SystemPrompt.build(agentName = null)
        assertEquals("你的名字是「MyAgent」。", prompt)
    }

    @Test
    fun `名字和偏好按固定顺序拼装`() {
        val prompt = SystemPrompt.build(
            agentName = "小助手",
            preferences = "默认用中文回答；先给结论再解释。",
        ).orEmpty()

        assertTrue(prompt.startsWith("你的名字是「小助手」。"))
        assertTrue(prompt.contains("请遵循用户的长期偏好：\n默认用中文回答；先给结论再解释。"))
        assertFalse(prompt.contains("null"))
    }

    @Test
    fun `名字为空白时回落到默认名字`() {
        assertEquals(
            "你的名字是「MyAgent」。",
            SystemPrompt.build(agentName = "   "),
        )
    }

    @Test
    fun `空白偏好不会产生空的偏好段落`() {
        val prompt = SystemPrompt.build(agentName = "A", preferences = "  \n ").orEmpty()
        assertFalse(prompt.contains("请遵循用户的长期偏好"))
    }

    @Test
    fun `会话提示与技能提示依次追加且忽略空白项`() {
        val prompt = SystemPrompt.build(
            agentName = "A",
            preferences = "简洁",
            extraPrompts = listOf("会话提示", null, "  ", "技能提示"),
        ).orEmpty()

        val lines = prompt.lines()
        assertEquals("你的名字是「A」。", lines[0])
        assertEquals("请遵循用户的长期偏好：", lines[1])
        assertEquals("简洁", lines[2])
        assertEquals("会话提示", lines[3])
        assertEquals("技能提示", lines[4])
        assertEquals(5, lines.size)
    }
}
