package com.wargame.controller.admin;

import com.wargame.service.admin.AdminGmService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/gm")
public class AdminGmController {

    private final AdminGmService adminGmService;

    public AdminGmController(AdminGmService adminGmService) {
        this.adminGmService = adminGmService;
    }

    @PostMapping("/mail-compensation")
    public Map<String, Object> sendCompensation(@RequestBody Map<String, Object> body,
                                                Authentication authentication,
                                                HttpServletRequest request) {
        String targetType = (String) body.get("targetType"); // ALL or SINGLE
        Long targetPlayerId = body.containsKey("targetPlayerId") && body.get("targetPlayerId") != null
                ? ((Number) body.get("targetPlayerId")).longValue() : null;
        String title = (String) body.get("title");
        String content = (String) body.get("content");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) body.get("attachments");

        return adminGmService.sendCompensationMail(targetType, targetPlayerId, title, content, attachments, authentication.getName(), request);
    }

    @PostMapping("/command")
    public Map<String, Object> command(@RequestBody Map<String, String> body,
                                       Authentication authentication,
                                       HttpServletRequest request) {
        String cmd = body.get("command");
        return adminGmService.executeCommand(cmd, authentication.getName(), request);
    }
}
