package com.wargame.model.dto;

public final class VipDtos {
    private VipDtos() {}

    public record DailySalaryClaimRequest(Integer targetCitySlot) {}
    public record LevelPackClaimRequest(Integer targetLevel, Integer targetCitySlot) {}
    public record LevelPackClaimAllRequest(Integer targetCitySlot) {}
    public record MilestoneClaimRequest(Long milestoneCents, Integer targetCitySlot) {}
    public record AllCitiesAppeaseRequest(String appeaseType) {}
    public record UniversalSkillConvertRequest(String targetSkillId) {}
    public record VipBadgeToggleRequest(Boolean visible) {}
    public record AvatarFrameRequest(String frameId) {}
    public record CustomAvatarUrlRequest(String url) {}
    public record CustomAvatarUploadRequest(String dataUrl) {}
}
