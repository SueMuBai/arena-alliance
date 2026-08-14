package com.arena.alliance.apikey;

import com.arena.alliance.common.AllianceException;
import com.arena.alliance.common.CryptoService;
import com.arena.alliance.engine.EngineEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyRepository repository;
    private final CryptoService crypto;
    private final ApplicationEventPublisher publisher;

    public ApiKeyService(ApiKeyRepository repository, CryptoService crypto, ApplicationEventPublisher publisher) {
        this.repository = repository;
        this.crypto = crypto;
        this.publisher = publisher;
    }

    @Transactional
    public ApiKey addKey(long userId, String token, String label) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.length() < 8 || trimmed.contains(" ") || trimmed.contains("\n")) {
            throw AllianceException.badRequest("apikey 格式不正确");
        }
        String hash = crypto.sha256Hex(trimmed);
        if (repository.findByTokenHash(hash).isPresent()) {
            throw AllianceException.badRequest("该 apikey 已被上传过");
        }
        ApiKey key = new ApiKey();
        key.setUserId(userId);
        key.setLabel(label == null || label.isBlank() ? null : label.trim());
        key.setTokenCipher(crypto.encrypt(trimmed));
        key.setTokenHash(hash);
        key.setStatus(ApiKey.Status.ENABLED);
        repository.save(key);
        publisher.publishEvent(new EngineEvents.KeyActivated(key.getId()));
        return key;
    }

    @Transactional
    public void deleteKey(long requesterId, boolean admin, long keyId) {
        ApiKey key = find(keyId);
        if (!admin && !key.getUserId().equals(requesterId)) {
            throw AllianceException.forbidden("无权操作他人的 apikey");
        }
        publisher.publishEvent(new EngineEvents.KeyDeactivated(keyId));
        repository.delete(key);
    }

    @Transactional
    public ApiKey setEnabled(long requesterId, boolean admin, long keyId, boolean enabled) {
        ApiKey key = find(keyId);
        if (!admin && !key.getUserId().equals(requesterId)) {
            throw AllianceException.forbidden("无权操作他人的 apikey");
        }
        key.setStatus(enabled ? ApiKey.Status.ENABLED : ApiKey.Status.DISABLED);
        if (enabled) {
            key.setLastError(null);
        }
        repository.save(key);
        publisher.publishEvent(enabled
                ? new EngineEvents.KeyActivated(keyId)
                : new EngineEvents.KeyDeactivated(keyId));
        return key;
    }

    @Transactional
    public void markInvalid(long keyId, String error) {
        repository.findById(keyId).ifPresent(key -> {
            key.setStatus(ApiKey.Status.INVALID);
            key.setLastError(error == null ? null : error.substring(0, Math.min(error.length(), 500)));
            repository.save(key);
        });
    }

    /**
     * 会话识别出该 key 对应的游戏用户名后回写。
     * 若已有另一个启用中的 key 是同一游戏账号（同玩家多凭证共享同一 AGENT 槽位，
     * 双连接无意义且会互相干扰），本 key 标记为 DUPLICATE。
     *
     * @return true 正常；false 重复，调用方应停止该会话
     */
    @Transactional
    public boolean updateGameUsername(long keyId, String gameUsername) {
        ApiKey key = find(keyId);
        boolean duplicate = repository
                .findFirstByGameUsernameAndStatusAndIdNot(gameUsername, ApiKey.Status.ENABLED, keyId)
                .isPresent();
        key.setGameUsername(gameUsername);
        if (duplicate) {
            key.setStatus(ApiKey.Status.DUPLICATE);
            key.setLastError("与已启用的 key 属于同一游戏账号");
            log.info("key {} 与现有 key 重复（游戏账号 {}）", keyId, gameUsername);
        }
        repository.save(key);
        return !duplicate;
    }

    @Transactional
    public void touchLastSeen(long keyId, long tick) {
        repository.findById(keyId).ifPresent(key -> {
            key.setLastSeenTick(tick);
            repository.save(key);
        });
    }

    @Transactional
    public void disableAllOf(long userId) {
        for (ApiKey key : repository.findByUserIdOrderByIdAsc(userId)) {
            if (key.getStatus() == ApiKey.Status.ENABLED) {
                key.setStatus(ApiKey.Status.DISABLED);
                repository.save(key);
            }
        }
    }

    public ApiKey find(long keyId) {
        return repository.findById(keyId).orElseThrow(() -> AllianceException.notFound("apikey 不存在"));
    }

    public List<ApiKey> listByUser(long userId) {
        return repository.findByUserIdOrderByIdAsc(userId);
    }

    public List<ApiKey> enabledKeys() {
        return repository.findByStatus(ApiKey.Status.ENABLED);
    }

    public List<ApiKey> listAll() {
        return repository.findAll();
    }

    public String decrypt(ApiKey key) {
        return crypto.decrypt(key.getTokenCipher());
    }

    /** 掩码展示：仅给出 hash 前 8 位，绝不回显明文 */
    public static String masked(ApiKey key) {
        return "***" + key.getTokenHash().substring(0, 8);
    }
}
