package com.wargame.controller.admin;

import com.wargame.model.entity.AdminOperationLog;
import com.wargame.service.admin.AdminAuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/logs")
public class AdminAuditLogController {

    private final AdminAuditLogService auditLogService;

    public AdminAuditLogController(AdminAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public Page<AdminOperationLog> list(@RequestParam(required = false) String adminUsername,
                                        @RequestParam(required = false) String actionType,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return auditLogService.queryLogs(adminUsername, actionType, page, size);
    }
}
