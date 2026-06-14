package com.aivca.api.llm.router;

import com.aivca.constant.IntentType;
import com.aivca.constant.UrgencyLevel;
import com.aivca.api.llm.model.IntentResult;
import com.aivca.util.ResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 意图识别器 —— LangChain4j 版。
 */
@Slf4j
@Component
public class IntentRecognizer {

    private static final String SYSTEM_PROMPT = "你是意图分类器。只输出JSON，不要任何解释或额外文字。";
    private static final double MIN_CONFIDENCE = 0.6;

    private final ChatLanguageModel model;
    private final ObjectMapper objectMapper;

    public IntentRecognizer(@Qualifier("deepseekModel") ChatLanguageModel model,
                             ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
    }

    public IntentResult recognize(String context) {
        if (context == null || context.isBlank()) {
            return new IntentResult(IntentType.CONVERSATION, UrgencyLevel.LOW, "上下文为空", 0.0);
        }

        String userPrompt = String.format("""
                %s

                意图(%s) 紧急度(%s)
                示例: {"intent":"vision","urgency":"low","confidence":0.95,"reasoning":"询问画面"}
                现在输出:
                """,
                context,
                IntentType.promptOptions(),
                UrgencyLevel.promptOptions()
        );

        try {
            var resp = model.generate(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(userPrompt));
            String content = resp.content().text();
            log.info("[INTENT] 识别结果: {}", content);

            var root = objectMapper.readTree(ResponseParser.extractJsonContent(content));
            IntentType intent = IntentType.fromString(root.get("intent").asText(""));
            UrgencyLevel urgency = UrgencyLevel.fromString(root.get("urgency").asText(""));
            String reasoning = root.has("reasoning") ? root.get("reasoning").asText("") : "";
            double confidence = root.has("confidence") ? root.get("confidence").asDouble(0.5) : 0.5;

            if (confidence < MIN_CONFIDENCE) {
                log.info("[INTENT] 置信度过低({})，降级 conversation", confidence);
                return new IntentResult(IntentType.CONVERSATION, UrgencyLevel.NORMAL, reasoning, confidence);
            }

            return new IntentResult(intent, urgency, reasoning, confidence);
        } catch (Exception e) {
            log.warn("[INTENT] 识别失败: {}", e.getMessage());
            return new IntentResult(IntentType.CONVERSATION, UrgencyLevel.NORMAL, "识别失败，降级", 0.0);
        }
    }
}
