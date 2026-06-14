package com.aivca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 视觉对话助手 —— 服务端启动入口。
 */
@SpringBootApplication
@EnableScheduling
public class AiVisualConversationApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiVisualConversationApplication.class, args);
    }
}
