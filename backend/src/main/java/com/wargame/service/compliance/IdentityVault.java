package com.wargame.service.compliance;

import com.wargame.config.ComplianceProperties;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 身份关联使用带密钥摘要，年龄依据以随机 IV 加密；密钥不写入数据库。 */
@Component
public class IdentityVault {
    private final ComplianceProperties config;
    public IdentityVault(ComplianceProperties config) { this.config = config; }
    private byte[] key() {
        byte[] result = Base64.getDecoder().decode(config.getDataKey());
        if (result.length != 32) throw new IllegalStateException("防沉迷数据密钥必须是 Base64 编码的 32 字节密钥");
        return result;
    }
    public void validate() { key(); }
    public String subjectId(String reference) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("identity:" + reference).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("身份数据服务不可用"); }
    }
    public static String sessionId(String secret) {
        if (secret == null || !secret.matches("[a-zA-Z0-9_-]{32,80}")) return "";
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("会话服务不可用"); }
    }
    public String encrypt(String text) {
        try {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("身份数据服务不可用"); }
    }
    public String decrypt(String value) {
        try {
            String[] parts = value.split("\\.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("身份数据服务不可用"); }
    }
}
