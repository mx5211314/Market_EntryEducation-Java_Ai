package com.investedu.smartassistant.service;

import com.investedu.smartassistant.entity.ChatMessage;
import com.investedu.smartassistant.mapper.ChatMessageMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatMessageService {

    private final ChatMessageMapper messageMapper;
    private final ContentAuditService auditService;

    public ChatMessageService(ChatMessageMapper messageMapper, ContentAuditService auditService) {
        this.messageMapper = messageMapper;
        this.auditService = auditService;
    }

    /** 保存前自动审核，返回审核结果供前端提示 */
    public Map<String, Object> saveWithAudit(String sessionId, Long userId, String role, String content, String sourcesJson) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setSources(sourcesJson);
        message.setCreatedAt(LocalDateTime.now());

        ContentAuditService.AuditResult audit = auditService.preCheckChat(message);
        auditService.applyAuditResult(message, audit);

        messageMapper.insert(message);

        Map<String, Object> result = new HashMap<>();
        result.put("message", message);
        result.put("auditStatus", audit.status);
        result.put("auditReason", audit.reason);
        result.put("blocked", audit.blocked);
        return result;
    }

    // 兼容旧接口
    public void saveMessage(String sessionId, Long userId, String role, String content) {
        saveWithAudit(sessionId, userId, role, content, null);
    }

    public void saveMessage(String sessionId, Long userId, String role, String content, String sourcesJson) {
        saveWithAudit(sessionId, userId, role, content, sourcesJson);
    }

    public List<ChatMessage> listMessages(String sessionId) {
        return messageMapper.listBySessionId(sessionId);
    }
}