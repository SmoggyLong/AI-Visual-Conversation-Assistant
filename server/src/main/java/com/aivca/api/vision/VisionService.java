package com.aivca.api.vision;

import java.io.Closeable;
import java.util.List;

/**
 * 视觉分析服务接口。
 */
public interface VisionService extends Closeable {

    /**
     * 分析单帧画面内容。
     *
     * @param base64Jpeg Base64 JPEG（不含 data:image 前缀）
     * @return 画面描述文本
     */
    String describe(String base64Jpeg);

    /**
     * 分析连续帧（动作/行为判断）。
     *
     * @param base64Frames 连续帧的 Base64 JPEG 列表（按时间顺序，最近的在最后）
     * @return 画面描述 + 动作判断
     */
    default String describeBatch(List<String> base64Frames) {
        // 默认降级为单帧分析（取最后一帧）
        return describe(base64Frames.isEmpty() ? "" : base64Frames.get(base64Frames.size() - 1));
    }

    @Override
    void close();
}
