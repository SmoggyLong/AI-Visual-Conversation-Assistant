package com.aivca.agent;

import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.constant.UrgencyLevel;

/**
 * Agent 接口 — 每个意图对应一个 Agent 实现。
 */
public interface Agent {

    /** Agent 唯一名称 */
    String name();

    /** 匹配的意图类别 */
    String intent();

    /** 角色系统提示词 */
    String systemPrompt();

    /** 温度参数（0=确定，1=随机） */
    double temperature();

    /** 最大输出 token */
    int maxTokens();

    /**
     * 根据紧急程度选择模型。
     * 默认规则: CRITICAL/HIGH→glm-4.7, NORMAL→deepseek-chat, LOW→glm-4-flash
     */
    default String selectModel(UrgencyLevel urgency) {
        return switch (urgency) {
            case CRITICAL, HIGH -> "glm-4.7";
            case NORMAL          -> "deepseek-chat";
            case LOW             -> "glm-4-flash";
        };
    }

    /** 处理用户输入，返回回复 */
    ChatResponse handle(AgentContext context);
}
