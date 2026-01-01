package com.adlanda.contextorchestrator.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JPA entity tracking ingested source files.
 *
 * Used for incremental ingestion - tracks which files have been ingested
 * and their content hash to detect changes.
 */
@Entity
@Table(name = "ingested_sources")
public class IngestedSource {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "file_path", unique = true, nullable = false, length = 500)
    private String filePath;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "ingested_at")
    private LocalDateTime ingestedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Default constructor for JPA
    public IngestedSource() {
        this.id = UUID.randomUUID();
        this.ingestedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Constructor with required fields
    public IngestedSource(String filePath, String fileHash, Long fileSize, Integer chunkCount) {
        this();
        this.filePath = filePath;
        this.fileHash = fileHash;
        this.fileSize = fileSize;
        this.chunkCount = chunkCount;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public LocalDateTime getIngestedAt() {
        return ingestedAt;
    }

    public void setIngestedAt(LocalDateTime ingestedAt) {
        this.ingestedAt = ingestedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "IngestedSource{" +
                "id=" + id +
                ", filePath='" + filePath + '\'' +
                ", fileHash='" + fileHash + '\'' +
                ", chunkCount=" + chunkCount +
                ", updatedAt=" + updatedAt +
                '}';
    }
}