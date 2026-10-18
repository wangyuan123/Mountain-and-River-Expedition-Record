package com.wargame.controller;

import com.wargame.model.dto.FriendDtos;
import com.wargame.service.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 所有者始终取自登录会话，客户端不能指定其他账号的好友列表或分组。 */
@RestController
@RequestMapping("/api/game/friends")
public class FriendController {
    private final FriendService friends;
    private final AuthService auth;
    public FriendController(FriendService friends, AuthService auth) { this.friends = friends; this.auth = auth; }
    private Long me() { return auth.getCurrentPlayer().getId(); }
    @GetMapping public FriendDtos.ListResponse list() { return friends.list(me()); }
    @GetMapping("/search") public FriendDtos.Profile search(@RequestParam String query) { return friends.search(me(), query); }
    @GetMapping("/{peerId}") public FriendDtos.Profile profile(@PathVariable Long peerId) { return friends.profile(me(), peerId); }
    @PostMapping("/{peerId}") public FriendDtos.AddResult add(@PathVariable Long peerId, @RequestBody FriendDtos.ContactRequest request) {
        return friends.add(me(), peerId, request.groupId());
    }
    @DeleteMapping("/{peerId}") public Map<String, Object> remove(@PathVariable Long peerId) {
        friends.remove(me(), peerId); return Map.of("success", true);
    }
    @PutMapping("/{peerId}/group") public FriendDtos.Profile move(@PathVariable Long peerId, @RequestBody FriendDtos.ContactRequest request) {
        return friends.move(me(), peerId, request.groupId());
    }
    @PostMapping("/{peerId}/block") public FriendDtos.Profile block(@PathVariable Long peerId) { return friends.block(me(), peerId, true); }
    @DeleteMapping("/{peerId}/block") public FriendDtos.Profile unblock(@PathVariable Long peerId) { return friends.block(me(), peerId, false); }
    @PostMapping("/groups") public FriendDtos.Group createGroup(@RequestBody FriendDtos.GroupRequest request) {
        return friends.saveGroup(me(), null, request.name());
    }
    @PutMapping("/groups/{id}") public FriendDtos.Group renameGroup(@PathVariable Long id, @RequestBody FriendDtos.GroupRequest request) {
        return friends.saveGroup(me(), id, request.name());
    }
    @DeleteMapping("/groups/{id}") public Map<String, Object> deleteGroup(@PathVariable Long id) {
        friends.deleteGroup(me(), id); return Map.of("success", true);
    }
    @PostMapping("/requests/{id}/accept") public Map<String, Object> accept(@PathVariable Long id) {
        friends.respond(me(), id, true); return Map.of("success", true);
    }
    @DeleteMapping("/requests/{id}") public Map<String, Object> reject(@PathVariable Long id) {
        friends.respond(me(), id, false); return Map.of("success", true);
    }
    @PutMapping("/settings") public FriendDtos.Settings settings(@RequestBody FriendDtos.Settings request) {
        return friends.updateSettings(me(), request);
    }
}
