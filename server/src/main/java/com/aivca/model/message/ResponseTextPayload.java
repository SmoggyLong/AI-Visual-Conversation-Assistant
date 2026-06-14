package com.aivca.model.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RESPONSE_TEXT 消息的载荷 —— AI 生成的文字回复。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseTextPayload {

    /** 消息唯一标识，用于关联 RESPONSE_AUDIO */
    private String messageId;

    /** AI 回复的文本内容 */
    private String content;

    /** 角色标识：固定 "assistant" */
    private String role;

    /** 当前对话轮次（从 0 开始） */
    private int conversationRound;

    /** 处理本消息的 Agent 名称 */
    private String agent;
}
