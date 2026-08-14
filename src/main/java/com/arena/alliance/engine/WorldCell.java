package com.arena.alliance.engine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 障碍地形永久记忆（游戏内障碍是永久地形，跨重启保留联盟探索成果）。
 */
@Entity
@Table(name = "world_cells")
public class WorldCell {

    @Id
    @Column(name = "cell_key", length = 48)
    private String cellKey;

    @Column(nullable = false)
    private long x;

    @Column(nullable = false)
    private long y;

    protected WorldCell() {
    }

    public WorldCell(long x, long y) {
        this.cellKey = x + ":" + y;
        this.x = x;
        this.y = y;
    }

    public String getCellKey() {
        return cellKey;
    }

    public long getX() {
        return x;
    }

    public long getY() {
        return y;
    }
}
