package com.wargame.service;

import com.wargame.model.entity.Player;
import com.wargame.repository.*;
import com.wargame.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号服务：负责注销账号、清理附属数据与撤销会话。
 *
 * 设计原则：
 * - 软删除：在 players 表上设置 disabled=1 + disabled_at，保留主键以兼容外键
 * - 7 天宽限期内玩家可登录恢复（复用登录入口的恢复逻辑）
 * - 注销前强制验证当前密码
 * - 注销后立即吊销当前会话（jti 加入撤销表）
 * - 7 天后再次登录将彻底清理附属数据
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final PlayerRepository playerRepository;
    private final PlayerCityRepository playerCityRepository;
    private final BuildingRepository buildingRepository;
    private final ArmyUnitRepository armyUnitRepository;
    private final WoundedUnitRepository woundedUnitRepository;
    private final FortificationRepository fortificationRepository;
    private final TechnologyRepository technologyRepository;
    private final OfficerRepository officerRepository;
    private final ConstructionRepository constructionRepository;
    private final ResourcesRepository resourcesRepository;
    private final CityStateRepository cityStateRepository;
    private final AcademyRepository academyRepository;
    private final PlayerItemRepository playerItemRepository;
    private final MailRepository mailRepository;
    private final ScoutReportRepository scoutReportRepository;
    private final PlayerQuestRepository playerQuestRepository;
    private final PlayerGuideRepository playerGuideRepository;
    private final MarchRepository marchRepository;
    private final IncomingMarchRepository incomingMarchRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Value("${game.account.cooldown-days:7}")
    private int cooldownDays;

    /** 注销宽限期天数（供登录入口读取） */
    public int getCooldownDays() {
        return cooldownDays;
    }

    public AccountService(PlayerRepository playerRepository,
                          PlayerCityRepository playerCityRepository,
                          BuildingRepository buildingRepository,
                          ArmyUnitRepository armyUnitRepository, WoundedUnitRepository woundedUnitRepository,
                          FortificationRepository fortificationRepository,
                          TechnologyRepository technologyRepository,
                          OfficerRepository officerRepository,
                          ConstructionRepository constructionRepository,
                          ResourcesRepository resourcesRepository,
                          CityStateRepository cityStateRepository,
                          AcademyRepository academyRepository,
                          PlayerItemRepository playerItemRepository,
                          MailRepository mailRepository,
                          ScoutReportRepository scoutReportRepository,
                          PlayerQuestRepository playerQuestRepository,
                          PlayerGuideRepository playerGuideRepository,
                          MarchRepository marchRepository,
                          IncomingMarchRepository incomingMarchRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil) {
        this.playerRepository = playerRepository;
        this.playerCityRepository = playerCityRepository;
        this.buildingRepository = buildingRepository;
        this.armyUnitRepository = armyUnitRepository;
        this.woundedUnitRepository = woundedUnitRepository;
        this.fortificationRepository = fortificationRepository;
        this.technologyRepository = technologyRepository;
        this.officerRepository = officerRepository;
        this.constructionRepository = constructionRepository;
        this.resourcesRepository = resourcesRepository;
        this.cityStateRepository = cityStateRepository;
        this.academyRepository = academyRepository;
        this.playerItemRepository = playerItemRepository;
        this.mailRepository = mailRepository;
        this.scoutReportRepository = scoutReportRepository;
        this.playerQuestRepository = playerQuestRepository;
        this.playerGuideRepository = playerGuideRepository;
        this.marchRepository = marchRepository;
        this.incomingMarchRepository = incomingMarchRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 发起注销。软标记账号、吊销当前会话。
     * 不会立即删除附属数据，等待 7 天宽限期结束后由登录入口兜底清理。
     */
    @Transactional
    public Map<String, Object> disableAccount(Long playerId, String password, String currentToken) {
        if (playerId == null) {
            throw new IllegalArgumentException("未登录");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("请输入当前密码以确认注销");
        }

        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));

        if (player.getDisabled() != null && player.getDisabled() == 1) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("alreadyDisabled", true);
            r.put("disabledAt", player.getDisabledAt());
            r.put("cooldownDays", cooldownDays);
            r.put("message", "该账号已处于注销状态");
            return r;
        }

        if (player.getPasswordHash() == null || !passwordEncoder.matches(password, player.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码不正确");
        }

        long now = System.currentTimeMillis();
        player.setDisabled(1);
        player.setDisabledAt(now);
        playerRepository.save(player);

        // 吊销当前 Token，避免同会话继续操作
        if (currentToken != null && !currentToken.isBlank()) {
            jwtUtil.revoke(currentToken);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("disabledAt", now);
        result.put("recoverUntil", now + cooldownDays * 86_400_000L);
        result.put("cooldownDays", cooldownDays);
        result.put("message", "账号已注销，7 天内登录可恢复");
        log.info("[Account] player {} disabled, recoverable until {}", playerId,
                result.get("recoverUntil"));
        return result;
    }

    /**
     * 检查并处理宽限期内的恢复：登录时如果账号处于 disabled=1 但仍在宽限期内，
     * 自动恢复正常状态并清空 disabledAt。
     */
    @Transactional
    public void recoverIfWithinCooldown(Player player) {
        if (player.getDisabled() == null || player.getDisabled() != 1) return;
        long disabledAt = player.getDisabledAt() == null ? 0L : player.getDisabledAt();
        long deadline = disabledAt + cooldownDays * 86_400_000L;
        if (System.currentTimeMillis() < deadline) {
            player.setDisabled(0);
            player.setDisabledAt(0L);
            playerRepository.save(player);
            log.info("[Account] player {} recovered from disabled state", player.getId());
        }
    }

    /**
     * 永久清理宽限期已过的账号所有附属数据。
     * 主表 players 仍保留以兼容引用，但其中资源/兵种等都已置零。
     */
    @Transactional
    public void purgeExpiredAccount(Player player) {
        Long playerId = player.getId();
        log.info("[Account] purging expired account {}", playerId);

        buildingRepository.deleteByPlayerId(playerId);
        armyUnitRepository.deleteByPlayerId(playerId);
        woundedUnitRepository.deleteByPlayerId(playerId);
        fortificationRepository.deleteByPlayerId(playerId);
        technologyRepository.deleteByPlayerId(playerId);
        officerRepository.deleteByPlayerId(playerId);
        constructionRepository.deleteByPlayerId(playerId);
        resourcesRepository.deleteByPlayerId(playerId);
        cityStateRepository.deleteByPlayerId(playerId);
        academyRepository.deleteByPlayerId(playerId);
        playerItemRepository.deleteByPlayerId(playerId);
        mailRepository.deleteByToPlayerId(playerId);
        mailRepository.deleteByFromPlayerId(playerId);
        scoutReportRepository.deleteByPlayerId(playerId);
        playerQuestRepository.deleteByPlayerId(playerId);
        playerGuideRepository.deleteByPlayerId(playerId);
        marchRepository.deleteByPlayerId(playerId);
        incomingMarchRepository.deleteByTargetPlayerId(playerId);
        playerCityRepository.findByOwnerId(playerId).forEach(c -> playerCityRepository.deleteById(c.getId()));
    }
}
