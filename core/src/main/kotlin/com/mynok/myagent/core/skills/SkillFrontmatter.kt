package com.mynok.myagent.core.skills

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 技能元数据（SKILL.md 的 YAML frontmatter）。
 *
 * 字段分两拨：
 * - Agent Skills 规范里的标准字段：name / description / license / compatibility / metadata
 * - ClawHub 扩展的 `metadata.openclaw` 块：依赖声明、平台限制等
 *
 * 所有字段都是可选的（除了 name 和 description 语义上必需），
 * 这样遇到格式古怪的文件时能尽量解析出能用的部分，而不是整个丢弃。
 */
@Serializable
data class SkillFrontmatter(
    /** 技能名，小写字母/数字/连字符。 */
    val name: String? = null,
    /** 技能做什么、什么时候用。模型靠它判断要不要激活。 */
    val description: String? = null,
    /** 版本号。ClawHub 的 basic frontmatter 把它放在顶层。 */
    val version: String? = null,
    val license: String? = null,
    /** 环境要求说明（面向人类）。 */
    val compatibility: String? = null,
    /** 空格分隔的预授权工具名。 */
    @SerialName("allowed-tools")
    val allowedTools: String? = null,
    /** 自由元数据。 */
    val metadata: SkillMetadata? = null,
)

/** `metadata` 块。标准只要求字符串键值对，ClawHub 额外用了一个嵌套结构。 */
@Serializable
data class SkillMetadata(
    val author: String? = null,
    val version: String? = null,
    val openclaw: OpenClawMetadata? = null,
)

/** ClawHub / OpenClaw 的运行时依赖声明。 */
@Serializable
data class OpenClawMetadata(
    /** 依赖的环境变量、命令行程序、配置文件。 */
    val requires: SkillRequirements? = null,
    /** 主密钥对应的环境变量名。 */
    val primaryEnv: String? = null,
    /** 每个变量的详细声明。 */
    val envVars: List<SkillEnvVar>? = null,
    /** 跳过所有依赖检查。 */
    val always: Boolean? = null,
    /** 覆盖调用时的 key。 */
    val skillKey: String? = null,
    /** 展示用 emoji。 */
    val emoji: String? = null,
    val homepage: String? = null,
    /** 平台限制，如 ["macos"]。 */
    val os: List<String>? = null,
)

/** 技能运行需要的环境。 */
@Serializable
data class SkillRequirements(
    /** 必须存在的环境变量。 */
    val env: List<String>? = null,
    /** 必须全部存在的命令行程序。 */
    val bins: List<String>? = null,
    /** 至少存在一个的命令行程序。 */
    val anyBins: List<String>? = null,
    /** 需要的配置文件。 */
    val config: List<String>? = null,
)

/** 单个环境变量的说明。 */
@Serializable
data class SkillEnvVar(
    val name: String? = null,
    val required: Boolean? = null,
    val description: String? = null,
)
