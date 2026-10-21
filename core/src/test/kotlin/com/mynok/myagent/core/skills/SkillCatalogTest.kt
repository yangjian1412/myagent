package com.mynok.myagent.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCatalogTest {

    private fun skill(name: String, description: String) = SkillDefinition(
        name = name,
        description = description,
        body = "body",
    )

    @Test
    fun `renders nothing when there are no skills`() {
        assertNull(SkillCatalog.render(emptyList()))
    }

    @Test
    fun `renders nothing when all skills lack description`() {
        val skills = listOf(SkillDefinition(name = "a", description = "", body = ""))
        assertNull(SkillCatalog.render(skills))
    }

    @Test
    fun `renders every skill when budget is enough`() {
        val skills = listOf(
            skill("alpha", "处理 A"),
            skill("beta", "处理 B"),
        )
        val text = SkillCatalog.render(skills, maxChars = 4000)!!
        assertTrue(text.contains("name=\"alpha\""))
        assertTrue(text.contains("name=\"beta\""))
        assertTrue(text.contains("处理 A"))
        assertTrue(text.startsWith("<available_skills>"))
        assertTrue(text.trimEnd().endsWith("</available_skills>"))
    }

    @Test
    fun `mentions the read tool name`() {
        val text = SkillCatalog.render(listOf(skill("alpha", "x")), readToolName = "load_skill")!!
        assertTrue(text.contains("load_skill"))
    }

    @Test
    fun `truncates to fit budget but keeps names`() {
        val skills = (1..40).map { skill("skill-$it", "这是一个很长的技能说明用来撑爆预算 $it") }
        val text = SkillCatalog.render(skills, maxChars = 600)!!
        assertTrue("长度应受预算约束: ${text.length}", text.length <= 700)
        // 至少保住了几个名字
        assertTrue(text.contains("name=\"skill-1\""))
    }

    @Test
    fun `escapes xml special characters`() {
        val text = SkillCatalog.render(listOf(skill("a", "包含 <标签> & \"引号\"")))!!
        assertTrue(text.contains("&lt;标签&gt;"))
        assertTrue(text.contains("&amp;"))
        assertFalse(text.contains("<标签>"))
    }

    @Test
    fun `includes tool and env hints`() {
        val s = SkillDefinition(
            name = "x",
            description = "d",
            body = "b",
            allowedTools = listOf("calendar", "contacts"),
            requiredEnv = listOf("TODOIST_API_KEY"),
        )
        val text = SkillCatalog.render(listOf(s))!!
        assertTrue(text.contains("calendar"))
        assertTrue(text.contains("TODOIST_API_KEY"))
    }

    @Test
    fun `newlines in description are flattened`() {
        val text = SkillCatalog.render(listOf(skill("a", "第一行\n第二行")))!!
        assertFalse(text.contains("第一行\n第二行"))
    }

    @Test
    fun `empty catalog string is null not blank`() {
        assertNotNull(SkillCatalog.render(listOf(skill("a", "d"))))
    }
}
