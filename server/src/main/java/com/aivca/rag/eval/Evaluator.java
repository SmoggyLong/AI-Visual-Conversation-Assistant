package com.aivca.rag.eval;

import com.aivca.agent.Agent;
import com.aivca.agent.AgentRouter;
import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.api.llm.model.IntentResult;
import com.aivca.handler.Orchestrator;
import com.aivca.model.session.ConversationSession;
import com.aivca.rag.KnowledgeBase;
import com.aivca.rag.SearchHit;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测器 —— 遍历测试用例，调 Agent，LLM 打分。
 */
@Slf4j
@Component
public class Evaluator {

    private final AgentRouter agentRouter;
    private final Orchestrator orchestrator;
    private final KnowledgeBase knowledgeBase;
    private final LLMJudge judge;
    private final ObjectMapper mapper;

    public Evaluator(AgentRouter agentRouter, Orchestrator orchestrator,
                      KnowledgeBase knowledgeBase, LLMJudge judge,
                      ObjectMapper mapper) {
        this.agentRouter = agentRouter;
        this.orchestrator = orchestrator;
        this.knowledgeBase = knowledgeBase;
        this.judge = judge;
        this.mapper = mapper;
    }

    /** 运行完整评测 */
    public EvalReport run(List<EvalCase> cases) {
        long start = System.currentTimeMillis();
        var results = new ArrayList<EvalResult>();

        for (EvalCase tc : cases) {
            try {
                results.add(evalOne(tc));
            } catch (Exception e) {
                log.warn("[EVAL] 案例 {} 评测失败: {}", tc.id(), e.getMessage());
                results.add(new EvalResult(tc.id(), tc.agent(), tc.query(),
                        "FAILED", null, null, null, null, e.getMessage()));
            }
        }

        long elapsed = System.currentTimeMillis() - start;

        // 汇总
        long total = results.size();
        long passed = results.stream().filter(r -> r.scores() != null).count();
        var byAgent = groupByAgent(results);

        return new EvalReport(total, passed, byAgent, elapsed, Instant.now());
    }

    private EvalResult evalOne(EvalCase tc) {
        // 1. 意图识别
        String intent = "unknown";
        if (tc.expectedIntent() != null) {
            var session = new ConversationSession();
            session.addTurn(tc.query(), "", null);
            session.setCachedVisionDescription(tc.context());
            IntentResult ir = orchestrator.recognizeIntent(session);
            intent = ir.getIntent().toValue();
        }

        // 2. Agent 回答
        String response = null;
        String knowledgeCtx = null;
        String retrievedSource = null;
        if (tc.agent() != null && !tc.agent().isEmpty()) {
            var intentResult = new com.aivca.api.llm.model.IntentResult(
                    com.aivca.constant.IntentType.fromString(tc.agent()),
                    com.aivca.constant.UrgencyLevel.NORMAL, "", 0.8);
            Agent agent = agentRouter.route(intentResult);

            var ctx = new AgentContext();
            ctx.setSpeech(tc.query());
            ctx.setVisionDesc(tc.context());
            ctx.setUrgency("normal");
            ctx.setConversationHistory(new ArrayList<>());

            // RAG 检索 (knowledge agent 时执行)
            if (tc.agent() != null && tc.agent().contains("knowledge") && knowledgeBase.isReady()) {
                var hits = knowledgeBase.searchHybrid(List.of(tc.query()), 5);
                if (!hits.isEmpty()) {
                    var sb = new StringBuilder();
                    var srcLabels = new ArrayList<String>();
                    for (SearchHit h : hits) {
                        sb.append(h.sourceLabel()).append(": ").append(h.content()).append("\n");
                        srcLabels.add(h.source());
                    }
                    knowledgeCtx = sb.toString();
                    retrievedSource = String.join(", ", srcLabels.stream().distinct().toList());
                }
            }

            ChatResponse resp = agent.handle(ctx);
            response = resp.getText();
        }

        // 3. LLM 裁判打分
        LLMJudge.JudgeScores scores = null;
        if (response != null) {
            scores = judge.judge(tc.query(), response, knowledgeCtx);
        }

        // 4. 断言
        var checks = new LinkedHashMap<String, Boolean>();
        if (tc.expectedIntent() != null) {
            checks.put("intent=" + tc.expectedIntent(), tc.expectedIntent().equals(intent));
        }
        if (tc.expectedKeywords() != null && response != null) {
            for (String kw : tc.expectedKeywords()) {
                checks.put("contains:" + kw, response.contains(kw));
            }
        }
        if (tc.expectedSource() != null) {
            checks.put("source:" + tc.expectedSource(), knowledgeCtx != null && knowledgeCtx.contains(tc.expectedSource()));
        }

        return new EvalResult(tc.id(), tc.agent(), tc.query(), intent, response,
                scores, checks, retrievedSource, null);
    }

    private Map<String, List<EvalResult>> groupByAgent(List<EvalResult> results) {
        var map = new LinkedHashMap<String, List<EvalResult>>();
        for (var r : results) {
            map.computeIfAbsent(r.agent() != null ? r.agent() : "intent", k -> new ArrayList<>()).add(r);
        }
        return map;
    }

    // ==================== DTOs ====================

    /** 评测总报告 */
    public record EvalReport(
            long totalCases, long passedCases,
            Map<String, List<EvalResult>> byAgent,
            long elapsedMs, Instant evaluatedAt
    ) {
        public double passRate() { return totalCases > 0 ? (double) passedCases / totalCases : 0; }
    }

    /** 单个案例评测结果 */
    public record EvalResult(
            String caseId, String agent, String query,
            String intent, String response,
            LLMJudge.JudgeScores scores, Map<String, Boolean> checks,
            String retrievedSource, String error
    ) {
        public boolean allChecksPassed() {
            return checks != null && checks.values().stream().allMatch(Boolean::booleanValue);
        }
    }

    /** 评测用例 */
    public record EvalCase(
            String id, String agent, String query,
            String expectedIntent, String context,
            List<String> expectedKeywords, String expectedSource
    ) {}
}
