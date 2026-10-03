package com.wargame.service.admin;

import com.wargame.model.entity.Player;
import com.wargame.model.entity.Resources;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.ResourcesRepository;
import com.wargame.service.MailService;
import com.wargame.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AdminGmService {

    private final MailService mailService;
    private final PlayerRepository playerRepository;
    private final ResourcesRepository resourcesRepository;
    private final AdminPlayerService adminPlayerService;
    private final AdminAuditLogService auditLogService;

    public AdminGmService(MailService mailService,
                          PlayerRepository playerRepository,
                          ResourcesRepository resourcesRepository,
                          AdminPlayerService adminPlayerService,
                          AdminAuditLogService auditLogService) {
        this.mailService = mailService;
        this.playerRepository = playerRepository;
        this.resourcesRepository = resourcesRepository;
        this.adminPlayerService = adminPlayerService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public Map<String, Object> sendCompensationMail(String targetType,
                                                   Long targetPlayerId,
                                                   String title,
                                                   String body,
                                                   List<Map<String, Object>> attachments,
                                                   String adminUsername,
                                                   HttpServletRequest req) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("邮件标题不能为空");
        }
        List<Map<String, Object>> safeAttach = attachments != null ? attachments : List.of();
        int sentCount = 0;

        if ("ALL".equalsIgnoreCase(targetType)) {
            List<Player> allPlayers = playerRepository.findAll();
            for (Player p : allPlayers) {
                if (p.accountActive()) {
                    mailService.sendSystem(p.getId(), "战备指挥部", "reward", title, body, safeAttach);
                    sentCount++;
                }
            }
            auditLogService.record(adminUsername, "SEND_COMPENSATION_ALL", "MAIL", "ALL",
                    String.format("向全服 %d 名活跃玩家发送补偿邮件: %s, 附件:%s", sentCount, title, JsonUtil.toJson(safeAttach)), req);
        } else {
            if (targetPlayerId == null) {
                throw new IllegalArgumentException("请指定目标玩家 ID");
            }
            Player target = playerRepository.findById(targetPlayerId)
                    .orElseThrow(() -> new IllegalArgumentException("目标玩家不存在: " + targetPlayerId));
            mailService.sendSystem(target.getId(), "战备指挥部", "reward", title, body, safeAttach);
            sentCount = 1;
            auditLogService.record(adminUsername, "SEND_COMPENSATION_SINGLE", "MAIL", String.valueOf(targetPlayerId),
                    String.format("向玩家 %s(ID:%d) 发送补偿邮件: %s, 附件:%s", target.getUsername(), targetPlayerId, title, JsonUtil.toJson(safeAttach)), req);
        }

        return Map.of(
                "success", true,
                "sentCount", sentCount,
                "message", String.format("成功向 %d 名玩家发放邮件及附件", sentCount)
        );
    }

    @Transactional
    public Map<String, Object> executeCommand(String commandLine, String adminUsername, HttpServletRequest req) {
        if (commandLine == null || commandLine.isBlank()) {
            throw new IllegalArgumentException("GM 指令不能为空");
        }
        String cmd = commandLine.trim();
        String[] parts = cmd.split("\\s+");
        String op = parts[0].toLowerCase();

        String result;
        try {
            switch (op) {
                case "/add_gold":
                case "/add_diamond":
                case "/add_food":
                case "/add_steel":
                case "/add_oil":
                case "/add_rare": {
                    if (parts.length < 3) throw new IllegalArgumentException("格式错误，示例: " + op + " [玩家ID] [数量]");
                    Long pid = Long.parseLong(parts[1]);
                    int amount = Integer.parseInt(parts[2]);
                    String resType = op.substring(5); // gold, diamond, food, steel, oil, rare
                    Resources res = resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(pid)
                            .orElseThrow(() -> new IllegalArgumentException("玩家资源数据不存在"));
                    if ("gold".equals(resType)) res.setGold((res.getGold() != null ? res.getGold() : 0) + amount);
                    else if ("diamond".equals(resType)) res.setDiamond((res.getDiamond() != null ? res.getDiamond() : 0) + amount);
                    else if ("food".equals(resType)) res.setFood((res.getFood() != null ? res.getFood() : 0) + amount);
                    else if ("steel".equals(resType)) res.setSteel((res.getSteel() != null ? res.getSteel() : 0) + amount);
                    else if ("oil".equals(resType)) res.setOil((res.getOil() != null ? res.getOil() : 0) + amount);
                    else if ("rare".equals(resType)) res.setRare((res.getRare() != null ? res.getRare() : 0) + amount);
                    resourcesRepository.save(res);
                    result = String.format("成功为玩家 %d 增加 %s: %d", pid, resType, amount);
                    break;
                }
                case "/set_level": {
                    if (parts.length < 3) throw new IllegalArgumentException("格式错误，示例: /set_level [玩家ID] [等级]");
                    Long pid = Long.parseLong(parts[1]);
                    int level = Integer.parseInt(parts[2]);
                    Player player = playerRepository.findById(pid)
                            .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
                    player.setLevel(level);
                    playerRepository.save(player);
                    result = String.format("成功将玩家 %d 的等级设置为 %d", pid, level);
                    break;
                }
                case "/add_item": {
                    if (parts.length < 4) throw new IllegalArgumentException("格式错误，示例: /add_item [玩家ID] [道具Key] [数量]");
                    Long pid = Long.parseLong(parts[1]);
                    String itemKey = parts[2];
                    int count = Integer.parseInt(parts[3]);
                    adminPlayerService.updatePlayerItem(pid, itemKey, count, adminUsername, req);
                    result = String.format("成功为玩家 %d 调整道具 %s 数量为 %d", pid, itemKey, count);
                    break;
                }
                case "/add_army": {
                    if (parts.length < 4) throw new IllegalArgumentException("格式错误，示例: /add_army [玩家ID] [兵种] [数量]");
                    Long pid = Long.parseLong(parts[1]);
                    String armyType = parts[2];
                    int count = Integer.parseInt(parts[3]);
                    adminPlayerService.updatePlayerArmy(pid, armyType, count, adminUsername, req);
                    result = String.format("成功为玩家 %d 调整兵力 %s 数量为 %d", pid, armyType, count);
                    break;
                }
                case "/ban": {
                    if (parts.length < 2) throw new IllegalArgumentException("格式错误，示例: /ban [玩家ID] [原因]");
                    Long pid = Long.parseLong(parts[1]);
                    String reason = parts.length > 2 ? parts[2] : "GM 封号指令";
                    adminPlayerService.banPlayer(pid, reason, 0L, adminUsername, req);
                    result = String.format("已封禁玩家 %d", pid);
                    break;
                }
                case "/unban": {
                    if (parts.length < 2) throw new IllegalArgumentException("格式错误，示例: /unban [玩家ID]");
                    Long pid = Long.parseLong(parts[1]);
                    adminPlayerService.unbanPlayer(pid, adminUsername, req);
                    result = String.format("已解封玩家 %d", pid);
                    break;
                }
                default:
                    throw new IllegalArgumentException("未知的 GM 指令: " + op + "。可用: /add_gold, /add_diamond, /add_food, /add_steel, /add_oil, /add_rare, /set_level, /add_item, /add_army, /ban, /unban");
            }
        } catch (Exception e) {
            result = "执行失败: " + e.getMessage();
        }

        auditLogService.record(adminUsername, "GM_COMMAND", "SYSTEM", "CONSOLE",
                String.format("执行指令: %s -> 结果: %s", commandLine, result), req);

        return Map.of("command", commandLine, "result", result);
    }
}
