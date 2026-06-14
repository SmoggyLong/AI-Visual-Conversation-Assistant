package com.aivca.model.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * STATUS_UPDATE 消息的载荷 —— 服务端推送当前处理状态给客户端。
 *
 * 客户端根据 state 值显示对应的状态指示器（如"正在听…""思考中…"）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatusUpdatePayload {

    /** 当前处理状态 */
    private State state;

    /** 状态的附加描述文本 */
    private String detail;

    /**
     * 服务端处理状态枚举。
     */
    public enum State {
        /** 待机：等待用户交互 */
        idle,
        /** 正在听取：正在接收/处理音频 */
        listening,
        /** 思考中：LLM 正在推理 */
        thinking,
        /** 正在说：正在播放 TTS 语音 */
        speaking,
        /** 正在看：正在分析摄像头画面 */
        watching,
        /** 异常：处理出错 */
        error
    }
}
