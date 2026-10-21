package com.mynok.myagent.data.skills

/**
 * 内置技能模板。
 *
 * 用户第一次进技能页时可以一键创建，省得从零写 frontmatter。
 * 模板也说明了标准格式：name + description 是必须的。
 */
object SkillTemplates {

    data class Template(
        val slug: String,
        val name: String,
        val description: String,
        val body: String,
    )

    /** 晨间简报。 */
    val MORNING_BRIEF = Template(
        slug = "morning-brief",
        name = "晨间简报",
        description = "汇总今天要做的事：日程、待办、天气。用在「今天有什么安排」「帮我做个简报」这类问题上。",
        body = """
            # 晨间简报

            请按下面的结构给用户一份简报，用中文，控制在 300 字以内。

            ## 今天的安排
            调用日历工具查询今天的日程，按时间先后列出。没有日程就写「今天没有安排」。

            ## 需要注意
            列出需要提前准备的事情，比如有会议要准备材料、要联系的联系人。

            ## 今日提示
            一两句概括今天的重点。

            规则：
            - 只汇报事实，不要编造日程
            - 日历没有授权时直接说明「需要日历权限」，不要猜测
        """.trimIndent(),
    )

    /** 翻译。 */
    val TRANSLATE = Template(
        slug = "translator",
        name = "翻译助手",
        description = "把用户给的内容翻译成另一种语言，并保持原文格式。用在「翻译成英文」「这段话什么意思」这类问题上。",
        body = """
            # 翻译助手

            规则：
            - 默认译成中文；用户指定了目标语言就译成那个语言
            - 保留原文的段落、列表、代码块结构
            - 术语第一次出现时给出括号内的原文
            - 拿不准的地方标注「原文歧义：…」
            - 不要额外解释语法，除非用户问
        """.trimIndent(),
    )

    /** 代码审查。 */
    val CODE_REVIEW = Template(
        slug = "code-review",
        name = "代码审查",
        description = "审查代码改动，指出正确性、边界条件和安全问题。用在「帮我 review 这个 diff」「看看这段代码有没有问题」上。",
        body = """
            # 代码审查

            审查代码时按严重程度分级，每条都要能指出具体位置：

            1. **阻塞问题** — 会出错、崩溃、数据丢失、安全漏洞
            2. **需要注意** — 边界条件没处理、并发问题、性能隐患
            3. **建议** — 可读性、命名、可以更简洁的写法

            规则：
            - 先说结论，再说理由
            - 引用具体代码行，不要泛泛而谈
            - 没有发现问题时明确说「没发现阻塞问题」，不要硬凑
            - 指出问题时给出修复思路，但不要直接改代码（除非用户要求）
        """.trimIndent(),
    )

    /** 写作润色。 */
    val WRITING = Template(
        slug = "writing-polish",
        name = "写作润色",
        description = "润色用户给的文字：改通顺、控篇幅、调语气。用在「帮我改一下这段」「写得更简洁些」上。",
        body = """
            # 写作润色

            规则：
            - 保留原意和作者语气，不擅自改立场
            - 删掉冗余和空话，句子尽量短
            - 给出润色后的完整版本
            - 后面附一小段「改动说明」，只列有意义的改动
            - 如果原文有事实或逻辑问题，指出但不要自己编内容替换
        """.trimIndent(),
    )

    /** 全部模板。 */
    val all: List<Template> = listOf(MORNING_BRIEF, TRANSLATE, CODE_REVIEW, WRITING)

    /**
     * 渲染成 SKILL.md 内容。
     */
    fun toSkillFile(template: Template): String = buildString {
        appendLine("---")
        appendLine("name: ${template.slug}")
        appendLine("description: ${template.description}")
        appendLine("---")
        appendLine()
        appendLine(template.body)
    }
}
