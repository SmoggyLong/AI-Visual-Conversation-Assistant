package com.aivca.config;

import com.aivca.handler.ConversationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 配置 —— 注册 /ws/conversation 端点，增大缓冲区以支持音频传输。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    /** WebSocket 消息处理器 */
    private final ConversationWebSocketHandler handler;

    /**
     * 增大 WebSocket 缓冲区以容纳音频 base64 数据（每块约 10KB）。
     * 默认 8KB 会导致 1009 错误。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(65536);    // 64KB
        container.setMaxBinaryMessageBufferSize(65536);  // 64KB
        container.setMaxSessionIdleTimeout(30 * 60 * 1000L); // 30 min
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/conversation")
                .setAllowedOriginPatterns("*");
    }
}
