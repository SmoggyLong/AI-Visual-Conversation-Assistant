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
            "分析画面，严格按照以下格式输出，每行一个字段：\n主体类型：人物/动物/物体/场景\n描述：简洁描述画面内容（衣着、位置、物体等）\n动作：画面中人物的动作（挥手、点头、指向、站立、走动、无等）\n文字：画面中的文字，无则写\"无\"\n\n示例输出：\n主体类型：人物\n描述：戴着眼镜的人，穿蓝色上衣\n动作：挥手\n文字：无";

    public static final int MAX_TOKENS = 400;
}
