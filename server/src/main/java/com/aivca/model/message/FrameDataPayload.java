package com.aivca.model.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FRAME_DATA 消息的载荷 —— 客户端从摄像头截取的视频帧数据。
 *
 * 由端侧 canvas 截取后经帧差检测过滤，仅 changed=true 的帧发送到服务端。
 *
 * 字段说明：
 * - format:        图片编码格式，固定 "jpeg"
 * - width/height:  图片尺寸（像素）
 * - data:          Base64 编码的 JPEG 数据（不含 data:image 前缀）
 * - changed:       帧差检测结果，true=画面有显著变化
 * - imageChecksum: 64x64 缩略图哈希值，用于服务端视觉缓存去重
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FrameDataPayload {

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
}
