package com.aivca.rag;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 语音识别纠错器 —— 用 LLM 修正 STT 同音误识别。
 */
@Slf4j
public class SpeechCorrector {

    private static final String PROMPT = """
            纠正语音识别错误。根据对话主题和技术术语列表，修正同音误识别。
            直接输出纠正后的文本，不要任何解释。若无需纠正则输出原文。
            
            对话主题: %s
            常见术语: %s
            识别原文: %s
            """;

    private final ChatLanguageModel model;

    public SpeechCorrector(ChatLanguageModel model) {
        this.model = model;
    }

    /**
     * 纠正 STT 识别文本。
     *
     * @param raw         原始识别文本
     * @param context     对话上下文摘要
     * @param domainTerms 领域术语列表
     * @return 纠正后文本，失败或无需纠正时返回原文
     */
    public String correct(String raw, String context, List<String> domainTerms) {
        if (raw == null || raw.isBlank() || raw.length() < 5) return raw;

        try {
            var resp = model.generate(UserMessage.from(
                    PROMPT.formatted(context, String.join(",", domainTerms), raw)));
            String corrected = resp.content().text().trim();

            // 输出字数和原文差异 > 50% → 不可信，回退原文
            if (corrected.isEmpty() || Math.abs(corrected.length() - raw.length()) > raw.length() * 0.5) {
                return raw;
            }
            if (!corrected.equals(raw)) {
                log.info("[CORRECT] {} → {}", raw, corrected);
            }
            return corrected;
        } catch (Exception e) {
            log.warn("[CORRECT] 纠错失败: {}", e.getMessage());
            return raw;
        }
    }
}
