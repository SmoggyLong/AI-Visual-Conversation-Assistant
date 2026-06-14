package com.aivca.util;

import com.aivca.agent.Agent;
import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.model.session.ConversationSession;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 对话历史格式化与压缩引擎。
 *
 * 参考 EchoMind 的 to_prompt_text() + _compress() 设计：
 * - 摘要生成：会话轮次达到阈值 → LLM 压缩为 1-2 句摘要 → 累积追加
 * - Prompt 拼接：摘要（如有） + raw 最近 N 轮（按 Agent 策略） + 当前 speech/vision/action
 *
 * 每个 Agent 通过 useSummary/maxRawTurns/formatHistory 控制注入策略。
 */
@Slf4j
public final class HistoryFormatter {

    private HistoryFormatter() {}

    /** 摘要生成用：temperature=0，最大输出 150 token */
    private static final double SUMMARY_TEMP = 0.0;
    private static final int SUMMARY_MAX_TOKENS = 150;

    /** 单条历史消息最大字符数（防止超长语音撑大 prompt） */
    private static final int MAX_CHARS_PER_MESSAGE = 200;

    // ==================== Prompt 拼接 ====================

    /**
     * 按 Agent 策略组装 user message（历史 + 当前输入）。
     *
     * 格式参考 EchoMind 的 to_prompt_text()：
     *
     *   [会话摘要]
     *   {summary}
     *
     *   [最近对话]
     *   用户: xxx
     *   小灵: yyy
     *
     *   [用户说] {speech}
     *   [画面] {visionDesc}
     *   [动作] {visionAction}
     *
     * @param agent 当前 Agent（读取策略配置）
     * @param ctx   Agent 上下文（含 history + summary + 当前输入）
     * @return 组装后的 user message 文本
     */
    public static String buildContext(Agent agent, AgentContext ctx) {
        StringBuilder sb = new StringBuilder();

        // 1. 注入压缩摘要（如 Agent 启用且摘要非空）
        if (agent.useSummary() && ctx.getConversationSummary() != null
                && !ctx.getConversationSummary().isEmpty()) {
            sb.append("[会话摘要]\n").append(ctx.getConversationSummary()).append("\n\n");
        }

        // 2. 注入 raw 历史（按 Agent 策略截取最近 N 轮）
        List<ConversationSession.ConversationTurn> history = ctx.getConversationHistory();
        if (history != null && !history.isEmpty()) {
            // 自定义格式（如 GameAgent 的成语链）
            String custom = agent.formatHistory(history);
            if (custom != null && !custom.isEmpty()) {
                sb.append(custom).append("\n\n");
            } else {
                int maxTurns = agent.maxRawTurns();
                if (maxTurns > 0) {
                    appendRawHistory(sb, history, maxTurns);
                }
            }
        }

        // 3. 当前输入（沿用各 Agent 原有的拼装字段）
        if (ctx.getSpeech() != null && !ctx.getSpeech().isEmpty()) {
            sb.append("[用户说] ").append(ctx.getSpeech()).append("\n");
        }
        if (ctx.getVisionDesc() != null && !ctx.getVisionDesc().isEmpty()) {
            sb.append("[画面] ").append(ctx.getVisionDesc()).append("\n");
        }
        if (ctx.getVisionAction() != null && !ctx.getVisionAction().isEmpty()) {
            sb.append("[动作] ").append(ctx.getVisionAction()).append("\n");
        }
        if (ctx.getSecondaryIntents() != null && !ctx.getSecondaryIntents().isEmpty()) {
            sb.append("[次要意图] ").append(String.join(",", ctx.getSecondaryIntents())).append("\n");
        }

        if (sb.isEmpty()) return "";
        return sb.toString().trim();
    }

    /** 追加 raw 最近 N 轮对话（参考 EchoMind 的 [最近对话] 格式） */
    private static void appendRawHistory(StringBuilder sb,
                                          List<ConversationSession.ConversationTurn> history,
                                          int maxTurns) {
        if (history.isEmpty()) return;
        int total = history.size();
        int start = Math.max(0, total - maxTurns);

        sb.append("[最近对话]\n");
        for (int i = start; i < total; i++) {
            ConversationSession.ConversationTurn turn = history.get(i);
            if (turn.getUserText() != null && !turn.getUserText().isEmpty()) {
                sb.append("用户: ").append(truncate(turn.getUserText(), MAX_CHARS_PER_MESSAGE)).append("\n");
            }
            if (turn.getAssistantText() != null && !turn.getAssistantText().isEmpty()) {
                sb.append("小灵: ").append(truncate(turn.getAssistantText(), MAX_CHARS_PER_MESSAGE)).append("\n");
            }
        }
        sb.append("\n");
    }

    // ==================== 压缩摘要生成 ====================

    /**
     * 对历史轮次生成压缩摘要（LLM 调用）。
     *
     * 参考 EchoMind 的 _compress()：
     * - 将所有轮次拼接为文本
     * - 调 LLM 用 1-2 句话总结关键信息
     * - 失败时返回 placeholder
     *
     * @param turns 待压缩的轮次列表
     * @param llm   ChatLanguageModel 实例
     * @return 压缩摘要文本
     */
    public static String summarize(List<ConversationSession.ConversationTurn> turns,
                                    ChatLanguageModel llm) {
        if (turns == null || turns.isEmpty()) return "";

        StringBuilder conversationText = new StringBuilder();
        for (ConversationSession.ConversationTurn turn : turns) {
            if (turn.getUserText() != null && !turn.getUserText().isEmpty()) {
                conversationText.append("用户: ")
                        .append(truncate(turn.getUserText(), MAX_CHARS_PER_MESSAGE)).append("\n");
            }
            if (turn.getAssistantText() != null && !turn.getAssistantText().isEmpty()) {
                conversationText.append("小灵: ")
                        .append(truncate(turn.getAssistantText(), MAX_CHARS_PER_MESSAGE)).append("\n");
            }
        }

        if (conversationText.isEmpty()) return "";

        String summaryPrompt = """
                用1-2句话总结以下对话的关键信息，只输出总结不输出其他内容：
                %s""".formatted(conversationText.toString().trim());

        try {
            var resp = llm.generate(
                    SystemMessage.from("你是一个对话摘要助手，简洁准确。"),
                    UserMessage.from(summaryPrompt));
            String text = resp.content().text();
            if (text != null && !text.isEmpty() && !text.contains("我暂时无法回复")) {
                log.info("[SUMMARY] 摘要生成成功 | len={}", text.length());
                return text;
            }
        } catch (Exception e) {
            log.warn("[SUMMARY] 摘要生成失败 | {}", e.getMessage());
        }

        String fallback = "之前有" + turns.size() + "轮对话";
        log.info("[SUMMARY] 使用兜底摘要: {}", fallback);
        return fallback;
    }

    // ==================== 工具方法 ====================

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
