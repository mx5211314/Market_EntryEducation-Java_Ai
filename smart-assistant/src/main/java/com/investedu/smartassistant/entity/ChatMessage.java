package com.investedu.smartassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Long userId;
    private String role;
    private String content;
    /** 助手消息引用的知识库来源，JSON 数组；用户消息为空。刷新页面后来源还能显示 */
    private String sources;
    private LocalDateTime createdAt;

    /** 审核状态：0 待审核，1 通过，2 驳回 */
    private Integer auditStatus;
    private String auditReason;
    private LocalDateTime auditAt;
    private Long auditBy;
}