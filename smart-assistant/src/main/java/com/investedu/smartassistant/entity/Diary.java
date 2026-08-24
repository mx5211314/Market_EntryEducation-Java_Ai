package com.investedu.smartassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 投资日记：不只是随笔，而是一次决策的留痕。
 * 记录时先写下卖出条件，到期后系统把这条日记推回来对账，看用户是否做到了自己说的话。
 */
@Data
@TableName("diary")
public class Diary {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String content;

    /** quick 快速记录 / full 完整复盘，只有 full 才进到期回顾 */
    private String recordType;
    private String symbol;
    /** 买入 / 加仓 / 减仓 / 卖出 / 持仓观察 */
    private String direction;
    /** 本次投入占总资金比例（%） */
    private Integer positionRatio;
    /** 买入理由标签，逗号分隔 */
    private String reasonTags;
    private Integer expectHoldDays;
    /** 卖出条件，整个功能的核心字段：没写就没法对账 */
    private String sellPlan;

    private String mood;
    /** 1-10 */
    private Integer moodScore;
    private String sentiment;
    private String tradeAction;

    /** 到期回顾时间，由记录时间 + 预期持有期算出 */
    private LocalDateTime reviewDueAt;
    private LocalDateTime reviewedAt;
    /** 卖出条件是否触发 */
    private Boolean reviewTriggered;
    /** 触发后是否照做 */
    private Boolean reviewExecuted;
    private String reviewNote;
    /** 用户自报结果：盈利 / 亏损 / 持平 */
    private String resultTag;

    /** 规则式纪律分 0-100 */
    private Integer disciplineScore;
    /** 识别到的认知偏差，逗号分隔 */
    private String biasTags;
    /** AI 复盘教练的点评 */
    private String aiReview;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 审核状态：0 待审核，1 通过，2 驳回 */
    private Integer auditStatus;
    private String auditReason;
    private LocalDateTime auditAt;
    private Long auditBy;
}
