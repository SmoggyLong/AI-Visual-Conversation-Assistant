package com.aivca.constant;

/**
 * 视觉分析相关常量。
 */
public final class VisionConstants {

    private VisionConstants() {}

    /** 智谱 GLM-4V API 端点 */
    public static final String ZHIPU_VISION_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    /** Vision 模型 */
    public static final String VISION_MODEL = "glm-4.6v";

    public static final String VISION_PROMPT =
            "分析画面，输出格式：\n主体类型：人物/动物/物体/场景\n描述：[对应属性]\n文字：[提取画面中文字，无则省略]";

    /** Vision 结果最大 token 数（需覆盖 reasoning_tokens + 回答） */
    public static final int MAX_TOKENS = 300;
}
