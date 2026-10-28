package com.wargame.service;

import com.wargame.config.AccountEmailProperties;
import com.wargame.security.AccountException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/** 邮箱密文与查询/验证码摘要使用独立密钥，均不复用 JWT 或实名密钥。 */
@Component
public class AccountEmailVault {
    private final AccountEmailProperties config;
    private final SecureRandom random = new SecureRandom();
    public AccountEmailVault(AccountEmailProperties config) { this.config = config; }

    @PostConstruct
    void validateKeys() {
        if (!config.getDataKey().isBlank()) key(config.getDataKey());
        if (!config.getHmacKey().isBlank()) key(config.getHmacKey());
    }

    private byte[] key(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length != 32) throw new IllegalArgumentException();
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("邮箱数据与 HMAC 密钥必须分别为 Base64 编码的 32 字节密钥");
        }
    }

    public String normalize(String email) {
        String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}@qq\\.com"))
            throw new AccountException("EMAIL_INVALID", "请输入完整的 QQ 邮箱地址（@qq.com）");
        try { new jakarta.mail.internet.InternetAddress(value, true).validate(); }
        catch (jakarta.mail.internet.AddressException e) { throw new AccountException("EMAIL_INVALID", "QQ 邮箱地址格式不正确"); }
        return value;
    }

    public String token() {
        byte[] bytes = new byte[24]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public String code() { return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)); }
    public String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(config.getHmacKey()), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("邮箱摘要不可用"); }
    }
    public boolean matches(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(String value) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(config.getDataKey()), "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("邮箱加密不可用"); }
    }
    public String decrypt(String value) {
        try {
            String[] parts = value.split(":");
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(config.getDataKey()), "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("邮箱密文不可读取，请检查配置"); }
    }
    public String mask(String email) {
        String local = email.substring(0, email.indexOf('@'));
        return (local.length() > 4 ? local.substring(0, 2) + "***" + local.substring(local.length() - 2)
                : local.length() > 2 ? local.substring(0, 1) + "***" : "***") + "@qq.com";
    }
}
