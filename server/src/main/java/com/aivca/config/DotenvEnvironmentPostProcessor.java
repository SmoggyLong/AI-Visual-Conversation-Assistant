package com.aivca.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * .env 文件加载器 —— 在 Spring 上下文初始化前将 .env 中的配置注入环境变量。
 *
 * 通过 spring.factories 或 spring.boot.ApplicationEnvironmentPostProcessor 自动注册。
 * 支持的 .env 格式：KEY=VALUE，忽略空行和 # 注释行。
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = Paths.get(".env");
        if (!Files.exists(envFile)) return;

        Map<String, Object> props = new HashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(envFile)) {
            reader.lines()
                    .filter(line -> !line.isBlank() && !line.trim().startsWith("#"))
                    .map(line -> line.split("=", 2))
                    .filter(parts -> parts.length == 2)
                    .forEach(parts -> {
                        String key = parts[0].trim();
                        String value = parts[1].trim();
                        if (!key.isEmpty() && !value.isEmpty()) {
                            props.put(key, value);
                        }
                    });
        } catch (Exception e) {
            System.err.println("[DOTENV] 读取失败: " + e.getMessage());
            return;
        }

        environment.getPropertySources().addFirst(new MapPropertySource("dotenv", props));
        System.out.println("[DOTENV] 已加载 " + props.size() + " 个变量");
    }
}
