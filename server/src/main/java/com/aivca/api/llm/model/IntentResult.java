package com.aivca.api.llm.model;

import com.aivca.constant.IntentType;
import com.aivca.constant.UrgencyLevel;

/**
 * 意图识别结果。
 */
public class IntentResult {

    /** 识别出的意图类型 */
    private IntentType intent;

    /** 紧急程度 */
    private UrgencyLevel urgency;

    /** 判断依据（一句话说明为什么是这个意图） */
    private String reasoning;

    public IntentResult() {}

    public IntentResult(IntentType intent, UrgencyLevel urgency, String reasoning) {
        this.intent = intent;
        this.urgency = urgency;
        this.reasoning = reasoning;
    }

    public IntentType getIntent() { return intent; }
    public void setIntent(IntentType intent) { this.intent = intent; }

    public UrgencyLevel getUrgency() { return urgency; }
    public void setUrgency(UrgencyLevel urgency) { this.urgency = urgency; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
}
