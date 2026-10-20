package com.mynok.myagent.core.model

import com.mynok.myagent.core.model.anthropic.AnthropicProvider
import com.mynok.myagent.core.model.gemini.GeminiProvider
import com.mynok.myagent.core.model.openai.OpenAICompatProvider
import com.mynok.myagent.core.net.Http

/** 根据档案构造对应的协议适配器。 */
object ProviderFactory {

    fun create(profile: ModelProfile, apiKey: String?, http: Http): ModelProvider =
        when (profile.providerType) {
            ProviderType.OPENAI_COMPAT -> OpenAICompatProvider(profile, http, apiKey)
            ProviderType.ANTHROPIC -> AnthropicProvider(profile, http, apiKey)
            ProviderType.GEMINI -> GeminiProvider(profile, http, apiKey)
        }
}
