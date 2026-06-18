package com.aivca.controller;

import com.aivca.service.SessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查控制器 —— 提供 /health 接口用于监控和运维。
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    /** 会话管理器，用于查询当前活跃会话数 */
    private final SessionManager sessionManager;

    /**
     * 健康检查接口。
     *
     * @return { status, activeSessions }
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "activeSessions", sessionManager.getActiveCount()
        ));
    }
}
