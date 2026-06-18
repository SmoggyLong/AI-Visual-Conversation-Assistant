package com.aivca.util;

/**
 * 上下文组装器。
 *
 * 将清洗后的语音文本和结构化视觉描述组合为统一格式的上下文文本块，
 * 直接注入 LLM 的 prompt。
 */
public final class ContextBuilder {

    private ContextBuilder() {}

    /**
     * 组装上下文。
     *
     * @param sanitizedSpeech 清洗后的用户语音（可为 null）
     * @param vision          结构化视觉描述（可为 null）
     * @return 格式化文本块；两者都为空时返回 null
     */
    public static String build(String sanitizedSpeech, VisionStructurer vision) {
        StringBuilder sb = new StringBuilder();

        if (sanitizedSpeech != null && !sanitizedSpeech.isEmpty()) {
            sb.append("[用户说] ").append(sanitizedSpeech).append("\n\n");
        }

        if (vision != null) {
            sb.append("[当前画面]\n");
            if (vision.getSubjectType() != null) {
                sb.append("主体类型: ").append(vision.getSubjectType()).append("\n");
            }
            if (vision.getDescription() != null) {
                sb.append("描述: ").append(vision.getDescription()).append("\n");
            }
            if (vision.getTextInImage() != null && !vision.getTextInImage().isEmpty()) {
                sb.append("画面文字: ").append(vision.getTextInImage()).append("\n");
            }
            if (vision.getAction() != null && !vision.getAction().isEmpty()) {
                sb.append("动作: ").append(vision.getAction()).append("\n");
            }
        }

        if (sb.isEmpty()) return null;
        return sb.toString().trim();
    }
}
