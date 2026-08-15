package com.arena.alliance.engine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 联盟可见格覆盖：1024 位图以固定 128 字节 Base64 保存。 */
@Entity
@Table(name = "explored_chunks")
public class ExploredChunk {

    @Id
    @Column(name = "chunk_key", length = 48)
    private String chunkKey;

    @Column(name = "chunk_x", nullable = false)
    private long chunkX;

    @Column(name = "chunk_y", nullable = false)
    private long chunkY;

    @Column(name = "coverage_base64", nullable = false, length = 172)
    private String coverageBase64;

    @Column(name = "updated_tick", nullable = false)
    private long updatedTick;

    protected ExploredChunk() {
    }

    public ExploredChunk(ChunkCoord coord, String coverageBase64, long updatedTick) {
        this.chunkKey = coord.key();
        this.chunkX = coord.x();
        this.chunkY = coord.y();
        this.coverageBase64 = coverageBase64;
        this.updatedTick = updatedTick;
    }

    public ChunkCoord coord() { return new ChunkCoord(chunkX, chunkY); }
    public String getCoverageBase64() { return coverageBase64; }
    public long getUpdatedTick() { return updatedTick; }
}
