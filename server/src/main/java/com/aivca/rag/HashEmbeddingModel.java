package com.aivca.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于字符 n-gram 哈希的轻量 Embedding 模型。
 *
 * 将文本的 2/3/4-gram 映射到 256 维浮点向量。相似的文本产生相似的向量，
 * 无需外部 API 或模型文件，纯 Java 实现。
 */
@Slf4j
public class HashEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSION = 256;

    /** 将文本转为 256 维浮点向量 */
    private Embedding embedText(String text) {
        float[] vec = new float[DIMENSION];
        if (text == null || text.isBlank()) return Embedding.from(vec);

        // 1. 字符 2-gram, 3-gram, 4-gram
        String s = text.toLowerCase();
        for (int i = 0; i < s.length(); i++) {
            for (int len = 2; len <= 4 && i + len <= s.length(); len++) {
                String gram = s.substring(i, i + len);
                int pos = hashToPos(gram, DIMENSION);
                vec[pos] += 1.0f / (len * 10.0f);  // 更长的 gram 权重更低
            }
        }

        // 2. L2 归一化
        float norm = 0;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        }

        return Embedding.from(vec);
    }

    @Override
    public Response<Embedding> embed(String text) {
        return Response.from(embedText(text));
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        return Response.from(embedText(segment.text()));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> embeddings = new ArrayList<>();
        for (TextSegment seg : segments) {
            embeddings.add(embedText(seg.text()));
        }
        return Response.from(embeddings);
    }

    /** 将 gram 哈希到 [0, dim) 范围 */
    private int hashToPos(String gram, int dim) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(gram.getBytes(StandardCharsets.UTF_8));
            return Math.abs((hash[0] & 0xFF) | ((hash[1] & 0xFF) << 8)) % dim;
        } catch (NoSuchAlgorithmException e) {
            return Math.abs(gram.hashCode() % dim);
        }
    }
}
