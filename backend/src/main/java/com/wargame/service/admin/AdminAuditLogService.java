package com.wargame.service.admin;

import com.wargame.model.entity.AdminOperationLog;
import com.wargame.repository.AdminOperationLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditLogService {

    private final AdminOperationLogRepository logRepository;
    private final com.wargame.security.ClientIpResolver clientIps;

    public AdminAuditLogService(AdminOperationLogRepository logRepository, com.wargame.security.ClientIpResolver clientIps) {
        this.logRepository = logRepository;
        this.clientIps = clientIps;
    }

    @Transactional
    public void record(String adminUsername, String actionType, String targetType, String targetId, String detail, HttpServletRequest request) {
        String ip = clientIps.resolve(request);
        AdminOperationLog log = new AdminOperationLog();
        log.setAdminUsername(adminUsername != null ? adminUsername : "system");
        log.setActionType(actionType);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail);
        log.setIpAddress(ip);
        log.setCreatedAt(System.currentTimeMillis());
        logRepository.save(log);
    }

    public Page<AdminOperationLog> queryLogs(String adminUsername, String actionType, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<AdminOperationLog> spec = Specification.where(null);
        if (adminUsername != null && !adminUsername.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("adminUsername"), adminUsername.trim()));
        }
        if (actionType != null && !actionType.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("actionType"), actionType.trim()));
        }
        return logRepository.findAll(spec, pageRequest);
    }
}
