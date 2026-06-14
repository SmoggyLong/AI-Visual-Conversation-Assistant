package com.aivca.agent;

import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.model.session.ConversationSession;
import com.aivca.util.HistoryFormatter;
import com.aivca.util.ResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

public class GameAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你是小灵，和用户玩成语接龙。

            【阶段判断】
            - 如果[已用成语]为空 → 用户还没有开始接龙。先确认要玩，
              然后简短解释规则，等用户出第一个成语。
            - 如果[已用成语]非空 → 接龙进行中，正常按规则回应。

            【规则（必须严格遵守）】
            1. 接龙规则：你出的成语首字读音 = 用户成语尾字读音（同音即可，
               不要求字形相同）
            2. 【重要】你必须自己选一个成语接上，绝不能列出选项让用户选
            3. 必须是真实存在的四字汉语成语（不能自己编造）
            4. 不能重复已用成语（见[已用成语]列表）
            5. 你接不上 → 说"我接不上了，你赢了！"
            6. 用户说非成语（闲聊、问规则、说犯规） → 正常回应，不要出成语

            【用户求助时（"不会接"、"怎么办"、"认输"等）】
            - 先安抚情绪（"没关系"），不要直接放弃
            - 给出具体提示：上一个成语的尾字是什么、读音是什么、以此音开头的常用成语示例
            - 鼓励用户："你再想想看"、"不行的话百度一下也行嘿嘿"
            - 用户反复表示放弃 → 说"那这次算你接不上，我赢啦！再来一局？重置计数。"

            输出JSON:
            - 正常对话: {"text":"回复","action":"idle","expression":"happy|thinking|surprised"}
            - 出了成语: {"text":"回复","action":"idle","expression":"happy|thinking|surprised","idiom":"你出的成语"}
            """;

    private final ChatLanguageModel chatModel;
    private final ChatLanguageModel summarizeModel;
    private final ObjectMapper objectMapper;

    public GameAgent(ChatLanguageModel chatModel, ChatLanguageModel summarizeModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.summarizeModel = summarizeModel;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "game"; }
    @Override public String intent() { return "game"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public double temperature() { return 0.3; }
    @Override public int maxTokens() { return 200; }
    @Override public ChatLanguageModel getChatModel() { return chatModel; }
    @Override public ChatLanguageModel getSummarizeModel() { return summarizeModel; }

    @Override public boolean useSummary() { return false; }
    @Override public int maxRawTurns() { return 0; }
    @Override public String fallbackText() { return "哎呀我卡住了，再来一次？"; }

    @Override
    public String formatHistory(List<ConversationSession.ConversationTurn> history) {
        return null;
    }

    @Override
    public ChatResponse handle(AgentContext ctx) {
        List<String> idioms = ctx.getUsedIdioms();
        if (idioms != null && !idioms.isEmpty()) {
            StringBuilder idiomBlock = new StringBuilder("[已用成语]\n");
            idiomBlock.append(String.join(" → ", idioms));
            ctx.setSpeech((ctx.getSpeech() != null ? ctx.getSpeech() + "\n" : "")
                    + idiomBlock);
        }
        String userMsg = HistoryFormatter.buildContext(this, ctx);
        try {
            var resp = chatModel.generate(
                    SystemMessage.from(systemPrompt()),
                    UserMessage.from(userMsg));
            return ResponseParser.parse(resp.content().text(), objectMapper);
        } catch (Exception e) {
            return new ChatResponse(fallbackText(), "idle", "neutral");
        }
    }
}
