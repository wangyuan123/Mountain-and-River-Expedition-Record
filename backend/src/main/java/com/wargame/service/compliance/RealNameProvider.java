package com.wargame.service.compliance;

import java.time.LocalDate;

/**
 * 正式接入适配边界：兑换核验流程产生的短期凭据，不接受客户端自报年龄或成功标记。
 * 适配器须验证来源、有效期、防重放及监护关系；本接口不是国家平台的线缆协议。
 */
public interface RealNameProvider {
    record VerifiedIdentity(String subjectReference, LocalDate birthDate, long validUntil,
                            String guardianReference, String consentVersion) {}
    VerifiedIdentity verify(String proof, Long playerId);
    default String authorizationUrl() { return ""; }
    default boolean available() { return true; }
}
