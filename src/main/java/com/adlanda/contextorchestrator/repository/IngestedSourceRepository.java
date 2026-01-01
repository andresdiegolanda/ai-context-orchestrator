package com.adlanda.contextorchestrator.repository;

import com.adlanda.contextorchestrator.entity.IngestedSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for tracking ingested source files.
 *
 * Supports incremental ingestion by tracking file hashes
 * to detect which files have changed since last ingestion.
 */
@Repository
public interface IngestedSourceRepository extends JpaRepository<IngestedSource, UUID> {

    /**
     * Find an ingested source by its file path.
     *
     * @param filePath The relative path to the source file
     * @return The ingested source record if found
     */
    Optional<IngestedSource> findByFilePath(String filePath);

    /**
     * Check if a file with the given path and hash already exists.
     * If this returns true, the file hasn't changed and can be skipped.
     *
     * @param filePath The relative path to the source file
     * @param fileHash The SHA-256 hash of the file content
     * @return true if the exact file (same path and hash) exists
     */
    boolean existsByFilePathAndFileHash(String filePath, String fileHash);

    /**
     * Delete all chunks associated with a specific source file.
     * Called before re-ingesting a changed file.
     *
     * @param filePath The relative path to the source file
     */
    @Modifying
    @Query("DELETE FROM IngestedSource s WHERE s.filePath = :filePath")
    void deleteByFilePath(String filePath);

    /**
     * Count total number of ingested files.
     */
    long count();

    /**
     * Sum of all chunk counts across all ingested files.
     */
    @Query("SELECT COALESCE(SUM(s.chunkCount), 0) FROM IngestedSource s")
    long sumChunkCount();
}