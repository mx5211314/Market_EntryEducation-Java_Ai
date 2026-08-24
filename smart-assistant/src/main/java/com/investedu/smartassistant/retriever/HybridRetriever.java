package com.investedu.smartassistant.retriever;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.investedu.smartassistant.service.RerankService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索器：向量检索 + BM25 + HyDE 重写 + Cross-Encoder 重排
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    @Qualifier("milvusEmbeddingStore")
    private EmbeddingStore<TextSegment> milvusStore;

    @Autowired(required = false)
    private ChatLanguageModel chatLanguageModel;

    @Autowired(required = false)
    private RerankService rerankService;

    @Value("${elasticsearch.host}")
    private String esHost;

    @Value("${elasticsearch.port}")
    private int esPort;

    @Value("${elasticsearch.index-name}")
    private String esIndex;

    // HyDE 重写接口
    interface HydeRewriter {
        String rewrite(String query);
    }

    public List<TextSegment> retrieve(String query, int maxResults) {
        return retrieve(query, maxResults, false, null);
    }

    /**
     * 增强检索：支持 HyDE 和 Cross-Encoder 重排
     */
    public List<TextSegment> retrieve(String query, int maxResults, boolean useHyde, String userRiskLevel) {
        // 1. HyDE 查询重写（可选）
        String effectiveQuery = query;
        if (useHyde && chatLanguageModel != null) {
            effectiveQuery = rewriteWithHyde(query);
        }

        Embedding queryEmbedding = embeddingModel.embed(effectiveQuery).content();

        // 2. Milvus 向量检索
        List<TextSegment> milvusResults = milvusStore.search(
                        EmbeddingSearchRequest.builder()
                                .queryEmbedding(queryEmbedding)
                                .maxResults(maxResults * 3)  // 多取一些用于重排
                                .build())
                .matches().stream()
                .map(EmbeddingMatch::embedded)
                .collect(Collectors.toList());

        // 3. ES BM25 关键词检索
        List<TextSegment> esResults = esKeywordSearch(query, maxResults * 2);

        // 4. 合并去重
        Set<String> seen = new HashSet<>();
        List<TextSegment> combined = new ArrayList<>();
        for (TextSegment seg : milvusResults) {
            if (seen.add(seg.text())) combined.add(seg);
        }
        for (TextSegment seg : esResults) {
            if (seen.add(seg.text())) combined.add(seg);
        }

        // 5. 本地 Embedding 重排序（粗排）
        List<TextSegment> reranked = rerankByEmbedding(queryEmbedding, combined, maxResults * 2);

        // 6. Cross-Encoder 精排（可选，需外部服务）
        if (userRiskLevel != null && !reranked.isEmpty()) {
            reranked = crossEncoderRerank(effectiveQuery, reranked, userRiskLevel, maxResults);
        } else {
            reranked = reranked.stream().limit(maxResults).collect(Collectors.toList());
        }

        return reranked;
    }

    /** HyDE 查询重写：生成假设性文档并提取关键词 */
    private String rewriteWithHyde(String query) {
        try {
            var rewriter = AiServices.builder(HydeRewriter.class)
                    .chatLanguageModel(chatLanguageModel)
                    .systemMessageProvider(state -> """
                            你是查询扩展器。给定用户的金融问题，生成一段假设性的完整回答片段（含关键法规条款、专业术语、典型案例），
                            然后提取其中最核心的 5-8 个检索关键词，用空格分隔返回。只返回关键词，不要解释。
                            """)
                    .build();
            String hydeKeywords = rewriter.rewrite(query);
            log.debug("HyDE 重写: {} -> {}", query, hydeKeywords);
            return query + " " + hydeKeywords;
        } catch (Exception e) {
            log.warn("HyDE 重写失败，使用原查询: {}", e.getMessage());
            return query;
        }
    }

    /** Cross-Encoder 重排：优先调用外部服务，失败则降级到 Embedding 加权 */
    private List<TextSegment> crossEncoderRerank(String query, List<TextSegment> candidates, String userRiskLevel, int topN) {
        // 优先使用外部 Rerank 服务
        if (rerankService != null) {
            try {
                List<TextSegment> reranked = rerankService.rerank(query, candidates);
                log.debug("Cross-Encoder 重排成功，返回 {} 条结果", reranked.size());
                return reranked.stream().limit(topN).collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("Rerank 服务调用失败，降级到 Embedding 加权: {}", e.getMessage());
            }
        }

        // 降级：Embedding 相似度 + 风险等级加权
        List<AbstractMap.SimpleEntry<TextSegment, Double>> scored = new ArrayList<>();
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        List<Embedding> candEmbeddings = embeddingModel.embedAll(candidates).content();

        for (int i = 0; i < candidates.size(); i++) {
            double sim = cosine(queryEmbedding.vector(), candEmbeddings.get(i).vector());
            double riskBoost = computeRiskBoost(candidates.get(i), userRiskLevel);
            scored.add(new AbstractMap.SimpleEntry<>(candidates.get(i), sim * 0.7 + riskBoost * 0.3));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return scored.stream().limit(topN).map(Map.Entry::getKey).collect(Collectors.toList());
    }

    private double computeRiskBoost(TextSegment segment, String riskLevel) {
        String text = segment.text().toLowerCase();
        int level = parseRiskLevel(riskLevel);
        // R1-R2: 关注保本、稳健、合规条款
        // R3-R4: 关注收益、策略、产品介绍
        // R5: 关注衍生品、杠杆、高收益
        if (level <= 2) {
            return (text.contains("风险") || text.contains("合规") || text.contains("保本") || text.contains("适当性")) ? 0.3 : 0.0;
        } else if (level <= 4) {
            return (text.contains("收益") || text.contains("策略") || text.contains("产品")) ? 0.2 : 0.0;
        } else {
            return (text.contains("衍生") || text.contains("杠杆") || text.contains("期权") || text.contains("融资")) ? 0.3 : 0.0;
        }
    }

    private int parseRiskLevel(String riskLevel) {
        if (riskLevel == null) return 3;
        if (riskLevel.startsWith("R")) {
            try { return Integer.parseInt(riskLevel.substring(1)); } catch (Exception e) { return 3; }
        }
        if (riskLevel.startsWith("C")) {
            try { return Integer.parseInt(riskLevel.substring(1)); } catch (Exception e) { return 3; }
        }
        return 3;
    }

    private List<TextSegment> esKeywordSearch(String query, int size) {
        String url = "http://" + esHost + ":" + esPort + "/" + esIndex + "/_search";
        Map<String, Object> body = new HashMap<>();
        body.put("size", size);
        body.put("query", Map.of("match", Map.of("text", query)));
        try {
            Map<String, Object> resp = restTemplate.postForObject(url, body, Map.class);
            if (resp == null) return List.of();
            Map<String, Object> hits = (Map<String, Object>) resp.get("hits");
            if (hits == null) return List.of();
            List<Map<String, Object>> hitList = (List<Map<String, Object>>) hits.get("hits");
            if (hitList == null) return List.of();
            return hitList.stream()
                    .map(h -> {
                        Map<String, Object> source = (Map<String, Object>) h.get("_source");
                        if (source == null) return null;
                        String text = (String) source.get("text");
                        if (text == null) return null;
                        return TextSegment.from(text, toMetadata(source.get("metadata")));
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("ES BM25 检索失败，降级为仅 Milvus 检索: {}", e.getMessage());
            return List.of();
        }
    }

    private Metadata toMetadata(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return new Metadata();
        Map<String, String> flat = new HashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v != null) flat.put(String.valueOf(k), String.valueOf(v));
        });
        return new Metadata(flat);
    }

    private List<TextSegment> rerankByEmbedding(Embedding queryEmbedding, List<TextSegment> candidates, int topN) {
        if (candidates.isEmpty()) return candidates;
        List<Embedding> candEmbeddings = embeddingModel.embedAll(candidates).content();

        List<AbstractMap.SimpleEntry<TextSegment, Double>> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            double sim = cosine(queryEmbedding.vector(), candEmbeddings.get(i).vector());
            scored.add(new AbstractMap.SimpleEntry<>(candidates.get(i), sim));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return scored.stream().limit(topN).map(Map.Entry::getKey).collect(Collectors.toList());
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-10);
    }
}