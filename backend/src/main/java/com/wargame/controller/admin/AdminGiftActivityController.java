package com.wargame.controller.admin;
import com.wargame.service.MailGiftActivityService;
import com.wargame.service.admin.AdminAuditLogService;
import com.wargame.model.entity.MailGiftActivity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 管理员显式发布活动，不内置任何真实节日和奖励。沿用 /api/admin 的权限限制。 */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/gm/gift-activities")
public class AdminGiftActivityController {
    private final MailGiftActivityService activities;
    private final AdminAuditLogService audit;
    public record Create(String id, long startsAt, long endsAt, boolean newAccountsOnly, String subject, String body, List<Map<String, Object>> rewards) {}

    @PostMapping
    public MailGiftActivity create(@RequestBody Create request, Authentication auth, HttpServletRequest servlet) {
        var activity = activities.create(request.id(), request.startsAt(), request.endsAt(), request.newAccountsOnly(), request.subject(), request.body(), request.rewards());
        audit.record(auth.getName(), "GIFT_ACTIVITY_CREATE", "GIFT_ACTIVITY", activity.getId(), "发布登录赠礼活动，领取截止=" + activity.getClaimExpiresAt(), servlet);
        return activity;
    }

    @PostMapping("/{id}/close")
    public Map<String, Object> close(@PathVariable String id, Authentication auth, HttpServletRequest servlet) {
        activities.close(id);
        audit.record(auth.getName(), "GIFT_ACTIVITY_CLOSE", "GIFT_ACTIVITY", id, "关闭活动发放，已发放附件期限保持原约定", servlet);
        return Map.of("success", true);
    }
}
