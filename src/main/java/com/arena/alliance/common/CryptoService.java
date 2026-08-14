package com.arena.alliance.common;

import com.arena.alliance.config.AllianceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 密钥体系：一个根 secret（env 或 data-dir/secret.key 自动生成）派生出
 * AES-GCM 密钥（apikey 落库加密）与 HMAC 密钥（会话 token 签名）。
 */
@Component
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    private final SecretKeySpec aesKey;
    private final SecretKeySpec hmacKey;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(AllianceProperties props) {
        String secret = props.secret();
        if (secret == null || secret.isBlank()) {
            secret = loadOrCreateFileSecret(Path.of(props.dataDir()));
        }
        this.aesKey = new SecretKeySpec(sha256((secret + ":aes").getBytes(StandardCharsets.UTF_8)), "AES");
        this.hmacKey = new SecretKeySpec(sha256((secret + ":hmac").getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
    }

    private String loadOrCreateFileSecret(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            Path keyFile = dataDir.resolve("secret.key");
            if (Files.exists(keyFile)) {
                return Files.readString(keyFile).trim();
            }
            byte[] buf = new byte[32];
            random.nextBytes(buf);
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
            Files.writeString(keyFile, secret);
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
                // 非 POSIX 文件系统（如 Windows 挂载）跳过
            }
            log.info("已生成平台密钥文件: {}", keyFile.toAbsolutePath());
            return secret;
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化平台密钥，请设置 ALLIANCE_SECRET 环境变量", e);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] in = Base64.getDecoder().decode(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, in, 0, 12));
            byte[] pt = cipher.doFinal(in, 12, in.length - 12);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败（平台密钥是否变更？）", e);
        }
    }

    public String hmacBase64Url(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 失败", e);
        }
    }

    public String sha256Hex(String data) {
        return HexFormat.of().formatHex(sha256(data.getBytes(StandardCharsets.UTF_8)));
    }

    public String randomState() {
        byte[] buf = new byte[16];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
