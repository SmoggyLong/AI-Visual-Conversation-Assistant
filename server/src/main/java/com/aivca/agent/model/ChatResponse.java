package com.aivca.agent.model;

/**
 * Agent 回复。
 */
public class ChatResponse {

    private String text;
    private String action;
    private String expression;
    /** GameAgent 使用的成语，供服务端追踪已用成语列表 */
    private String idiom;

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

    public String getIdiom() { return idiom; }
    public void setIdiom(String idiom) { this.idiom = idiom; }
}
