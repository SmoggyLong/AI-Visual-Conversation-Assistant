package com.aivca.api.llm.model;

/**
 * Agent 回复。
 */
public class ChatResponse {

    /** 回复文字 */
    private String text;

    /** 动作：idle / wave / point / nod */
    private String action;

    /** 表情：happy / curious / neutral / surprised */
    private String expression;

    public ChatResponse() {}

    public ChatResponse(String text, String action, String expression) {
        this.text = text;
        this.action = action;
        this.expression = expression;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getExpression() { return expression; }
    public void setExpression(String expression) { this.expression = expression; }
}
