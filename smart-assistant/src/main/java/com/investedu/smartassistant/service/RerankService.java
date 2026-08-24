package com.investedu.smartassistant.service;

import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "rerank.enabled", havingValue = "true")
public class RerankService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${rerank.api-key}")
    private String apiKey;

    @Value("${rerank.model}")
    private String model;

    @Value("${rerank.base-url}")
    private String baseUrl;

    public List<TextSegment> rerank(String query, List<TextSegment> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        List<String> documents = candidates.stream()
                .map(TextSegment::text)
                .collect(Collectors.toList());

        // 标准 rerank 请求体（与 Cohere/Jina 一致）
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("query", query);
        requestBody.put("documents", documents);
        requestBody.put("top_n", Math.min(candidates.size(), 3));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("Content-Type", "application/json");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(baseUrl, request, Map.class);

        // 通用解析 results
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");
        if (results == null) {
            // 可能放在 data 字段下
            results = (List<Map<String, Object>>) response.getBody().get("data");
        }

        return results.stream()
                .sorted((a, b) -> Double.compare(
                        ((Number) b.getOrDefault("relevance_score", b.getOrDefault("score", 0.0))).doubleValue(),
                        ((Number) a.getOrDefault("relevance_score", a.getOrDefault("score", 0.0))).doubleValue()))
                .map(r -> {
                    int index = ((Number) r.get("index")).intValue();
                    return candidates.get(index);
                })
                .collect(Collectors.toList());
    }
}