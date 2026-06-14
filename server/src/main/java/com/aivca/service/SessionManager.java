package com.aivca.service;

import com.aivca.model.session.ConversationSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器 —— 内存 + Redis 双写（参考 EchoMind 的 wm:{user}:{conv} 设计）。
 */
@Slf4j
@Service
public class SessionManager {

    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> wsToSession = new ConcurrentHashMap<>();
    private final RedisTemplate<String, Object> redisTemplate;

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    public SessionManager(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public ConversationSession create() {
        ConversationSession session = new ConversationSession();
        session.setRedis(redisTemplate);
        sessions.put(session.getSessionId(), session);
        log.info("[SESSION] 会话已创建 | sessionId={}", session.getSessionId());
        return session;
    }

    /**
     * 按 sessionId 查找会话，并更新活跃时间。
     *
     * @param sessionId 业务会话 ID
     * @return 会话对象，不存在时返回 null
     */
    public ConversationSession get(String sessionId) {
        ConversationSession session = sessions.get(sessionId);
        if (session != null) {
            session.touch();
        }
        return session;
    }

    /**
     * 绑定 WebSocket 连接 ID 到业务会话 ID。
     *
     * @param wsSessionId  WebSocket 会话 ID
     * @param appSessionId 业务会话 ID
     */
    public void bindWsSession(String wsSessionId, String appSessionId) {
        wsToSession.put(wsSessionId, appSessionId);
    }

    /**
     * 通过 WebSocket 连接 ID 查找对应的业务会话。
     *
     * @param wsSessionId WebSocket 会话 ID
     * @return 业务会话对象，不存在时返回 null
     */
    public ConversationSession getByWsId(String wsSessionId) {
        String appSessionId = wsToSession.get(wsSessionId);
        if (appSessionId == null) return null;
        return get(appSessionId);
    }

    /**
     * 移除 WebSocket 连接及其关联的业务会话。
     *
     * @param wsSessionId WebSocket 会话 ID
     */
    public void remove(String wsSessionId) {
        String appSessionId = wsToSession.remove(wsSessionId);
        if (appSessionId != null) {
            ConversationSession removed = sessions.remove(appSessionId);
            if (removed != null) {
                log.info("[SESSION] 会话已销毁 | sessionId={}", appSessionId);
            }
        }
    }

    /**
     * 清理所有超过空闲超时的会话。
     *
     * @return 本次清理的会话数量
     */
    public int cleanIdleSessions() {
        int removed = 0;
        Instant cutoff = Instant.now().minus(IDLE_TIMEOUT);
        for (Map.Entry<String, ConversationSession> entry : sessions.entrySet()) {
            if (entry.getValue().getLastActiveAt().isBefore(cutoff)) {
                sessions.remove(entry.getKey());
                wsToSession.values().remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[SESSION] 空闲会话清理完成 | 清理数={}", removed);
        }
        return removed;
    }

    /**
     * 获取当前活跃会话数量。
     *
     * @return 活跃会话数
     */
    public int getActiveCount() {
        return sessions.size();
    }
}
