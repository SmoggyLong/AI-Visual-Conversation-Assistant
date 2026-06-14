package com.aivca.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j LLM 配置 —— DeepSeek + 智谱，均走 OpenAI 兼容协议。
 */
@Configuration
public class LangChain4jConfig {

    @Value("${DEEPSEEK_API_KEY}") String dsKey;
    @Value("${ZHIPU_API_KEY}")    String zpKey;

    @Bean("deepseekModel")
    ChatLanguageModel deepseekModel() {
        return OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com/v1")
                .apiKey(dsKey)
                .modelName("deepseek-chat")
                .timeout(Duration.ofSeconds(30))
                .maxRetries(2)
                .build();
    }

    @Bean("zhipuFlashModel")
    ChatLanguageModel zhipuFlashModel() {
        return OpenAiChatModel.builder()
                .baseUrl("https://open.bigmodel.cn/api/paas/v4")
                .apiKey(zpKey)
                .modelName("glm-4-flash")
                .timeout(Duration.ofSeconds(30))
                .maxRetries(2)
                .build();
    }

    @Bean("zhipu7Model")
    ChatLanguageModel zhipu7Model() {
        return OpenAiChatModel.builder()
                .baseUrl("https://open.bigmodel.cn/api/paas/v4")
                .apiKey(zpKey)
                .modelName("glm-4.7")
                .timeout(Duration.ofSeconds(30))
                .maxRetries(2)
                .build();
    }
}
