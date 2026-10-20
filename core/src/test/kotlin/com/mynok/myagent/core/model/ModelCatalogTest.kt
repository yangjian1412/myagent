package com.mynok.myagent.core.model

import com.mynok.myagent.core.net.HttpResponse
import com.mynok.myagent.core.testing.FakeHttp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    private fun CatalogResult.success(): CatalogResult.Success {
        assertTrue("期望成功，实际是 $this", this is CatalogResult.Success)
        return this as CatalogResult.Success
    }

    private fun CatalogResult.failure(): CatalogResult.Failure {
        assertTrue("期望失败，实际是 $this", this is CatalogResult.Failure)
        return this as CatalogResult.Failure
    }

    @Test
    fun openAiCompatibleParsesIdList() = runTest {
        val http = FakeHttp(
            HttpResponse(
                200,
                body = """{"object":"list","data":[
                    {"id":"gpt-4o-mini","object":"model"},
                    {"id":"gpt-4o","object":"model"}
                ]}""",
            ),
        )
        val success = ModelCatalog(http).list(
            ProviderType.OPENAI_COMPAT,
            "https://api.example.com/v1",
            "sk-test",
        ).success()
        assertEquals("https://api.example.com/v1/models", success.endpoint)
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), success.models.map { it.id })
        assertEquals("Bearer sk-test", http.requests.single().headers["Authorization"])
        // 原生 OpenAI 不给上限
        assertNull(success.models.first().contextWindow)
    }

    @Test
    fun baseUrlWithoutV1GetsSuffix() = runTest {
        val http = FakeHttp(HttpResponse(200, body = """{"data":[{"id":"local-model"}]}"""))
        val success = ModelCatalog(http)
            .list(ProviderType.OPENAI_COMPAT, "http://192.168.1.5:8080/", null).success()
        assertEquals("http://192.168.1.5:8080/v1/models", success.endpoint)
        assertTrue(http.requests.single().headers.isEmpty())
    }

    @Test
    fun openRouterExposesLimitsAndReasoning() = runTest {
        val http = FakeHttp(
            HttpResponse(
                200,
                body = """{"data":[{
                    "id":"vendor/reasoner",
                    "context_length":131072,
                    "top_provider":{"max_completion_tokens":8192},
                    "architecture":{"input_modalities":["text","image"]},
                    "supported_parameters":["tools","reasoning","reasoning_effort"]
                }]}""",
            ),
        )
        val model = ModelCatalog(http)
            .list(ProviderType.OPENAI_COMPAT, "https://openrouter.ai/api/v1", "k")
            .success().models.single()
        assertEquals(131_072, model.contextWindow)
        assertEquals(8_192, model.maxOutputTokens)
        assertEquals(true, model.supportsVision)
        assertEquals(true, model.supportsTools)
        assertEquals(
            listOf(ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            model.reasoningEfforts,
        )
    }

    @Test
    fun anthropicUsesOwnHeadersAndStripsV1() = runTest {
        val http = FakeHttp(
            HttpResponse(
                200,
                body = """{"data":[{"id":"claude-sonnet-4","display_name":"Claude Sonnet 4"}]}""",
            ),
        )
        val success = ModelCatalog(http)
            .list(ProviderType.ANTHROPIC, "https://api.anthropic.com/v1", "sk-ant").success()
        assertEquals("https://api.anthropic.com/v1/models?limit=1000", success.endpoint)
        assertEquals("claude-sonnet-4", success.models.single().id)
        assertEquals("Claude Sonnet 4", success.models.single().displayName)
        val request = http.requests.single()
        assertEquals("sk-ant", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
    }

    @Test
    fun geminiReadsTokenLimitsAndFiltersNonChatModels() = runTest {
        val http = FakeHttp(
            HttpResponse(
                200,
                body = """{"models":[
                    {"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro",
                     "inputTokenLimit":1048576,"outputTokenLimit":65536,
                     "supportedGenerationMethods":["generateContent","countTokens"]},
                    {"name":"models/text-embedding-004",
                     "supportedGenerationMethods":["embedContent"]}
                ]}""",
            ),
        )
        val success = ModelCatalog(http)
            .list(ProviderType.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "g-key").success()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
            success.endpoint,
        )
        val model = success.models.single()
        assertEquals("gemini-2.5-pro", model.id)
        assertEquals(1_048_576, model.contextWindow)
        assertEquals(65_536, model.maxOutputTokens)
        assertEquals("g-key", http.requests.single().headers["x-goog-api-key"])
    }

    @Test
    fun httpErrorBecomesFailure() = runTest {
        val http = FakeHttp(HttpResponse(401, body = "unauthorized"))
        val failure = ModelCatalog(http)
            .list(ProviderType.OPENAI_COMPAT, "https://x/v1", "bad").failure()
        assertTrue(failure.message.contains("401"))
    }

    @Test
    fun blankBaseUrlFailsFast() = runTest {
        val http = FakeHttp()
        ModelCatalog(http).list(ProviderType.OPENAI_COMPAT, "  ", null).failure()
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun presetsGuessKnownModels() {
        assertEquals(4, ModelPresets.lookup("gpt-5")?.reasoningEfforts?.size)
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            ModelPresets.lookup("o3-mini")?.reasoningEfforts,
        )
        // 2.5 Pro 不能关闭思考，所以没有最低档
        assertEquals(
            listOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            ModelPresets.lookup("gemini-2.5-pro-latest")?.reasoningEfforts,
        )
        assertEquals(200_000, ModelPresets.lookup("claude-sonnet-4-20250514")?.contextWindow)
        assertNull(ModelPresets.lookup("totally-unknown"))
        assertNull(ModelPresets.lookup("")?.reasoningEfforts?.takeIf { it.isNotEmpty() })
    }
}
