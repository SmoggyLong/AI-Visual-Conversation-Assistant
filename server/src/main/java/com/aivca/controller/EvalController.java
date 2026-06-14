package com.aivca.controller;

import com.aivca.rag.eval.Evaluator;
import com.aivca.rag.eval.Evaluator.EvalCase;
import com.aivca.rag.eval.Evaluator.EvalReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 评测 API。
 */
@Slf4j
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final Evaluator evaluator;
    private final ObjectMapper mapper;
    private static final Path CASES_FILE = Path.of("..", "data", "eval", "test_cases.json").toAbsolutePath().normalize();
    private static final Path BASELINE_FILE = Path.of("..", "data", "eval", "baseline.json").toAbsolutePath().normalize();

    private EvalReport lastReport;

    public EvalController(Evaluator evaluator, ObjectMapper mapper) {
        this.evaluator = evaluator;
        this.mapper = mapper;
    }

    @PostMapping("/run")
    public Object run() throws Exception {
        String json = Files.readString(CASES_FILE);
        List<EvalCase> cases = mapper.readValue(json,
                mapper.getTypeFactory().constructCollectionType(List.class, EvalCase.class));
        log.info("[EVAL] 开始评测 | {} 个用例", cases.size());
        lastReport = evaluator.run(cases);

        // 对比基线
        double baselineScore = -1;
        if (Files.exists(BASELINE_FILE)) {
            var baseline = mapper.readTree(Files.readString(BASELINE_FILE));
            baselineScore = baseline.get("overallScore").asDouble(0);
        }

        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("totalCases", lastReport.totalCases());
        result.put("passedCases", lastReport.passedCases());
        result.put("elapsedMs", lastReport.elapsedMs());
        result.put("baselineScore", baselineScore);
        result.put("ragHits", lastReport.ragHits());
        result.put("ragTotal", lastReport.ragTotal());
        result.put("byAgent", buildAgentSummary(lastReport));
        result.put("details", lastReport.byAgent());

        log.info("[EVAL] 评测完成 | 通过 {}/{} | {}ms", lastReport.passedCases(), lastReport.totalCases(), lastReport.elapsedMs());
        return result;
    }

    @PostMapping("/baseline")
    public Map<String, Object> saveBaseline() throws Exception {
        if (lastReport == null) return Map.of("status", "error", "message", "请先运行评测");

        double overallScore = lastReport.byAgent().values().stream().flatMap(List::stream)
                .filter(r -> r.scores() != null)
                .mapToDouble(r -> r.scores().overall())
                .average().orElse(0);

        var baseline = new java.util.LinkedHashMap<>();
        baseline.put("overallScore", Math.round(overallScore * 100) / 100.0);
        baseline.put("evaluatedAt", lastReport.evaluatedAt().toString());
        baseline.put("totalCases", lastReport.totalCases());

        Files.createDirectories(BASELINE_FILE.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(BASELINE_FILE.toFile(), baseline);
        log.info("[EVAL] 基线已保存 | score={}", baseline.get("overallScore"));
        return Map.of("status", "ok", "baseline", baseline);
    }

    @GetMapping("/report")
    public Object report() {
        if (lastReport == null) return Map.of("message", "暂无评测报告");
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("totalCases", lastReport.totalCases());
        result.put("passedCases", lastReport.passedCases());
        result.put("elapsedMs", lastReport.elapsedMs());
        result.put("byAgent", buildAgentSummary(lastReport));
        return result;
    }

    private Object buildAgentSummary(EvalReport report) {
        var summary = new java.util.LinkedHashMap<String, Object>();
        report.byAgent().forEach((agent, results) -> {
            var avgScores = new java.util.LinkedHashMap<String, Double>();
            var scored = results.stream().filter(r -> r.scores() != null).toList();
            if (!scored.isEmpty()) {
                avgScores.put("relevance", avg(scored, s -> s.scores().relevance()));
                avgScores.put("accuracy", avg(scored, s -> s.scores().accuracy()));
                avgScores.put("completeness", avg(scored, s -> s.scores().completeness()));
                avgScores.put("helpfulness", avg(scored, s -> s.scores().helpfulness()));
                avgScores.put("overall", avg(scored, s -> s.scores().overall()));
            }
            var agentInfo = new java.util.LinkedHashMap<String, Object>();
            agentInfo.put("cases", results.size());
            agentInfo.put("passedChecks", results.stream().filter(Evaluator.EvalResult::allChecksPassed).count());
            agentInfo.put("avgScores", avgScores);
            summary.put(agent, agentInfo);
        });
        return summary;
    }

    private double avg(List<Evaluator.EvalResult> list, java.util.function.ToDoubleFunction<Evaluator.EvalResult> fn) {
        return Math.round(list.stream().mapToDouble(fn).average().orElse(0) * 100) / 100.0;
    }
}
