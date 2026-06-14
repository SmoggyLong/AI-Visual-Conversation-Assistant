package com.aivca.agent.model;

import com.aivca.model.session.ConversationSession;

import java.util.List;

/**
 * Agent 上下文 —— 包含当前输入 + 历史记忆。
 *
 * 参考 EchoMind 的 MemoryContext 设计，将 raw 轮次和压缩摘
要一并传入 Agent，
 * 由 HistoryFormatter 统一拼装为 prompt。
 */
public class AgentContext {

    private String speech;
    private String visionDesc;
    private String visionAction;
    private String urgency;
    private List<String> secondaryIntents;

    /** 最近 N 轮 raw 对话历史（全量注入，由 Agent 的 maxRawTurns 控制截取） */
    private List<ConversationSession.ConversationTurn> conversationHistory;

    /** 累积压缩摘要（参考 EchoMind 的 old_summary + new_summary，压缩时追加） */
    private String conversationSummary;

    /** 成语接龙已用成语列表（由 GameAgent.formatHistory() 读取） */
    private List<String> usedIdioms;

    public AgentContext() {}

    public AgentContext(String speech, String visionDesc, String visionAction,
                        String urgency, List<String> secondaryIntents) {
        this.speech = speech;
        this.visionDesc = visionDesc;
        this.visionAction = visionAction;
        this.urgency = urgency;
        this.secondaryIntents = secondaryIntents;
    }

    public String getSpeech() { return speech; }
    public void setSpeech(String speech) { this.speech = speech; }

    public String getVisionDesc() { return visionDesc; }
    public void setVisionDesc(String visionDesc) { this.visionDesc = visionDesc; }

    public String getVisionAction() { return visionAction; }
    public void setVisionAction(String visionAction) { this.visionAction = visionAction; }

    public String getUrgency() { return urgency; }
    public void setUrgency(String urgency) { this.urgency = urgency; }

    public List<String> getSecondaryIntents() { return secondaryIntents; }
    public void setSecondaryIntents(List<String> secondaryIntents) { this.secondaryIntents = secondaryIntents; }

    public List<ConversationSession.ConversationTurn> getConversationHistory() { return conversationHistory; }
    public void setConversationHistory(List<ConversationSession.ConversationTurn> conversationHistory) {
        this.conversationHistory = conversationHistory;
    }

    public String getConversationSummary() { return conversationSummary; }
    public void setConversationSummary(String conversationSummary) { this.conversationSummary = conversationSummary; }

    public List<String> getUsedIdioms() { return usedIdioms; }
    public void setUsedIdioms(List<String> usedIdioms) { this.usedIdioms = usedIdioms; }
}
