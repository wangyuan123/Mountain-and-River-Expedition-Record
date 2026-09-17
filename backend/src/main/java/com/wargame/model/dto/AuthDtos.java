package com.wargame.model.dto;

public class AuthDtos {

    public record RegisterRequest(String username, String password) {}

    public record LoginRequest(String username, String password) {}

    public record AuthResponse(String token, String username, Long playerId) {}

    public record UserInfoResponse(Long playerId, String username, String faction, String cityName) {}

    /** 注销账号请求：需要玩家当前密码做最终确认 */
    public record DisableAccountRequest(String password, String confirm, String requestId) {}
    public record RecoverAccountRequest(String recoveryToken, boolean confirm) {}
}
