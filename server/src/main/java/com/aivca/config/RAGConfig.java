package com.aivca.config;

import com.aivca.rag.LlmTextCleaner;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * RAG 配置 —— Zhipu Embedding + InMemoryEmbeddingStore。
 */
@Slf4j
@Configuration
public class RAGConfig {

    @Value("${ZHIPU_API_KEY}") String zhipuKey;

    @Bean
    EmbeddingModel embeddingModel() {
        log.info("[RAG] 初始化 Zhipu EmbeddingModel | modelName=embedding-2");
        return OpenAiEmbeddingModel.builder()
                .baseUrl("https://open.bigmodel.cn/api/paas/v4")
                .apiKey(zhipuKey)
                .modelName("embedding-2")
                .timeout(Duration.ofSeconds(30))
                .maxRetries(2)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean
    InMemoryEmbeddingStore<TextSegment> embeddingStore() {
        log.info("[RAG] 初始化 InMemoryEmbeddingStore");
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    LlmTextCleaner llmTextCleaner(@Qualifier("zhipuFlashModel") ChatLanguageModel flashModel) {
        log.info("[RAG] 初始化 LlmTextCleaner | model=glm-4-flash");
        return new LlmTextCleaner(flashModel);
    }
}
