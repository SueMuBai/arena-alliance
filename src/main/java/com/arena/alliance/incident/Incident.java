package com.arena.alliance.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "incidents", indexes = @Index(name = "idx_incidents_created", columnList = "created_at"))
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tick;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(name = "attacker_user_id")
    private Long attackerUserId;

    @Column(name = "attacker_name", length = 64)
    private String attackerName;

    @Column(name = "victim_user_id")
    private Long victimUserId;

    @Column(name = "victim_name", length = 64)
    private String victimName;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public Long getTick() {
        return tick;
    }

    public void setTick(Long tick) {
        this.tick = tick;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getAttackerUserId() {
        return attackerUserId;
    }

    public void setAttackerUserId(Long attackerUserId) {
        this.attackerUserId = attackerUserId;
    }

    public String getAttackerName() {
        return attackerName;
    }

    public void setAttackerName(String attackerName) {
        this.attackerName = attackerName;
    }

    public Long getVictimUserId() {
        return victimUserId;
    }

    public void setVictimUserId(Long victimUserId) {
        this.victimUserId = victimUserId;
    }

    public String getVictimName() {
        return victimName;
    }

    public void setVictimName(String victimName) {
        this.victimName = victimName;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
