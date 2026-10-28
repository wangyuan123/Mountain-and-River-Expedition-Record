package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 安全凭据独立持久化，不作为游戏状态返回。时间均为真实 UTC 毫秒。 */
public final class AccountEmailRecords {
    private AccountEmailRecords() {}

    @Entity(name = "AccountEmailGate") @Table(name = "account_email_gate") @Data @NoArgsConstructor
    public static class Gate { @Id private Integer id; }

    @Entity(name = "AccountEmailQuota") @Table(name = "account_email_quotas") @Data @NoArgsConstructor
    public static class Quota {
        @Id @Column(length = 80) private String id;
        private int used;
        @Column(name = "expires_at", nullable = false) private long expiresAt;
    }

    @Entity(name = "AccountEmailChallenge") @Table(name = "account_email_challenges") @Data @NoArgsConstructor
    public static class Challenge {
        @Id @Column(length = 64) private String slot;
        @Column(name = "challenge_id", unique = true, nullable = false, length = 64) private String challengeId;
        @Column(length = 32, nullable = false) private String purpose;
        @Column(name = "player_id") private Long playerId;
        @Column(length = 50) private String username;
        @Column(name = "email_cipher", length = 512) private String emailCipher;
        @Column(name = "email_key", length = 64) private String emailKey;
        @Column(name = "code_hash", length = 64) private String codeHash;
        @Column(name = "auth_version") private long authVersion;
        @Column(name = "email_version") private long emailVersion;
        @Column(name = "flow_hash", length = 64) private String flowHash;
        @Column(name = "expires_at", nullable = false) private long expiresAt;
        private boolean sent;
        private int failures;
        private boolean consumed;
    }

    @Entity(name = "AccountEmailChange") @Table(name = "account_email_changes") @Data @NoArgsConstructor
    public static class Change {
        @Id @Column(name = "token_hash", length = 64) private String tokenHash;
        @Column(name = "player_id", nullable = false) private Long playerId;
        @Column(name = "email_cipher", length = 512, nullable = false) private String emailCipher;
        @Column(name = "email_key", length = 64, nullable = false) private String emailKey;
        @Column(name = "auth_version") private long authVersion;
        @Column(name = "email_version") private long emailVersion;
        @Column(name = "expires_at", nullable = false) private long expiresAt;
    }

    @Entity(name = "AccountEmailOutbox") @Table(name = "account_email_outbox") @Data @NoArgsConstructor
    public static class Outbox {
        @Id @Column(length = 64) private String id;
        @Column(name = "challenge_id", length = 64) private String challengeId;
        @Column(name = "player_id") private Long playerId;
        @Column(name = "email_cipher", length = 512) private String emailCipher;
        @Column(name = "body_cipher", length = 4096) private String bodyCipher;
        @Column(length = 200) private String subject;
        @Column(name = "expires_at", nullable = false) private long expiresAt;
        @Column(name = "next_attempt_at", nullable = false) private long nextAttemptAt;
        private int attempts;
        /** 0 待发、1 租约发送中、2 已投递、3 已取消/过期。 */
        private int state;
    }
}
