package com.arena.alliance.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "api_keys", uniqueConstraints = @UniqueConstraint(columnNames = "token_hash"))
public class ApiKey {

    /** DB 侧配置状态；连接是否在线是运行时状态，由引擎另行提供 */
    public enum Status {ENABLED, DISABLED, INVALID, DUPLICATE}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 64)
    private String label;

    @Column(name = "token_cipher", nullable = false, columnDefinition = "TEXT")
    private String tokenCipher;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "game_username", length = 64)
    private String gameUsername;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ENABLED;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "last_seen_tick")
    private Long lastSeenTick;

    /** 是否在联盟地图成员图层显示自己的 Core；null 兼容升级前的旧数据。 */
    @Column(name = "show_core_on_map")
    private Boolean showCoreOnMap = Boolean.TRUE;

    /** 盟友名册是否只暴露对象 ID，不返回坐标和单位类型；null 兼容旧数据。 */
    @Column(name = "roster_ids_only")
    private Boolean rosterIdsOnly = Boolean.FALSE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getTokenCipher() {
        return tokenCipher;
    }

    public void setTokenCipher(String tokenCipher) {
        this.tokenCipher = tokenCipher;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getGameUsername() {
        return gameUsername;
    }

    public void setGameUsername(String gameUsername) {
        this.gameUsername = gameUsername;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Long getLastSeenTick() {
        return lastSeenTick;
    }

    public void setLastSeenTick(Long lastSeenTick) {
        this.lastSeenTick = lastSeenTick;
    }

    public boolean isShowCoreOnMap() {
        return showCoreOnMap == null || showCoreOnMap;
    }

    public void setShowCoreOnMap(boolean showCoreOnMap) {
        this.showCoreOnMap = showCoreOnMap;
    }

    public boolean isRosterIdsOnly() {
        return Boolean.TRUE.equals(rosterIdsOnly);
    }

    public void setRosterIdsOnly(boolean rosterIdsOnly) {
        this.rosterIdsOnly = rosterIdsOnly;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
