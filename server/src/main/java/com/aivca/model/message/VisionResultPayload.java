package com.aivca.model.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * VISION_RESULT 消息的载荷 —— Vision API 对视频帧的分析结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VisionResultPayload {

    /** 对应帧的校验和（imageChecksum），用于客户端关联显示 */
    private String frameChecksum;

    /** 画面的自然语言描述 */
    private String description;

    /** 检测到的物体名称列表 */
    private List<String> detectedObjects;

    /** 截帧时间戳（毫秒） */
    private long timestamp;
}
