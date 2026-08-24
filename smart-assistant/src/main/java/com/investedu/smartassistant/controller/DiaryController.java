package com.investedu.smartassistant.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.investedu.smartassistant.entity.Diary;
import com.investedu.smartassistant.service.ContentAuditService;
import com.investedu.smartassistant.service.DiaryService;
import com.investedu.smartassistant.util.AuthContext;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/diary")
public class DiaryController {

    private final DiaryService diaryService;
    private final ContentAuditService auditService;
    private final AuthContext authContext;

    public DiaryController(DiaryService diaryService, ContentAuditService auditService, AuthContext authContext) {
        this.diaryService = diaryService;
        this.auditService = auditService;
        this.authContext = authContext;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Long userId = authContext.requireUserId();
        Diary diary = new Diary();
        diary.setUserId(userId);
        diaryService.applyForm(diary, body);

        // 自动审核
        ContentAuditService.AuditResult audit = auditService.preCheckDiary(diary);
        auditService.applyAuditResult(diary, audit);

        if (audit.blocked) {
            return Map.of("success", false, "message", audit.reason, "auditStatus", audit.status);
        }

        diaryService.create(diary);
        return Map.of("success", true, "diary", diary, "auditStatus", audit.status, "auditReason", audit.reason);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id,
                                      @RequestBody Map<String, Object> body) {
        Long userId = authContext.requireUserId();
        Diary diary = diaryService.update(id, userId, body);

        // 更新后重新审核
        ContentAuditService.AuditResult audit = auditService.preCheckDiary(diary);
        auditService.applyAuditResult(diary, audit);
        diaryService.update(id, userId, Map.of()); // 触发更新审核字段

        if (audit.blocked) {
            return Map.of("success", false, "message", audit.reason, "auditStatus", audit.status);
        }
        return Map.of("success", true, "diary", diary, "auditStatus", audit.status);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Long id) {
        diaryService.delete(id, authContext.requireUserId());
        return Map.of("message", "删除成功");
    }

    @GetMapping("/list")
    public IPage<Diary> list(@RequestParam(defaultValue = "1") int pageNum,
                             @RequestParam(defaultValue = "5") int pageSize) {
        return diaryService.pageByUser(authContext.requireUserId(), pageNum, Math.min(pageSize, 50));
    }

    @GetMapping("/pending")
    public List<Diary> pending() {
        return diaryService.pendingReviews(authContext.requireUserId());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return diaryService.getStats(authContext.requireUserId());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return diaryService.detail(authContext.requireUserId(), id);
    }

    @PostMapping("/{id}/review")
    public Diary review(@PathVariable Long id,
                        @RequestBody Map<String, Object> body) {
        Boolean triggered = asBoolean(body.get("triggered"));
        Boolean executed = asBoolean(body.get("executed"));
        String resultTag = body.get("resultTag") == null ? null : String.valueOf(body.get("resultTag"));
        String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
        return diaryService.review(id, authContext.requireUserId(), triggered, executed, resultTag, note);
    }

    @PostMapping("/{id}/coach")
    public Map<String, String> coach(@PathVariable Long id) {
        return Map.of("review", diaryService.coach(id, authContext.requireUserId()));
    }

    @GetMapping("/options")
    public Map<String, Object> options() {
        return Map.of("reasonTags", DiaryService.REASON_TAGS);
    }

    private Boolean asBoolean(Object raw) {
        if (raw instanceof Boolean b) return b;
        return raw != null && Boolean.parseBoolean(String.valueOf(raw));
    }
}
