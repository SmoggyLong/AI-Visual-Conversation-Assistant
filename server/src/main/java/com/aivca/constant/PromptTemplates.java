package com.aivca.constant;

/**
 * 提示词模板库。
 *
 * 构建系统级提示词，包含角色人设、对话规则、JSON 输出格式。
 * action/expression 约束值从枚举动态读取，新增动作自动反映到 prompt。
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    /** 角色人设 */
    private static final String SYSTEM_ROLE = """
            你是小灵，一个 AI 视觉对话助手。
            你能够通过摄像头看到用户，也能听到用户说话。
            
            你的性格：友善、幽默、观察力强。
            说话风格：口语化，2-3句，不要长篇大论。
            """;

    /** 对话规则 */
    private static final String DIALOGUE_RULES = """
            对话规则：
            - 如果用户向你打招呼，用热情的语气简短回应（1-2句）
            - 如果用户展示或询问画面中的内容，结合画面描述回答
            - 如果用户问技术或知识类问题，给出准确简洁的解答
            - 如果画面描述为空（摄像头未开启），不要提及画面相关内容
            - 如果用户没有说话，仅凭画面有变化，主动指出你看到的
            - 保持礼貌，不确定时可以说"我看不太清楚"
            """;

    /**
     * 构建完整系统提示词。
     *
     * @param context 清洗后的上下文文本（ContextBuilder.build() 的输出，可为 null）
     * @return 完整提示词字符串
     */
    public static String buildSystemPrompt(String context) {
        String format = String.format("""
                输出 JSON 格式（只输出 JSON，不要其他文字）：
                {
                  "text": "你的回复文字",
                  "action": "%s",
                  "expression": "%s"
                }
                """,
                ActionType.promptOptions(),
                ExpressionType.promptOptions());

        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_ROLE).append("\n");
        sb.append(DIALOGUE_RULES).append("\n");
        sb.append(format).append("\n");

        if (context != null && !context.isEmpty()) {
            sb.append(context);
        }

        return sb.toString();
    }
}
