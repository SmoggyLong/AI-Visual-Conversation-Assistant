package com.aivca.agent.model;

import java.util.List;

/**
 * Agent 上下文。
 */
public class AgentContext {

    private String speech;
    private String visionDesc;
    private String visionAction;
    private String urgency;
    private List<String> secondaryIntents;

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
}
