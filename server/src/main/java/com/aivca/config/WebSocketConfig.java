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
     * 增大 WebSocket 缓冲区以容纳视频帧+音频同时发送时的合批消息。
     * 每帧 JPEG base64 ~15KB，语音+视频同时流式传输时可能合批超 256KB。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(2_097_152);    // 2MB
        container.setMaxBinaryMessageBufferSize(2_097_152);  // 2MB
        container.setMaxSessionIdleTimeout(30 * 60 * 1000L); // 30 min
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/conversation")
                .setAllowedOriginPatterns("*");
    }
}
