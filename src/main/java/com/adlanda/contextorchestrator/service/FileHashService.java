package com.adlanda.contextorchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Service for computing file content hashes.
 *
 * Used for change detection in incremental ingestion.
 * Files with the same hash haven't changed and can be skipped.
 */
@Service
public class FileHashService {

    private static final Logger log = LoggerFactory.getLogger(FileHashService.class);

    /**
     * Computes the SHA-256 hash of a file's content.
     *
     * @param filePath Path to the file
     * @return Hexadecimal string representation of the hash (64 characters)
     * @throws IOException If the file cannot be read
     */
    public String computeHash(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(filePath);
            byte[] hashBytes = digest.digest(fileBytes);
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in standard JVMs
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Computes the SHA-256 hash of a string content.
     *
     * @param content The string content to hash
     * @return Hexadecimal string representation of the hash (64 characters)
     */
    public String computeHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(content.getBytes());
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Gets the file size in bytes.
     *
     * @param filePath Path to the file
     * @return File size in bytes
     * @throws IOException If the file cannot be read
     */
    public long getFileSize(Path filePath) throws IOException {
        return Files.size(filePath);
    }
}