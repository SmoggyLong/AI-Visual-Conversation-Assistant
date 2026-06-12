package com.aivca.constant;

/**
 * 视觉分析相关常量。
 */
public final class VisionConstants {

    private VisionConstants() {}

    /** OpenAI Vision API 端点（可通过代理访问） */
    public static final String OPENAI_VISION_URL = "https://api.openai.com/v1/chat/completions";

    /** Vision 模型（性价比最优） */
    public static final String VISION_MODEL = "gpt-4o-mini";

    /** 图片分析提示词 */
    public static final String VISION_PROMPT = "用中文一句话描述这个画面中有什么，不要超过50个字。";

    /** 图片 detail 模式：low = 固定 85 tokens（成本最优） */
    public static final String VISION_DETAIL = "low";

    /** Vision 结果最大 token 数 */
    public static final int MAX_TOKENS = 100;
}
