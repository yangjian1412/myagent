package com.mynok.myagent.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {

    @Test
    fun `parses minimal frontmatter`() {
        val content = """
            ---
            name: my-skill
            description: A description of what this skill does and when to use it.
            ---
            When the user asks for X, do Y.
        """.trimIndent()

        val skill = SkillParser.parse(content, "my-skill")
        assertNotNull(skill)
        assertEquals("my-skill", skill!!.name)
        assertEquals("A description of what this skill does and when to use it.", skill.description)
        assertEquals("When the user asks for X, do Y.", skill.body)
        assertTrue(skill.warnings.isEmpty())
    }

    @Test
    fun `falls back to directory name when frontmatter has no name`() {
        val content = """
            ---
            description: 只有描述没有名字
            ---
            正文
        """.trimIndent()

        val skill = SkillParser.parse(content, "dir-name")
        assertEquals("dir-name", skill?.name)
        assertEquals("dir-name", skill?.slug)
    }

    @Test
    fun `parses allowed-tools as space separated list`() {
        val content = """
            ---
            name: pdf
            description: 处理 PDF
            allowed-tools: Bash(git:*) Read Write
            ---
            body
        """.trimIndent()

        val skill = SkillParser.parse(content)
        assertEquals(listOf("Bash(git:*)", "Read", "Write"), skill?.allowedTools)
    }

    @Test
    fun `parses openclaw metadata block`() {
        val content = """
            ---
            name: todoist-cli
            description: Manage Todoist tasks from the command line.
            version: 1.2.0
            metadata:
              openclaw:
                requires:
                  env:
                    - TODOIST_API_KEY
                  bins:
                    - curl
                primaryEnv: TODOIST_API_KEY
                envVars:
                  - name: TODOIST_PROJECT_ID
                    required: false
                emoji: "✅"
                os:
                  - macos
            ---
            body
        """.trimIndent()

        val skill = SkillParser.parse(content)
        assertNotNull(skill)
        assertEquals("1.2.0", skill!!.version)
        assertEquals("TODOIST_API_KEY", skill.primaryEnv)
        assertEquals(listOf("TODOIST_API_KEY"), skill.requiredEnv)
        assertEquals(listOf("curl"), skill.requiredBins)
        assertEquals(listOf("macos"), skill.requiredOs)
        assertEquals("✅", skill.emoji)
        assertTrue(!skill.isOsCompatible("android"))
        assertTrue(skill.isOsCompatible("macos"))
    }

    @Test
    fun `env vars marked required are added to requirements`() {
        val content = """
            ---
            name: s
            description: d
            metadata:
              openclaw:
                envVars:
                  - name: NEEDED
                    required: true
                  - name: OPTIONAL
                    required: false
            ---
            body
        """.trimIndent()

        val skill = SkillParser.parse(content)
        assertEquals(listOf("NEEDED"), skill?.requiredEnv)
    }

    @Test
    fun `unknown frontmatter fields are ignored`() {
        val content = """
            ---
            name: s
            description: d
            future-field: whatever
            metadata:
              unknown-nested:
                deep: value
            ---
            body
        """.trimIndent()

        val skill = SkillParser.parse(content)
        assertNotNull(skill)
        assertEquals("s", skill?.name)
    }

    @Test
    fun `broken yaml is reported as warning but body still usable`() {
        val content = """
            ---
            name: broken
            description: "未闭合的引号
            ---
            仍然可用的正文
        """.trimIndent()

        val skill = SkillParser.parse(content, "broken")
        assertNotNull(skill)
        assertTrue(skill!!.warnings.isNotEmpty())
        assertEquals("仍然可用的正文", skill.body)
    }

    @Test
    fun `file without frontmatter is treated as body only`() {
        val content = "只有正文，没有 frontmatter。"
        val skill = SkillParser.parse(content, "plain")
        assertEquals("plain", skill?.name)
        assertEquals("只有正文，没有 frontmatter。", skill?.body)
    }

    @Test
    fun `unclosed frontmatter does not lose content`() {
        val content = """
            ---
            name: unclosed
            description: d
            后面没有分隔线了
        """.trimIndent()

        val skill = SkillParser.parse(content, "unclosed")
        assertNotNull(skill)
        assertTrue(skill!!.body.contains("后面没有分隔线了"))
    }

    @Test
    fun `parse returns null when there is no name anywhere`() {
        val content = """
            ---
            description: 只有描述
            ---
            body
        """.trimIndent()

        assertNull(SkillParser.parse(content, ""))
    }

    @Test
    fun `windows line endings are handled`() {
        val content = "---\r\nname: win\r\ndescription: d\r\n---\r\nbody\r\n"
        val skill = SkillParser.parse(content, "win")
        assertEquals("win", skill?.name)
        assertEquals("body", skill?.body)
    }
}
