package com.aivca.api.vision;

import java.io.Closeable;

/**
 * 视觉分析服务接口。
 *
 * 将视频帧（Base64 JPEG）发送到云端 Vision API，返回画面自然语言描述。
 */
public interface VisionService extends Closeable {

    /**
     * 分析画面内容。
     *
     * @param base64Jpeg Base64 编码的 JPEG 图片数据（不含 data:image 前缀）
     * @return 画面描述文本
     */
    String describe(String base64Jpeg);

    @Override
    void close();
}
