package com.aivca.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * HTTP 请求工具类。
 */
public final class HttpUtil {

    private HttpUtil() {}

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** 发送 POST JSON 请求，返回响应 JsonNode */
    public static JsonNode postJson(String url, String jsonBody, ObjectMapper mapper)
            throws IOException, InterruptedException {
        return postJsonWithAuth(url, jsonBody, null, mapper);
    }

    /** 发送 POST JSON 请求（带 Bearer token），返回响应 JsonNode */
    public static JsonNode postJsonWithAuth(String url, String jsonBody, String bearerToken, ObjectMapper mapper)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (bearerToken != null && !bearerToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpRequest req = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }

    /** 发送 POST 无 body 请求，返回响应 JsonNode */
    public static JsonNode postEmpty(String url, ObjectMapper mapper)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }
}
