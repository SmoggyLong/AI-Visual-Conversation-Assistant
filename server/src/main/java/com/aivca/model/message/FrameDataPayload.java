package com.aivca.model.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * FRAME_DATA 消息的载荷 —— 客户端从摄像头截取的视频帧数据（支持单帧或多帧批次）。
 *
 * 单帧模式（向后兼容）：data / format / width / height 字段
 * 批次模式（连续帧分析）：frames 字段，每项含 data / format / checksum / offsetMs
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FrameDataPayload {

    // ===== 单帧字段（向后兼容） =====

    /** 图片编码格式：固定 "jpeg" */
    private String format;

    /** 图片宽度（像素） */
    private int width;

    /** 图片高度（像素） */
    private int height;

    /** Base64 编码的 JPEG 图片数据 */
    private String data;

    /** 帧差检测结果：与上一帧相比是否有显著变化 */
    private boolean changed;

    /** 缩略图哈希值（64x64），用于服务端缓存去重 */
    private String imageChecksum;

    // ===== 多帧批次字段 =====

    /** 连续帧列表（5fps 采集，发送最近 1 秒的帧） */
    private List<FrameItem> frames;

    /**
     * 批次中单帧信息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FrameItem {
        /** Base64 JPEG */
        private String data;
        /** 图片格式 */
        private String format;
        /** 校验和（用于服务端去重） */
        private String checksum;
        /** 相对当前时刻的时间偏移（毫秒），-1000=1秒前 */
        private int offsetMs;
    }
}
