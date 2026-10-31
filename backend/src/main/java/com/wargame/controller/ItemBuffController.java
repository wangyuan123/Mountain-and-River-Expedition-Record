package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.ItemBuffService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 增益列表不接受目标账号参数，城市选择也不缩小账号级汇总范围。 */
@RestController
@RequestMapping("/api/game/item-buffs")
@RequiredArgsConstructor
public class ItemBuffController {
    private final AuthService auth;
    private final ItemBuffService buffs;

    @GetMapping
    public ItemBuffService.Snapshot list() {
        return buffs.snapshot(auth.getCurrentPlayer().getId());
    }
}
