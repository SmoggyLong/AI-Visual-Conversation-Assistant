package com.aivca.api.llm.model;

import java.util.List;

/**
 * Agent 上下文 — 传递给每个 Agent 的输入。
 */
public class AgentContext {

    /** 清洗后的用户语音 */
    private String speech;

    /** 画面自然语言描述 */
    private String visionDesc;

    /** 画面中检测到的动作 */
    private String visionAction;

    /** 次要意图列表 */
    private List<String> secondaryIntents;

    public AgentContext() {}

    public AgentContext(String speech, String visionDesc, String visionAction, List<String> secondaryIntents) {
        this.speech = speech;
        this.visionDesc = visionDesc;
        this.visionAction = visionAction;
        this.secondaryIntents = secondaryIntents;
    }

    public String getSpeech() { return speech; }
    public void setSpeech(String speech) { this.speech = speech; }

    public String getVisionDesc() { return visionDesc; }
    public void setVisionDesc(String visionDesc) { this.visionDesc = visionDesc; }

    public String getVisionAction() { return visionAction; }
    public void setVisionAction(String visionAction) { this.visionAction = visionAction; }

    public List<String> getSecondaryIntents() { return secondaryIntents; }
    public void setSecondaryIntents(List<String> secondaryIntents) { this.secondaryIntents = secondaryIntents; }
}
