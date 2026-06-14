package com.aivca.agent;

import com.aivca.api.llm.model.IntentResult;
import com.aivca.constant.IntentType;
import com.aivca.rag.KnowledgeBase;
import com.aivca.rag.QueryRewriter;
import com.aivca.rag.Reranker;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 路由器 — intent → Agent 映射（4 Agents）。
 */
@Slf4j
@Component
public class AgentRouter {

    private final Map<IntentType, Agent> agentMap = new LinkedHashMap<>();
    private final Agent fallbackAgent;

    public AgentRouter(
            @Qualifier("deepseekModel")   ChatLanguageModel ds,
            @Qualifier("zhipuFlashModel") ChatLanguageModel zpFlash,
            @Qualifier("zhipu7Model")     ChatLanguageModel zp7,
            ObjectMapper objectMapper,
            KnowledgeBase knowledgeBase,
            QueryRewriter rewriter,
            Reranker reranker) {

        agentMap.put(IntentType.VISION,       new VisionAgent(ds, ds, objectMapper));
        agentMap.put(IntentType.KNOWLEDGE,    new KnowledgeAgent(ds, ds, objectMapper,
                                                       knowledgeBase, rewriter, reranker));
        agentMap.put(IntentType.CONVERSATION, new ConversationAgent(zpFlash, zpFlash, objectMapper));
        agentMap.put(IntentType.GAME,         new GameAgent(ds, ds, objectMapper));

        this.fallbackAgent = new ConversationAgent(zpFlash, zpFlash, objectMapper);
    }

    public Agent route(IntentResult result) {
        if (result.getConfidence() < 0.6) {
            log.info("[ROUTE] 置信度过低({})，降级 conversation", result.getConfidence());
            return fallbackAgent;
        }
        Agent agent = agentMap.get(result.getIntent());
        return agent != null ? agent : fallbackAgent;
    }
}
