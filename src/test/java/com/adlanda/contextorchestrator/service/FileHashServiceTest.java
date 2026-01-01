package com.adlanda.contextorchestrator.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for FileHashService.
 * Tests SHA-256 hash computation for file change detection.
 */
class FileHashServiceTest {

    private FileHashService hashService;

    @BeforeEach
    void setUp() {
        hashService = new FileHashService();
    }

    @Test
    void computeHash_sameContent_returnsSameHash() {
        String hash1 = hashService.computeHash("hello world");
        String hash2 = hashService.computeHash("hello world");

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void computeHash_differentContent_returnsDifferentHash() {
        String hash1 = hashService.computeHash("hello world");
        String hash2 = hashService.computeHash("hello universe");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeHash_returnsValidSha256Format() {
        String hash = hashService.computeHash("test content");

        assertThat(hash)
                .hasSize(64)  // SHA-256 produces 64 hex characters
                .matches("[a-f0-9]+");
    }

    @Test
    void computeHash_emptyString_returnsConsistentHash() {
        String hash = hashService.computeHash("");

        // SHA-256 of empty string is well-known
        assertThat(hash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void computeHash_file_matchesContentHash(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.txt");
        String content = "file content for hashing";
        Files.writeString(file, content);

        String fileHash = hashService.computeHash(file);
        String contentHash = hashService.computeHash(content);

        assertThat(fileHash).isEqualTo(contentHash);
    }

    @Test
    void computeHash_whitespaceMatters() {
        String hash1 = hashService.computeHash("hello world");
        String hash2 = hashService.computeHash("hello  world");  // extra space

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeHash_newlinesAreSignificant() {
        String hash1 = hashService.computeHash("line1\nline2");
        String hash2 = hashService.computeHash("line1\r\nline2");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void getFileSize_returnsCorrectSize(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.txt");
        String content = "Hello, World!";
        Files.writeString(file, content);

        long size = hashService.getFileSize(file);

        assertThat(size).isEqualTo(content.getBytes().length);
    }

    @Test
    void computeHash_largeContent_completesSuccessfully() {
        // Generate 1MB of content
        String largeContent = "x".repeat(1024 * 1024);
        
        String hash = hashService.computeHash(largeContent);

        assertThat(hash)
                .hasSize(64)
                .matches("[a-f0-9]+");
    }
}
