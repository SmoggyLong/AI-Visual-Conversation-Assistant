package com.aivca.rag;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM 文本清洗器 —— 用 glm-4-flash 清洗 Markdown 文档为纯文本。
 *
 * 清洗在入库时离线执行，不要求实时性。失败时自动降级到 TextCleaner（正则）。
 */
@Slf4j
public class LlmTextCleaner {

    private static final String SYSTEM_PROMPT = """
            你是文本清洗助手。将以下文档清洗为纯文本，用于知识库检索。
            
            规则:
            1. 去掉 Markdown 标记（# * - > 等格式符号），保留文字
            2. 去掉 HTML 标签和 URL
            3. 表格 → 转为自然语言描述（"未发货→直接退款，已签收→需退货"）
            4. 代码块 → 提取注释和关键逻辑，用中文简短描述（1-2句）
            5. 去掉分隔线、版权声明、纯标点行等噪声
            6. 保留所有步骤编号、技术术语、数字不变
            7. 不要添加原文没有的信息
            8. 直接输出清洗后文本，不要加任何前缀或解释性文字
            
            输入:
            """;

    private final ChatLanguageModel model;

    public LlmTextCleaner(ChatLanguageModel model) {
        this.model = model;
    }

    /**
     * 用 LLM 清洗文本。
     *
     * @param raw 原始文档内容（含 Markdown 标记）
     * @return 清洗后的纯文本；失败时返回 null（由调用方降级 TextCleaner）
     */
    public String clean(String raw) {
        if (raw == null || raw.isBlank()) return "";

        log.info("[LLM-CLEAN] 开始清洗 | inputLen={}", raw.length());
        long start = System.currentTimeMillis();

        try {
            var resp = model.generate(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(raw));
            String result = resp.content().text();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[LLM-CLEAN] 清洗完成 | inputLen={} → outputLen={} | {}ms",
                    raw.length(), result != null ? result.length() : 0, elapsed);
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[LLM-CLEAN] 清洗失败, 降级正则 | {}ms | {}", elapsed, e.getMessage());
            return null;
        }
    }
}
