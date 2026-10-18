package com.wargame.model.dto;

import java.util.List;

/** 仅公开游戏资料，不返回登录、经济明细或账号安全字段。 */
public final class FriendDtos {
    private FriendDtos() {}
    public record Group(Long id, String name) {}
    public record GroupRequest(String name) {}
    public record ContactRequest(Long groupId) {}
    public record Settings(String friendRequestPolicy, boolean blockStrangerMessages) {}
    public record Request(Long id, Profile peer, boolean outgoing, Long createdAt) {}
    public record AddResult(Profile profile, boolean pending) {}
    public record Profile(Long id, String username, String displayName, String avatar, Integer level,
                          Integer militaryRank, Integer prestige, String cityName, String faction,
                          boolean online, boolean available, boolean friend, boolean blocked, Long groupId,
                          String requestState) {}
    public record ListResponse(List<Group> groups, List<Profile> contacts, List<Request> requests, Settings settings) {}
}
