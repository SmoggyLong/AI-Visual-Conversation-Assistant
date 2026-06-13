package com.aivca.api.llm.agent;

import com.aivca.api.llm.model.AgentContext;
import com.aivca.api.llm.model.ChatResponse;

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

    /** LLM 模型名称 */
    String model();

    /** 温度参数（0=确定，1=随机） */
    double temperature();

    /** 最大输出 token */
    int maxTokens();

    /** 处理用户输入，返回回复 */
    ChatResponse handle(AgentContext context);
}
