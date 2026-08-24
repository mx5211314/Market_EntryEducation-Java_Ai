package com.investedu.smartassistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investedu.smartassistant.agent.InvestmentAgent;
import com.investedu.smartassistant.agent.InvestmentAgentGraph;
import com.investedu.smartassistant.agent.GraphState;
import com.investedu.smartassistant.entity.ChatMessage;
import com.investedu.smartassistant.entity.ChatSession;
import com.investedu.smartassistant.entity.RiskAssessment;
import com.investedu.smartassistant.entity.User;
import com.investedu.smartassistant.retriever.HybridRetriever;
import com.investedu.smartassistant.service.ChatMessageService;
import com.investedu.smartassistant.service.ChatSessionService;
import com.investedu.smartassistant.service.QueryRewriter;
import com.investedu.smartassistant.service.ResponseValidator;
import com.investedu.smartassistant.service.RiskAssessmentService;
import com.investedu.smartassistant.service.AnswerSourceService;
import com.investedu.smartassistant.util.AuthContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ChatController {

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private HybridRetriever hybridRetriever;

    @Autowired
    private InvestmentAgent investmentAgent;

    @Autowired
    private InvestmentAgentGraph investmentAgentGraph;

    @Autowired
    private QueryRewriter queryRewriter;

    @Autowired
    private ResponseValidator responseValidator;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private AuthContext authContext;

    @Autowired
    private RiskAssessmentService riskAssessmentService;

    @Autowired
    private AnswerSourceService answerSourceService;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.stream-url}")
    private String streamUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT =
            "你是“入市教育智慧助手”，一个由金融法规知识驱动的智能伴学系统。\n" +
                    "你的使命是帮助投资者理解证券交易规则、融资融券业务、风险测评等金融知识，并引导他们通过模拟交易熟悉市场。\n" +
                    "回答风格请参考 DeepSeek：结构化、条理清晰、详细且友好。如果问题与金融无关，可以简短聊天，但最终引导回投资教育主题。\n" +
                    "回答中必须包含“风险提示”和“法规来源”（如果引用了知识库）。\n" +
                    "不要编造知识库中没有的法规，如果知识库无相关内容，请基于通用金融知识回答，并注明“仅供参考”。";

    /**
     * 获取会话列表
     */
    @GetMapping("/chat")
    public List<Map<String, Object>> getSessionList() {
        User user = authContext.requireUser();

        List<ChatSession> sessions = chatSessionService.listSessions(user.getId());
        return sessions.stream().map(session -> {
            Map<String, Object> map = new HashMap<>();
            map.put("sessionId", session.getSessionId());
            map.put("title", session.getTitle());
            map.put("createdAt", session.getCreatedAt());
            map.put("updatedAt", session.getUpdatedAt());
            // 获取最后一条消息
            List<ChatMessage> messages = chatMessageService.listMessages(session.getSessionId());
            if (!messages.isEmpty()) {
                ChatMessage lastMsg = messages.get(messages.size() - 1);
                map.put("lastMessage", lastMsg.getContent());
            }
            return map;
        }).collect(Collectors.toList());
    }

    /**
     * 同步问答接口（支持持久化会话）
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> request) {
        String sessionId = request.getOrDefault("sessionId", "");
        String userMessage = request.get("message");
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return Map.of("reply", "您好，请问有什么可以帮您的？");
        }

        Long userId = authContext.requireUserId();

        // 创建或更新会话
        if (sessionId.isEmpty()) {
            ChatSession newSession = chatSessionService.createSession(userId, userMessage.substring(0, Math.min(20, userMessage.length())));
            sessionId = newSession.getSessionId();
        } else {
            chatSessionService.touchSession(userId, sessionId);
        }

        // 保存用户消息
        chatMessageService.saveMessage(sessionId, userId, "user", userMessage);

        // 获取历史消息
        List<Map<String, String>> history = chatMessageService.listMessages(sessionId).stream()
                .map(msg -> Map.of("role", msg.getRole(), "content", msg.getContent()))
                .collect(Collectors.toList());

        // 意图重写与回答生成
        String rewritten = queryRewriter.rewrite(userMessage);
        String riskProfile = riskProfilePrompt(userId);
        String reply;
        List<Map<String, Object>> sources = List.of();
        if (QueryRewriter.isChat(rewritten)) {
            reply = chatWithHistory(history, userMessage, riskProfile);
        } else {
            List<TextSegment> relevant = hybridRetriever.retrieve(rewritten, 4);
            String context = relevant.stream().map(TextSegment::text).collect(Collectors.joining("\n\n"));
            sources = answerSourceService.extract(relevant);
            reply = responseValidator.generateWithValidation(buildFinancePrompt(context, userMessage, history, riskProfile), 2);
        }

        // 保存助手消息
        chatMessageService.saveMessage(sessionId, userId, "assistant", reply, toJson(sources));

        return Map.of("reply", reply, "sessionId", sessionId, "sources", sources);
    }

    /**
     * 流式问答接口（SSE，支持持久化会话）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, String> request) {
        SseEmitter emitter = new SseEmitter(0L);
        String sessionId = request.getOrDefault("sessionId", "");
        String userMessage = request.get("message");
        if (userMessage == null || userMessage.trim().isEmpty()) {
            sendSingleSseEvent(emitter, "您好，请问有什么可以帮您的？");
            return emitter;
        }

        // SSE 里不能直接抛异常，前端拿不到原因，只会看到连接断开
        Long userId;
        try {
            userId = authContext.requireUserId();
        } catch (RuntimeException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        // 创建或更新会话
        if (sessionId.isEmpty()) {
            ChatSession newSession = chatSessionService.createSession(userId, userMessage.substring(0, Math.min(20, userMessage.length())));
            sessionId = newSession.getSessionId();
        } else {
            chatSessionService.touchSession(userId, sessionId);
        }

        // 保存用户消息
        chatMessageService.saveMessage(sessionId, userId, "user", userMessage);

        // 获取历史消息
        List<Map<String, String>> history = chatMessageService.listMessages(sessionId).stream()
                .map(msg -> Map.of("role", msg.getRole(), "content", msg.getContent()))
                .collect(Collectors.toList());

        // 为 lambda 创建 final 副本
        final String finalSessionId = sessionId;
        final Long finalUserId = userId;
        final String finalUserMessage = userMessage;
        final List<Map<String, String>> finalHistory = history;
        final String finalRiskProfile = riskProfilePrompt(userId);

        CompletableFuture.runAsync(() -> {
            try {
                String rewritten = queryRewriter.rewrite(finalUserMessage);

                // 闲聊：直接生成并一次性发送
                if (QueryRewriter.isChat(rewritten)) {
                    String reply = chatWithHistory(finalHistory, finalUserMessage, finalRiskProfile);
                    chatMessageService.saveMessage(finalSessionId, finalUserId, "assistant", reply);
                    sendSingleSseEvent(emitter, reply);
                    return;
                }

                // 金融问题：检索知识库，构建流式 API 的 messages
                List<TextSegment> relevant = hybridRetriever.retrieve(rewritten, 4);
                String context = relevant.stream().map(TextSegment::text).collect(Collectors.joining("\n\n"));

                // 来源先推出去：正文可能要生成十几秒，等结束再发用户面对的是一段没有出处的文字
                List<Map<String, Object>> sources = answerSourceService.extract(relevant);
                String sourcesJson = toJson(sources);
                if (!sources.isEmpty()) {
                    emitter.send(SseEmitter.event().name("sources").data(sourcesJson));
                }
                List<Map<String, Object>> messages = new ArrayList<>();
                messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT + finalRiskProfile));
                for (Map<String, String> msg : finalHistory) {
                    messages.add(Map.of("role", msg.get("role"), "content", msg.get("content")));
                }
                String userPrompt = String.format(
                        "根据以下金融法规知识，回答用户问题。回答中必须包含“风险提示”和“法规来源”。\n\n知识库：\n%s\n\n用户：%s",
                        context.isEmpty() ? "无相关知识" : context,
                        finalUserMessage);
                messages.add(Map.of("role", "user", "content", userPrompt));

                // 调用百炼流式 API
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", "Bearer " + apiKey);

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", modelName);
                requestBody.put("stream", true);
                requestBody.put("messages", messages);

                restTemplate.execute(streamUrl, HttpMethod.POST, req -> {
                    req.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    req.getHeaders().set("Authorization", "Bearer " + apiKey);
                    req.getBody().write(objectMapper.writeValueAsString(requestBody).getBytes(StandardCharsets.UTF_8));
                }, resp -> {
                    StringBuilder fullReply = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(resp.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if ("[DONE]".equals(data)) break;
                                try {
                                    Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                                    List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                                    if (choices != null && !choices.isEmpty()) {
                                        Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                                        if (delta != null && delta.containsKey("content")) {
                                            String content = (String) delta.get("content");
                                            if (content != null && !content.isEmpty()) {
                                                fullReply.append(content);
                                                emitter.send(SseEmitter.event().data(content));
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    // 忽略解析错误
                                }
                            }
                        }
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                        return null;
                    }
                    // 保存助手消息
                    chatMessageService.saveMessage(finalSessionId, finalUserId, "assistant", fullReply.toString(), sourcesJson);
                    emitter.complete();
                    return null;
                });
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Agent 引导接口（同步）
     */
    @PostMapping("/agent/guidance")
    public Map<String, String> agentGuidance(@RequestBody Map<String, String> request) {
        String userInput = request.get("message");
        if (userInput == null || userInput.trim().length() < 2) {
            return Map.of("guidance", "请输入完整的问题，例如“我想了解融资融券交易规则”。");
        }
        String report = investmentAgent.run(userInput);
        return Map.of("guidance", report);
    }

    /**
     * 状态图 Agent 接口：意图分类 → 风测/检索/模拟 → 合规生成 → 校验重试
     */
    @PostMapping("/agent/graph")
    public Map<String, Object> agentGraph(@RequestBody Map<String, Object> request) {
        String userInput = (String) request.get("message");
        if (userInput == null || userInput.trim().length() < 2) {
            return Map.of("reply", "请输入完整的问题。");
        }

        Long userId = authContext.requireUserId();
        String sessionId = (String) request.getOrDefault("sessionId", "");

        Map<String, Object> extraContext = new HashMap<>();
        if (request.containsKey("holdings")) extraContext.put("holdings", request.get("holdings"));
        if (request.containsKey("amount")) extraContext.put("amount", request.get("amount"));

        GraphState result = investmentAgentGraph.invoke(userId, sessionId, userInput, extraContext);

        Map<String, Object> response = new HashMap<>();
        response.put("reply", result.getFinalAnswer());
        response.put("intent", result.getIntent());
        if (result.getRiskAssessment() != null) {
            response.put("riskLevel", result.getRiskAssessment().getLevelCode());
        }
        if (result.getSimulation() != null) {
            response.put("simulation", Map.of(
                    "suitable", result.getSimulation().isSuitable(),
                    "riskScore", result.getSimulation().getRiskScore(),
                    "expectedReturn", result.getSimulation().getExpectedReturn()
            ));
        }
        if (result.getSources() != null && !result.getSources().isEmpty()) {
            response.put("sources", result.getSources());
        }
        if (result.getError() != null) {
            response.put("error", result.getError());
        }
        return response;
    }

    // ==================== 私有辅助方法 ====================

    /** 来源序列化失败不该影响回答，退化成没有来源即可 */
    private String toJson(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            return null;
        }
    }

    private void sendSingleSseEvent(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().data(message));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private String buildFinancePrompt(String context, String currentMsg, List<Map<String, String>> history, String riskProfile) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPT).append(riskProfile).append("\n\n");
        sb.append("历史对话：\n");
        for (Map<String, String> msg : history) {
            sb.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
        }
        sb.append("当前问题：").append(currentMsg).append("\n");
        sb.append("知识库参考：\n").append(context.isEmpty() ? "无" : context).append("\n");
        sb.append("assistant: ");
        return sb.toString();
    }

    private String chatWithHistory(List<Map<String, String>> history, String currentMsg, String riskProfile) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPT).append(riskProfile).append("\n\n");
        for (Map<String, String> msg : history) {
            sb.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
        }
        sb.append("user: ").append(currentMsg).append("\nassistant: ");
        return chatLanguageModel.generate(sb.toString()).trim();
    }

    /**
     * 把用户最近一次风险测评结果拼进系统提示，让建议贴合其风险承受能力。
     * 没测过就返回空串，不影响原有回答。
     */
    private String riskProfilePrompt(Long userId) {
        try {
            RiskAssessment latest = riskAssessmentService.getLatest(userId);
            if (latest == null || latest.getLevel() == null) return "";
            RiskAssessmentService.LevelInfo info = riskAssessmentService.levelByName(latest.getLevel());
            return "\n\n【用户风险画像】该用户最近一次风险测评结果为「" + info.name + "」（" + info.code
                    + "，可承受产品风险等级不高于 R" + info.index + "）。" + info.summary
                    + "\n给出投资相关建议时必须贴合这一等级，不要推荐超出其承受能力的品种；"
                    + "若用户主动询问更高风险的业务，先说明其超出测评等级再作解释。";
        } catch (Exception e) {
            return "";
        }
    }
}