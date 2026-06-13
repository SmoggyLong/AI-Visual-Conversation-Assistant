package com.aivca.api.llm.router;

import com.aivca.api.llm.ZhipuChatService;
import com.aivca.api.llm.agent.*;
import com.aivca.api.llm.model.IntentResult;
import com.aivca.constant.IntentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 路由器 — intent → Agent 映射。
 */
@Slf4j
public class AgentRouter {

    private final Map<IntentType, Agent> agentMap = new LinkedHashMap<>();
    private final GeneralAgent generalAgent;

    public AgentRouter(String deepseekApiKey, String zhipuApiKey, ObjectMapper objectMapper) {
        var ds = ZhipuChatService.forDeepSeek(deepseekApiKey, objectMapper);
        var zp = ZhipuChatService.forZhipu(zhipuApiKey, objectMapper);

        agentMap.put(IntentType.EMERGENCY, new EmergencyAgent(zp));
        agentMap.put(IntentType.GREETING,  new GreetingAgent(zp));
        agentMap.put(IntentType.VISION,    new VisionAgent(ds));
        agentMap.put(IntentType.TECHNICAL, new TechnicalAgent(ds));
        agentMap.put(IntentType.GAME,      new GameAgent(zp));

        this.generalAgent = new GeneralAgent(zp);
    }

    /**
     * 路由到对应 Agent。
     */
    public Agent route(IntentResult result) {
        // 规则1: emergency 最高优先级
        if (result.containsIntent(IntentType.EMERGENCY)) {
            return agentMap.get(IntentType.EMERGENCY);
        }

        // 规则2: 置信度过低 → general 兜底
        if (result.getConfidence() < 0.6) {
            log.info("[ROUTE] 置信度过低({})，降级为 general", result.getConfidence());
            return generalAgent;
        }

        // 规则3: 按主意图路由
        Agent agent = agentMap.get(result.getIntent());
        if (agent != null) return agent;

        return generalAgent;
    }

    /** 用于扩展意图（后续加） */
    public void registerAgent(IntentType intent, Agent agent) {
        agentMap.put(intent, agent);
    }
}
