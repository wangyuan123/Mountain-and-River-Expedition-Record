package com.wargame.service;

import com.wargame.model.constants.CommanderRenamePolicy;
import com.wargame.model.constants.DisplayNamePolicy;
import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.service.quest.QuestService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommanderRenameService {
    private final PlayerRepository players;
    private final PlayerItemRepository items;
    private final QuestService quests;
    private final AccountService accounts;

    public CommanderRenameService(PlayerRepository players, PlayerItemRepository items, QuestService quests, AccountService accounts) {
        this.players = players;
        this.items = items;
        this.quests = quests;
        this.accounts = accounts;
    }

    /** 所有玩家改名入口共用本事务；唯一冲突、冷却和缺卡失败都回滚，不扣卡。 */
    @Transactional
    public String rename(Long playerId, String rawName) {
        String name = DisplayNamePolicy.validate(rawName, 8, "统帅名");
        Player player = accounts.lockPlayer(playerId);
        String oldName = player.getDisplayName() == null || player.getDisplayName().isBlank()
                ? player.getUsername() : player.getDisplayName();
        String key = CommanderRenamePolicy.nameKey(name);
        if (key.equals(CommanderRenamePolicy.nameKey(oldName)))
            throw new IllegalArgumentException("新名称与当前统帅名相同");
        if (players.existsByDisplayNameKeyAndIdNot(key, playerId))
            throw new IllegalArgumentException("名称不能重复请重新修改");
        long now = System.currentTimeMillis();
        long availableAt = CommanderRenamePolicy.nextAllowedAt(player.getCommanderRenamedAt());
        if (availableAt > now)
            throw new IllegalArgumentException("统帅改名需间隔30天，可再次改名时间：" +
                    java.time.Instant.ofEpochMilli(availableAt).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "（北京时间）");
        if (items.tryConsume(playerId, CommanderRenamePolicy.ITEM_KEY, 1, now) == 0)
            throw new IllegalArgumentException("统帅改名卡不足，请先领取邮件附件或查看仓库");
        // 扣卡会清空持久化上下文，必须重新取回已锁定的玩家再更新。
        player = players.findById(playerId).orElseThrow();
        player.setDisplayName(name);
        player.setCommanderRenamedAt(now);
        player.setCommanderRenameCount(player.getCommanderRenameCount() + 1);
        try {
            players.saveAndFlush(player);
        } catch (DataIntegrityViolationException conflict) {
            throw new IllegalArgumentException("名称不能重复请重新修改", conflict);
        }
        quests.onEvent(playerId, "DISPLAY_NAME_CHANGE", null, 1);
        return name;
    }
}
