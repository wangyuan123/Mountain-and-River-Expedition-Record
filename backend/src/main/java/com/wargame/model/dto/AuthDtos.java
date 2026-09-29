package com.wargame.model.dto;

public class AuthDtos {

    /** 注册时必须明确确认当前发布版本的用户协议。 */
    public record RegisterRequest(String username, String password, String agreementVersion, String serverId) {}

    public record LoginRequest(String username, String password, String serverId) {}

    public record AuthResponse(String token, String username, Long playerId) {}

    public record UserInfoResponse(Long playerId, String username, String faction, String cityName) {}

    /** 注销账号请求：需要玩家当前密码做最终确认 */
    public record DisableAccountRequest(String password, String confirm, String requestId) {}
    public record RecoverAccountRequest(String recoveryToken, boolean confirm) {}
}
