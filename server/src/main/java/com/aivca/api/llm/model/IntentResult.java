package com.aivca.api.llm.model;

import com.aivca.constant.IntentType;
import com.aivca.constant.UrgencyLevel;
import java.util.List;
import java.util.ArrayList;

/**
 * 意图识别结果。
 */
public class IntentResult {

    /** 主意图 */
    private IntentType intent;

    /** 次要意图（供 Agent 参考） */
    private List<IntentType> secondaryIntents = new ArrayList<>();

    /** 紧急程度 */
    private UrgencyLevel urgency;

    /** 判断依据 */
    private String reasoning;

    /** 置信度（0.0 ~ 1.0） */
    private double confidence;

    public IntentResult() {}

    public IntentResult(IntentType intent, UrgencyLevel urgency, String reasoning, double confidence) {
        this.intent = intent;
        this.urgency = urgency;
        this.reasoning = reasoning;
        this.confidence = confidence;
    }

    public boolean containsIntent(IntentType type) {
        return intent == type || secondaryIntents.contains(type);
    }

    public IntentType getIntent() { return intent; }
    public void setIntent(IntentType intent) { this.intent = intent; }

    public List<IntentType> getSecondaryIntents() { return secondaryIntents; }
    public void setSecondaryIntents(List<IntentType> secondary) { this.secondaryIntents = secondary; }

    public UrgencyLevel getUrgency() { return urgency; }
    public void setUrgency(UrgencyLevel urgency) { this.urgency = urgency; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
}
