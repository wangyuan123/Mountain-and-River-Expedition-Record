package com.wargame.service;

import com.wargame.model.entity.Guild;
import com.wargame.model.entity.GuildApplication;
import com.wargame.model.entity.GuildMember;
import com.wargame.model.entity.Player;
import com.wargame.repository.GuildApplicationRepository;
import com.wargame.repository.GuildMemberRepository;
import com.wargame.repository.GuildRepository;
import com.wargame.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GuildService {

    private static final int MAX_MEMBERS = 30;
    private static final String LEADER = "leader";
    private static final String ADMIN = "admin";

    private final GuildRepository guildRepository;
    private final GuildMemberRepository guildMemberRepository;
    private final GuildApplicationRepository guildApplicationRepository;
    private final PlayerRepository playerRepository;
    private final MailService mailService;

    public GuildService(GuildRepository guildRepository,
                        GuildMemberRepository guildMemberRepository,
                        GuildApplicationRepository guildApplicationRepository,
                        PlayerRepository playerRepository,
                        MailService mailService) {
        this.guildRepository = guildRepository;
        this.guildMemberRepository = guildMemberRepository;
        this.guildApplicationRepository = guildApplicationRepository;
        this.playerRepository = playerRepository;
        this.mailService = mailService;
    }

    public Map<String, Object> getMyGuild(Long playerId) {
        return guildMemberRepository.findByPlayerId(playerId)
                .map(member -> detail(member.getGuildId(), playerId))
                .orElseGet(() -> Map.of("joined", false));
    }

    @Transactional
    public Map<String, Object> create(Long playerId, String rawName) {
        requireNoGuild(playerId);
        String name = normalizeName(rawName);
        if (guildRepository.existsByName(name)) throw new IllegalArgumentException("军团名称已被占用");
        long now = System.currentTimeMillis();
        Guild guild = new Guild();
        guild.setName(name);
        guild.setNotice("欢迎加入「" + name + "」");
        guild.setIcon("⚑");
        guild.setLeaderPlayerId(playerId);
        guild.setCreatedAt(now);
        guild = guildRepository.save(guild);
        guildMemberRepository.save(new GuildMember(null, guild.getId(), playerId, "leader", now));
        return detail(guild.getId(), playerId);
    }

    @Transactional
    public Map<String, Object> apply(Long playerId, Long guildId) {
        requireNoGuild(playerId);
        Guild guild = guildRepository.findById(guildId).orElseThrow(() -> new IllegalArgumentException("军团不存在"));
        if (guildMemberRepository.countByGuildId(guildId) >= MAX_MEMBERS) throw new IllegalArgumentException("军团人数已满");
        if (guildApplicationRepository.findByGuildIdAndPlayerId(guildId, playerId).isPresent()) throw new IllegalArgumentException("已提交申请，请等待审核");
        guildApplicationRepository.save(new GuildApplication(null, guildId, playerId, System.currentTimeMillis()));
        Player applicant = player(playerId);
        mailService.sendSystem(guild.getLeaderPlayerId(), "军团系统", "alliance", "新的入团申请", applicant.getUsername() + " 申请加入军团「" + guild.getName() + "」。", List.of());
        return Map.of("success", true, "message", "申请已发送");
    }

    @Transactional
    public Map<String, Object> review(Long leaderId, Long applicationId, boolean approved) {
        GuildApplication application = guildApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在或已处理"));
        Guild guild = requireManager(leaderId, application.getGuildId());
        Player applicant = player(application.getPlayerId());
        guildApplicationRepository.delete(application);
        if (!approved) {
            mailService.sendSystem(applicant.getId(), "军团系统", "alliance", "入团申请结果", "军团「" + guild.getName() + "」拒绝了你的申请。", List.of());
            return Map.of("success", true, "message", "已拒绝申请");
        }
        if (guildMemberRepository.findByPlayerId(applicant.getId()).isPresent()) throw new IllegalArgumentException("该玩家已加入其他军团");
        if (guildMemberRepository.countByGuildId(guild.getId()) >= MAX_MEMBERS) throw new IllegalArgumentException("军团人数已满");
        guildMemberRepository.save(new GuildMember(null, guild.getId(), applicant.getId(), "member", System.currentTimeMillis()));
        mailService.sendSystem(applicant.getId(), "军团系统", "alliance", "欢迎加入军团", "你已加入军团「" + guild.getName() + "」。", List.of());
        return Map.of("success", true, "message", "已同意申请");
    }

    @Transactional
    public Map<String, Object> updateNotice(Long playerId, String notice) {
        GuildMember member = guildMemberRepository.findByPlayerId(playerId).orElseThrow(() -> new IllegalArgumentException("尚未加入军团"));
        Guild guild = requireManager(playerId, member.getGuildId());
        String text = notice == null ? "" : notice.trim();
        if (text.length() > 200) throw new IllegalArgumentException("公告不能超过 200 字");
        guild.setNotice(text);
        guildRepository.save(guild);
        return detail(guild.getId(), playerId);
    }

    @Transactional
    public Map<String, Object> updateSettings(Long playerId, String name, String icon) {
        GuildMember member = guildMemberRepository.findByPlayerId(playerId).orElseThrow(() -> new IllegalArgumentException("尚未加入军团"));
        Guild guild = requireManager(playerId, member.getGuildId());
        String newName = normalizeName(name);
        if (!newName.equals(guild.getName()) && guildRepository.existsByName(newName)) throw new IllegalArgumentException("军团名称已被占用");
        String newIcon = icon == null ? "⚑" : icon.trim();
        if (newIcon.length() < 1 || newIcon.length() > 4) throw new IllegalArgumentException("军团图标需为1-4个字符");
        guild.setName(newName);
        guild.setIcon(newIcon);
        guildRepository.save(guild);
        return detail(guild.getId(), playerId);
    }

    @Transactional
    public Map<String, Object> updateRole(Long playerId, Long targetPlayerId, String role) {
        GuildMember operator = guildMemberRepository.findByPlayerId(playerId).orElseThrow(() -> new IllegalArgumentException("尚未加入军团"));
        Guild guild = requireLeader(playerId, operator.getGuildId());
        if (!ADMIN.equals(role) && !"member".equals(role)) throw new IllegalArgumentException("无效的成员角色");
        GuildMember target = guildMemberRepository.findByPlayerId(targetPlayerId).orElseThrow(() -> new IllegalArgumentException("成员不存在"));
        if (!guild.getId().equals(target.getGuildId()) || targetPlayerId.equals(playerId)) throw new IllegalArgumentException("无法修改该成员角色");
        target.setRole(role);
        guildMemberRepository.save(target);
        return detail(guild.getId(), playerId);
    }

    @Transactional
    public Map<String, Object> removeMember(Long leaderId, Long targetPlayerId) {
        GuildMember leader = guildMemberRepository.findByPlayerId(leaderId).orElseThrow(() -> new IllegalArgumentException("尚未加入军团"));
        Guild guild = requireManager(leaderId, leader.getGuildId());
        GuildMember target = guildMemberRepository.findByPlayerId(targetPlayerId).orElseThrow(() -> new IllegalArgumentException("成员不存在"));
        if (!target.getGuildId().equals(guild.getId()) || "leader".equals(target.getRole())) throw new IllegalArgumentException("无法移除此成员");
        guildMemberRepository.delete(target);
        mailService.sendSystem(targetPlayerId, "军团系统", "alliance", "已被移出军团", "你已被移出军团「" + guild.getName() + "」。", List.of());
        return Map.of("success", true, "message", "已移出成员");
    }

    @Transactional
    public Map<String, Object> leave(Long playerId) {
        GuildMember member = guildMemberRepository.findByPlayerId(playerId).orElseThrow(() -> new IllegalArgumentException("尚未加入军团"));
        Guild guild = guildRepository.findById(member.getGuildId()).orElseThrow(() -> new IllegalArgumentException("军团不存在"));
        if ("leader".equals(member.getRole())) {
            List<GuildMember> members = guildMemberRepository.findByGuildIdOrderByJoinedAtAsc(guild.getId());
            if (members.size() > 1) throw new IllegalArgumentException("请先移交团长或移出全部成员");
            guildMemberRepository.delete(member);
            guildRepository.delete(guild);
            return Map.of("success", true, "message", "军团已解散");
        }
        guildMemberRepository.delete(member);
        return Map.of("success", true, "message", "已退出军团");
    }

    public List<Map<String, Object>> browse() {
        List<Guild> guilds = guildRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Guild guild : guilds) result.add(summary(guild));
        result.sort(Comparator.comparingLong(m -> -((Number) m.get("prestige")).longValue()));
        return result;
    }

    private Map<String, Object> detail(Long guildId, Long viewerId) {
        Guild guild = guildRepository.findById(guildId).orElseThrow(() -> new IllegalArgumentException("军团不存在"));
        Map<String, Object> result = summary(guild);
        GuildMember viewer = guildMemberRepository.findByPlayerId(viewerId).orElse(null);
        result.put("joined", viewer != null);
        result.put("role", viewer != null ? viewer.getRole() : null);
        result.put("isLeader", viewer != null && LEADER.equals(viewer.getRole()));
        result.put("isManager", isManager(viewer));
        List<Map<String, Object>> members = new ArrayList<>();
        for (GuildMember member : guildMemberRepository.findByGuildIdOrderByJoinedAtAsc(guildId)) {
            Player p = player(member.getPlayerId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("playerId", p.getId()); item.put("name", p.getUsername()); item.put("cityName", p.getCityName());
            item.put("prestige", p.getPrestige()); item.put("role", member.getRole());
            members.add(item);
        }
        result.put("members", members);
        if (isManager(viewer)) {
            List<Map<String, Object>> applications = new ArrayList<>();
            for (GuildApplication app : guildApplicationRepository.findByGuildIdOrderByCreatedAtAsc(guildId)) {
                Player p = player(app.getPlayerId());
                applications.add(Map.of("id", app.getId(), "playerId", p.getId(), "name", p.getUsername(), "prestige", p.getPrestige()));
            }
            result.put("applications", applications);
        }
        return result;
    }

    private Map<String, Object> summary(Guild guild) {
        long prestige = 0;
        for (GuildMember member : guildMemberRepository.findByGuildIdOrderByJoinedAtAsc(guild.getId())) prestige += player(member.getPlayerId()).getPrestige() == null ? 0 : player(member.getPlayerId()).getPrestige();
        Player leader = player(guild.getLeaderPlayerId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", guild.getId()); result.put("name", guild.getName()); result.put("icon", guild.getIcon()); result.put("notice", guild.getNotice());
        result.put("leaderName", leader.getUsername()); result.put("members", guildMemberRepository.countByGuildId(guild.getId()));
        result.put("maxMembers", MAX_MEMBERS); result.put("prestige", prestige); result.put("createdAt", guild.getCreatedAt());
        return result;
    }

    private void requireNoGuild(Long playerId) { if (guildMemberRepository.findByPlayerId(playerId).isPresent()) throw new IllegalArgumentException("你已加入军团"); }
    private Guild requireLeader(Long playerId, Long guildId) { Guild guild = guildRepository.findById(guildId).orElseThrow(() -> new IllegalArgumentException("军团不存在")); if (!guild.getLeaderPlayerId().equals(playerId)) throw new IllegalArgumentException("只有团长可以执行此操作"); return guild; }
    private Guild requireManager(Long playerId, Long guildId) {
        Guild guild = guildRepository.findById(guildId)
                .orElseThrow(() -> new IllegalArgumentException("军团不存在"));
        GuildMember member = guildMemberRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalArgumentException("尚未加入军团"));
        if (!guild.getId().equals(member.getGuildId())) {
            throw new IllegalArgumentException("成员不属于该军团");
        }
        if (!isManager(member)) {
            throw new IllegalArgumentException("只有团长或管理员可以执行此操作");
        }
        return guild;
    }
    private boolean isManager(GuildMember member) { return member != null && (LEADER.equals(member.getRole()) || ADMIN.equals(member.getRole())); }
    private Player player(Long playerId) { return playerRepository.findById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在")); }
    private String normalizeName(String name) { String value = name == null ? "" : name.trim(); if (value.length() < 2 || value.length() > 16) throw new IllegalArgumentException("军团名称需为 2-16 个字符"); return value; }
}
