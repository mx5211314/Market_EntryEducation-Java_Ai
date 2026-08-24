package com.investedu.smartassistant.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.investedu.smartassistant.entity.Diary;
import com.investedu.smartassistant.entity.RiskAssessment;
import com.investedu.smartassistant.mapper.DiaryMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.Objects;

/**
 * 投资日记：把「记心情」改成「记决策 + 到期对账」。
 * 记录时必须写下卖出条件，到期后系统把日记推回来问是否触发、是否照做，
 * 由此算出可解释的纪律分并识别常见认知偏差——用户能看见自己「说到做不到」。
 */
@Service
public class DiaryService {

    /** 可选的买入理由。前四项是可验证的分析依据，后三项是典型的非理性来源 */
    public static final List<String> REASON_TAGS =
            List.of("基本面", "技术面", "估值便宜", "长期配置", "听消息", "跟风热点", "情绪冲动");
    private static final Set<String> IRRATIONAL_REASONS = Set.of("听消息", "跟风热点", "情绪冲动");
    /** 没填预期持有期时的默认回顾间隔（天） */
    private static final int DEFAULT_HOLD_DAYS = 30;
    /** 趋势曲线取最近多少条 */
    private static final int TREND_SIZE = 10;

    private final DiaryMapper diaryMapper;
    private final ChatLanguageModel chatLanguageModel;
    private final RiskAssessmentService assessmentService;

    public DiaryService(DiaryMapper diaryMapper, ChatLanguageModel chatLanguageModel,
                        RiskAssessmentService assessmentService) {
        this.diaryMapper = diaryMapper;
        this.chatLanguageModel = chatLanguageModel;
        this.assessmentService = assessmentService;
    }

    public Diary create(Long userId, Map<String, Object> body) {
        Diary diary = new Diary();
        diary.setUserId(userId);
        diary.setCreatedAt(LocalDateTime.now());
        applyForm(diary, body);
        diaryMapper.insert(diary);
        return diary;
    }

    public void create(Diary diary) {
        diaryMapper.insert(diary);
    }

    public Diary update(Long id, Long userId, Map<String, Object> body) {
        Diary diary = require(id, userId);
        applyForm(diary, body);
        diaryMapper.updateById(diary);
        return diary;
    }

    public void delete(Long id, Long userId) {
        require(id, userId);
        diaryMapper.deleteById(id);
    }

    /** 归属校验统一走这里：查详情也要过，否则换个 id 就能读别人的日记 */
    public Diary require(Long id, Long userId) {
        Diary diary = diaryMapper.selectById(id);
        if (diary == null || !Objects.equals(diary.getUserId(), userId)) {
            throw new RuntimeException("日记不存在");
        }
        return diary;
    }

    public List<Diary> listByUser(Long userId) {
        QueryWrapper<Diary> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).orderByDesc("created_at");
        return diaryMapper.selectList(wrapper);
    }

    /** 时间线分页。统计仍走全量：纪律分平均值和偏差次数不能只算当前页 */
    public IPage<Diary> pageByUser(Long userId, int pageNum, int pageSize) {
        QueryWrapper<Diary> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).orderByDesc("created_at");
        return diaryMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /** 到期未回顾的记录，页面顶部要拿它做提醒 */
    public List<Diary> pendingReviews(Long userId) {
        QueryWrapper<Diary> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId)
                .isNull("reviewed_at")
                .isNotNull("review_due_at")
                .le("review_due_at", LocalDateTime.now())
                .orderByAsc("review_due_at");
        return diaryMapper.selectList(wrapper);
    }

    /** 到期对账：卖出条件触发了吗？你照做了吗？答完重算纪律分与偏差 */
    public Diary review(Long id, Long userId, Boolean triggered, Boolean executed,
                        String resultTag, String note) {
        Diary diary = require(id, userId);
        diary.setReviewTriggered(Boolean.TRUE.equals(triggered));
        diary.setReviewExecuted(Boolean.TRUE.equals(triggered) && Boolean.TRUE.equals(executed));
        diary.setResultTag(resultTag);
        diary.setReviewNote(note);
        diary.setReviewedAt(LocalDateTime.now());
        diary.setUpdatedAt(LocalDateTime.now());
        diary.setDisciplineScore(scoreOf(disciplineItems(diary)));
        diary.setBiasTags(detectBias(diary));
        diaryMapper.updateById(diary);
        return diary;
    }

    /** AI 复盘教练：拆解这次决策里哪部分是依据、哪部分是情绪，并追问一个反思问题 */
    public String coach(Long id, Long userId) {
        Diary d = require(id, userId);
        String prompt = "你是投资者教育平台的复盘教练。基于下面这条投资日记做点评，"
                + "严格分三段，总字数不超过 300 字：\n"
                + "1. 决策拆解：指出哪些是可验证的分析依据，哪些其实是情绪或从众。\n"
                + "2. 纪律点评：这条卖出条件是否具体、可衡量、可执行，不合格要说明怎么改。\n"
                + "3. 反思问题：只问一个能让他下次改进的问题。\n"
                + "不要推荐任何具体标的，不要预测涨跌，不要给收益承诺。\n\n"
                + "用户风险等级：" + levelCodeOf(userId) + "\n"
                + "标的：" + blankTo(d.getSymbol(), "未填") + "\n"
                + "操作：" + blankTo(d.getDirection(), "未填")
                + "，投入占比：" + (d.getPositionRatio() == null ? "未填" : d.getPositionRatio() + "%") + "\n"
                + "买入理由：" + blankTo(d.getReasonTags(), "未填") + "\n"
                + "预期持有：" + (d.getExpectHoldDays() == null ? "未填" : d.getExpectHoldDays() + " 天") + "\n"
                + "卖出条件：" + blankTo(d.getSellPlan(), "没有写") + "\n"
                + "当时情绪：" + blankTo(d.getMood(), "未填")
                + "（" + (d.getMoodScore() == null ? "-" : d.getMoodScore()) + "/10）\n"
                + "回顾结果：" + reviewSummary(d) + "\n"
                + "日记内容：" + blankTo(d.getContent(), "无");
        String output;
        try {
            output = chatLanguageModel.generate(prompt).trim();
        } catch (Exception e) {
            throw new RuntimeException("AI 复盘服务暂时不可用，请稍后再试");
        }
        d.setAiReview(output);
        d.setUpdatedAt(LocalDateTime.now());
        diaryMapper.updateById(d);
        return output;
    }

    /** 单条日记的纪律分构成，展示用；和存库的分数共用同一套规则，不会两边算不一样 */
    public List<Map<String, Object>> disciplineItems(Diary d) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("基础分", 40));

        boolean hasPlan = notBlank(d.getSellPlan());
        items.add(item(hasPlan ? "写下了卖出条件" : "没写卖出条件", hasPlan ? 20 : 0));

        boolean complete = notBlank(d.getSymbol()) && notBlank(d.getDirection())
                && notBlank(d.getReasonTags()) && d.getExpectHoldDays() != null;
        items.add(item(complete ? "决策要素记录完整" : "决策要素不完整", complete ? 10 : 0));

        int bad = irrationalCount(d.getReasonTags());
        if (bad > 0) items.add(item("理由含 " + bad + " 项非理性来源", -10 * Math.min(bad, 2)));

        if (d.getPositionRatio() != null && d.getPositionRatio() > 50) {
            items.add(item("单次投入超过五成资金", -10));
        }

        if (d.getReviewedAt() != null) {
            items.add(item("完成了到期回顾", 10));
            if (Boolean.TRUE.equals(d.getReviewTriggered())) {
                boolean done = Boolean.TRUE.equals(d.getReviewExecuted());
                items.add(item(done ? "条件触发后照计划执行" : "条件触发却没执行", done ? 20 : -20));
            } else {
                items.add(item("条件未触发，按计划继续持有", 10));
            }
        }
        return items;
    }

    /** 表单落到实体：用户自己选的方向和情绪优先，AI 只在没填时兜底，不再覆盖用户的选择 */
    public void applyForm(Diary d, Map<String, Object> body) {
        d.setTitle(str(body, "title"));
        d.setContent(str(body, "content"));
        d.setRecordType("full".equals(str(body, "recordType")) ? "full" : "quick");
        d.setSymbol(str(body, "symbol"));
        d.setDirection(str(body, "direction"));
        d.setPositionRatio(integer(body, "positionRatio"));
        d.setReasonTags(joinTags(body.get("reasonTags")));
        d.setExpectHoldDays(integer(body, "expectHoldDays"));
        d.setSellPlan(str(body, "sellPlan"));
        d.setMood(str(body, "mood"));
        d.setMoodScore(integer(body, "moodScore"));
        d.setTradeAction(notBlank(d.getDirection()) ? d.getDirection() : "持仓观察");
        d.setSentiment(sentimentOf(d));
        if (d.getCreatedAt() == null) d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        // 只有完整复盘才进对账流程：快速记录连卖出条件都没有，到期也无从回顾
        if ("full".equals(d.getRecordType()) && notBlank(d.getSellPlan()) && d.getReviewedAt() == null) {
            int days = d.getExpectHoldDays() == null || d.getExpectHoldDays() <= 0
                    ? DEFAULT_HOLD_DAYS : d.getExpectHoldDays();
            d.setReviewDueAt(d.getCreatedAt().plusDays(days));
        }
        d.setDisciplineScore(scoreOf(disciplineItems(d)));
        d.setBiasTags(detectBias(d));
    }

    /** 仅更新审核字段（自动审核后回写） */
    public void updateAuditFields(Long id, Long userId, Integer auditStatus, String auditReason, LocalDateTime auditAt) {
        Diary diary = require(id, userId);
        diary.setAuditStatus(auditStatus);
        diary.setAuditReason(auditReason);
        diary.setAuditAt(auditAt);
        diary.setUpdatedAt(LocalDateTime.now());
        diaryMapper.updateById(diary);
    }

    /** 获取单条详情（含归属校验） */
    public Map<String, Object> detail(Long userId, Long id) {
        Diary d = require(id, userId);
        Map<String, Object> m = new HashMap<>();
        m.put("id", d.getId());
        m.put("userId", d.getUserId());
        m.put("title", d.getTitle());
        m.put("content", d.getContent());
        m.put("recordType", d.getRecordType());
        m.put("symbol", d.getSymbol());
        m.put("direction", d.getDirection());
        m.put("positionRatio", d.getPositionRatio());
        m.put("reasonTags", d.getReasonTags());
        m.put("expectHoldDays", d.getExpectHoldDays());
        m.put("sellPlan", d.getSellPlan());
        m.put("mood", d.getMood());
        m.put("moodScore", d.getMoodScore());
        m.put("sentiment", d.getSentiment());
        m.put("disciplineScore", d.getDisciplineScore());
        m.put("biasTags", d.getBiasTags());
        m.put("aiReview", d.getAiReview());
        m.put("reviewDueAt", d.getReviewDueAt());
        m.put("reviewedAt", d.getReviewedAt());
        m.put("reviewTriggered", d.getReviewTriggered());
        m.put("reviewExecuted", d.getReviewExecuted());
        m.put("resultTag", d.getResultTag());
        m.put("reviewNote", d.getReviewNote());
        m.put("auditStatus", d.getAuditStatus());
        m.put("auditReason", d.getAuditReason());
        m.put("auditAt", d.getAuditAt());
        m.put("auditBy", d.getAuditBy());
        m.put("createdAt", d.getCreatedAt());
        m.put("updatedAt", d.getUpdatedAt());
        return m;
    }

    /** 情绪由用户自己打分决定，比让大模型猜内容更准、也省一次调用；没打分才回落到 AI */
    private String sentimentOf(Diary d) {
        Integer score = d.getMoodScore();
        if (score != null) {
            if (score >= 7) return "积极";
            if (score <= 4) return "消极";
            return "中性";
        }
        try {
            String prompt = "判断以下投资日记的情绪，只回复 积极、中性、消极 三个词之一：\n" + d.getContent();
            String out = chatLanguageModel.generate(prompt).trim();
            for (String s : List.of("积极", "消极", "中性")) {
                if (out.contains(s)) return s;
            }
        } catch (Exception ignored) {
        }
        return "中性";
    }

    /**
     * 规则式偏差识别。刻意不用大模型：命中条件必须能一条条讲清楚，
     * 用户改一个字段就该看到对应标签消失，否则这个分数没有说服力。
     */
    private String detectBias(Diary d) {
        Set<String> tags = new LinkedHashSet<>();
        String reasons = d.getReasonTags() == null ? "" : d.getReasonTags();
        String direction = d.getDirection() == null ? "" : d.getDirection();
        Integer mood = d.getMoodScore();
        boolean opening = direction.equals("买入") || direction.equals("加仓");

        if (!notBlank(d.getSellPlan())) tags.add("无退出计划");
        if (reasons.contains("听消息") || reasons.contains("跟风热点")) tags.add("消息驱动");
        if (reasons.contains("情绪冲动")) tags.add("情绪化交易");
        if (opening && reasons.contains("技术面") && mood != null && mood >= 8) tags.add("追涨倾向");
        if (direction.equals("加仓") && "亏损".equals(d.getResultTag())) tags.add("亏损摊平");
        if (direction.equals("卖出") && "盈利".equals(d.getResultTag())
                && d.getReviewedAt() != null && d.getReviewDueAt() != null
                && d.getReviewedAt().isBefore(d.getReviewDueAt())) {
            tags.add("过早止盈");
        }
        if (Boolean.TRUE.equals(d.getReviewTriggered()) && Boolean.FALSE.equals(d.getReviewExecuted())) {
            tags.add("说到做不到");
        }
        if (d.getPositionRatio() != null && d.getPositionRatio() > 50) tags.add("单次重仓");
        return String.join(",", tags);
    }

    /** 偏差 -> 给用户的一句建议，命中后页面直接引导去知识库看对应主题 */
    private static final Map<String, String> BIAS_ADVICE = new LinkedHashMap<>();
    /** 偏差 -> 知识库检索词。映射只放后端一份，前端点标签时带着这个词跳过去筛文章 */
    private static final Map<String, String> BIAS_KEYWORD = new LinkedHashMap<>();

    static {
        BIAS_ADVICE.put("无退出计划", "买之前先写清楚什么情况下卖，否则涨跌都只能凭当下感觉决定");
        BIAS_ADVICE.put("消息驱动", "消息到你这里往往已是第二手，先问自己能否独立验证这个理由");
        BIAS_ADVICE.put("情绪化交易", "情绪最高的时候下单，事后回看多半是买在了短期高位");
        BIAS_ADVICE.put("追涨倾向", "只因为在涨就买入，等于把别人的乐观当成自己的依据");
        BIAS_ADVICE.put("亏损摊平", "越跌越买要有基本面依据支撑，否则只是把亏损做大");
        BIAS_ADVICE.put("过早止盈", "没到计划期限就因为浮盈卖出，长期会系统性错过大部分涨幅");
        BIAS_ADVICE.put("说到做不到", "条件触发却没执行，说明计划定得太松或对亏损的接受度估高了");
        BIAS_ADVICE.put("单次重仓", "单次投入超过五成资金，一次判断失误就会伤到整体仓位");

        BIAS_KEYWORD.put("无退出计划", "止损");
        BIAS_KEYWORD.put("消息驱动", "内幕信息");
        BIAS_KEYWORD.put("情绪化交易", "投资者情绪");
        BIAS_KEYWORD.put("追涨倾向", "追涨杀跌");
        BIAS_KEYWORD.put("亏损摊平", "补仓");
        BIAS_KEYWORD.put("过早止盈", "长期持有");
        BIAS_KEYWORD.put("说到做不到", "交易纪律");
        BIAS_KEYWORD.put("单次重仓", "分散投资");
        BIAS_KEYWORD.put("交易过于频繁", "交易成本");
    }

    /** 页面顶部的 KPI、纪律分趋势、偏差统计都出自这里 */
    public Map<String, Object> getStats(Long userId) {
        List<Diary> diaries = listByUser(userId);
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);

        int planned = 0, triggered = 0, executed = 0, reviewed = 0, pending = 0;
        int scoreSum = 0, scoreCount = 0, weekTrades = 0;
        Map<String, Integer> biasCount = new LinkedHashMap<>();
        Map<String, Integer> directionCount = new LinkedHashMap<>();
        Map<String, Integer> sentimentCount = new LinkedHashMap<>();
        for (String s : List.of("积极", "中性", "消极")) sentimentCount.put(s, 0);

        LocalDateTime now = LocalDateTime.now();
        for (Diary d : diaries) {
            if (notBlank(d.getSellPlan())) planned++;
            if (d.getReviewedAt() != null) {
                reviewed++;
                if (Boolean.TRUE.equals(d.getReviewTriggered())) {
                    triggered++;
                    if (Boolean.TRUE.equals(d.getReviewExecuted())) executed++;
                }
            } else if (d.getReviewDueAt() != null && d.getReviewDueAt().isBefore(now)) {
                pending++;
            }
            if (d.getDisciplineScore() != null) {
                scoreSum += d.getDisciplineScore();
                scoreCount++;
            }
            if (notBlank(d.getDirection())) directionCount.merge(d.getDirection(), 1, Integer::sum);
            if (notBlank(d.getSentiment())) sentimentCount.merge(d.getSentiment(), 1, Integer::sum);
            for (String tag : splitTags(d.getBiasTags())) biasCount.merge(tag, 1, Integer::sum);
            if (d.getCreatedAt() != null && d.getCreatedAt().isAfter(weekAgo)
                    && notBlank(d.getDirection()) && !d.getDirection().equals("持仓观察")) {
                weekTrades++;
            }
        }
        // 一周内五笔以上买卖，本身就是个值得提醒的信号
        if (weekTrades > 5) biasCount.merge("交易过于频繁", weekTrades, Integer::sum);

        List<Map<String, Object>> bias = new ArrayList<>();
        biasCount.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    m.put("advice", BIAS_ADVICE.getOrDefault(e.getKey(),
                            "一周内买卖次数偏多，交易成本和情绪损耗都会累积"));
                    m.put("keyword", BIAS_KEYWORD.getOrDefault(e.getKey(), e.getKey()));
                    bias.add(m);
                });

        // 趋势按时间正序，取最近 TREND_SIZE 条
        List<Map<String, Object>> trend = new ArrayList<>();
        List<Diary> recent = diaries.stream().limit(TREND_SIZE).toList();
        for (int i = recent.size() - 1; i >= 0; i--) {
            Diary d = recent.get(i);
            Map<String, Object> point = new HashMap<>();
            point.put("date", d.getCreatedAt() == null ? "" : d.getCreatedAt().toLocalDate().toString());
            point.put("score", d.getDisciplineScore() == null ? 0 : d.getDisciplineScore());
            trend.add(point);
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", diaries.size());
        stats.put("disciplineScore", scoreCount == 0 ? 0 : Math.round(scoreSum * 1.0f / scoreCount));
        stats.put("planRate", diaries.isEmpty() ? 0 : Math.round(planned * 100.0f / diaries.size()));
        stats.put("executeRate", triggered == 0 ? -1 : Math.round(executed * 100.0f / triggered));
        stats.put("reviewedCount", reviewed);
        stats.put("pendingCount", pending);
        stats.put("weekTrades", weekTrades);
        stats.put("bias", bias);
        stats.put("trend", trend);
        stats.put("direction", directionCount);
        stats.put("sentiment", sentimentCount);
        return stats;
    }

    private String levelCodeOf(Long userId) {
        try {
            RiskAssessment latest = assessmentService.getLatest(userId);
            if (latest == null) return "未测评";
            return String.valueOf(assessmentService.describeRecord(latest).get("levelCode"));
        } catch (Exception e) {
            return "未测评";
        }
    }

    private String reviewSummary(Diary d) {
        if (d.getReviewedAt() == null) return "尚未到期回顾";
        String base = Boolean.TRUE.equals(d.getReviewTriggered())
                ? (Boolean.TRUE.equals(d.getReviewExecuted()) ? "卖出条件已触发并照计划执行" : "卖出条件已触发但没有执行")
                : "卖出条件未触发，继续持有";
        return base + "，自报结果：" + blankTo(d.getResultTag(), "未填")
                + (notBlank(d.getReviewNote()) ? "，补充：" + d.getReviewNote() : "");
    }

    private int scoreOf(List<Map<String, Object>> items) {
        int sum = 0;
        for (Map<String, Object> item : items) sum += ((Number) item.get("delta")).intValue();
        return Math.max(0, Math.min(100, sum));
    }

    private Map<String, Object> item(String label, int delta) {
        Map<String, Object> m = new HashMap<>();
        m.put("label", label);
        m.put("delta", delta);
        return m;
    }

    private int irrationalCount(String tags) {
        int count = 0;
        for (String tag : splitTags(tags)) {
            if (IRRATIONAL_REASONS.contains(tag)) count++;
        }
        return count;
    }

    private List<String> splitTags(String tags) {
        if (!notBlank(tags)) return List.of();
        List<String> list = new ArrayList<>();
        for (String part : tags.split(",")) {
            if (notBlank(part)) list.add(part.trim());
        }
        return list;
    }

    private String joinTags(Object raw) {
        if (raw instanceof List<?> items) {
            List<String> list = new ArrayList<>();
            for (Object o : items) {
                if (o != null && notBlank(o.toString())) list.add(o.toString().trim());
            }
            return String.join(",", list);
        }
        return raw == null ? null : String.valueOf(raw);
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private Integer integer(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? null : Integer.valueOf(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String blankTo(String s, String fallback) {
        return notBlank(s) ? s : fallback;
    }
}
