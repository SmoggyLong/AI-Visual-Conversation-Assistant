package com.aivca.util;

/**
 * 视觉描述结构化解析结果。
 *
 * 从 GLM-4V 返回的固定格式文本中解析出各字段。
 * 输入样例:
 *   "主体类型：人物\n描述：戴眼镜穿白衣服的男子\n文字：STREET\n动作：挥手"
 */
public class VisionStructurer {

    /** 主体类型：人物 / 动物 / 物体 / 场景 */
    private String subjectType;

    /** 画面自然语言描述 */
    private String description;

    /** 画面中提取的文字（可为空） */
    private String textInImage;

    /** 动作描述（多帧模式，可为空） */
    private String action;

    private VisionStructurer() {}

    /**
     * 从 GLM-4V 返回的结构化文本中解析。
     *
     * @param raw 原始 Vision 输出（固定格式）
     * @return 解析结果；输入为空时返回 null
     */
    public static VisionStructurer parse(String raw) {
        if (raw == null || raw.isBlank()) return null;

        VisionStructurer vs = new VisionStructurer();
        String[] lines = raw.split("\\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            int idx = line.indexOf("：");
            if (idx < 0) idx = line.indexOf(':');
            if (idx < 0) continue;

            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();

            if (value.isEmpty() || "(无)".equals(value) || "无".equals(value)) continue;

            switch (key) {
                case "主体类型" -> vs.subjectType = value;
                case "描述" -> vs.description = value;
                case "文字" -> vs.textInImage = value;
                case "动作" -> vs.action = value;
            }
        }

        // 至少要有主体类型或描述之一
        if (vs.subjectType == null && vs.description == null) return null;

        return vs;
    }

    // getters
    public String getSubjectType() { return subjectType; }
    public String getDescription() { return description; }
    public String getTextInImage() { return textInImage; }
    public String getAction() { return action; }
}
