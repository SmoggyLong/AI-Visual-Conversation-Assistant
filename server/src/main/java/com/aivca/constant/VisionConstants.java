package com.aivca.constant;

/**
 * 视觉分析相关常量。
 */
public final class VisionConstants {

    private VisionConstants() {}

    /** 智谱 GLM-4V API 端点 */
    public static final String ZHIPU_VISION_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    /** Vision 模型 */
    public static final String VISION_MODEL = "glm-4v";

    /** 图片分析提示词 */
    public static final String VISION_PROMPT = "用中文一句话描述这个画面中有什么，不要超过50个字。";

    /** Vision 结果最大 token 数 */
    public static final int MAX_TOKENS = 100;
}
