package com.aivca.config;

import com.aivca.rag.LlmTextCleaner;
import com.aivca.rag.SpeechCorrector;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * RAG 配置 —— Ollama nomic-embed-text 嵌入模型 + InMemoryEmbeddingStore。
 */
@Slf4j
@Configuration
public class RAGConfig {

    @Bean
    EmbeddingModel embeddingModel() {
        log.info("[RAG] 初始化 Ollama EmbeddingModel | model=nomic-embed-text");
        return OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("nomic-embed-text")
                .timeout(Duration.ofSeconds(60))
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

    @Bean
    SpeechCorrector speechCorrector(@Qualifier("zhipuFlashModel") ChatLanguageModel flashModel) {
        log.info("[RAG] 初始化 SpeechCorrector | model=glm-4-flash");
        return new SpeechCorrector(flashModel);
    }
}
