package com.wargame;

import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import com.wargame.service.AvatarStorageService;
import com.wargame.service.VipService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实数据库事务验证文件清理的提交、回滚及并发边界。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AvatarStorageServiceTest extends BaseServiceTest {
    private static final Path DIRECTORY = temporaryDirectory();
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/x9sAAAAASUVORK5CYII=");
    @Autowired private VipService vip;
    @Autowired private AvatarStorageService storage;
    @Autowired private PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void avatarDirectory(DynamicPropertyRegistry properties) {
        properties.add("wargame.avatar-upload-dir", DIRECTORY::toString);
    }

    @AfterEach
    void cleanTestFiles() throws IOException {
        try (var files = Files.list(DIRECTORY)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
        }
    }

    @AfterAll
    static void removeTestDirectory() throws IOException {
        Files.deleteIfExists(DIRECTORY);
    }

    @Test
    void repeatedUploadsKeepOnlyTheLatestFileIncludingWhenTheExtensionChanges() throws Exception {
        Long id = player("avatar-repeat");
        String latest = null;
        for (String extension : List.of("png", "jpg", "webp", "png", "png")) {
            String previous = latest;
            latest = upload(id, extension);
            assertArrayEquals(PNG, Files.readAllBytes(file(latest)));
            if (previous != null) assertFalse(Files.exists(file(previous)));
            assertEquals(latest, playerRepository.findById(id).orElseThrow().getCustomAvatar());
            assertEquals(1, fileCount());
        }
    }

    @Test
    void rollbackDeletesTheNewFileAndPreservesThePreviouslyCommittedAvatar() throws Exception {
        Long id = player("avatar-rollback");
        String previous = upload(id, "png");
        String[] rolledBack = new String[1];
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            try {
                rolledBack[0] = upload(id, "png");
                assertTrue(Files.exists(file(previous)), "旧头像必须保留到提交成功");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            throw new IllegalStateException("模拟保存失败");
        }));
        assertEquals(previous, playerRepository.findById(id).orElseThrow().getCustomAvatar());
        assertTrue(Files.exists(file(previous)));
        assertFalse(Files.exists(file(rolledBack[0])));
        assertEquals(1, fileCount());
    }

    @Test
    void switchingToExternalOrPresetAvatarRemovesThePreviousLocalFile() throws Exception {
        Long id = player("avatar-clear");
        String local = upload(id, "png");
        vip.setCustomAvatarUrl(id, "https://example.com/new-avatar.png");
        assertFalse(Files.exists(file(local)));
        local = upload(id, "png");
        vip.setCustomAvatarUrl(id, "");
        assertFalse(Files.exists(file(local)));
        assertNull(playerRepository.findById(id).orElseThrow().getCustomAvatar());
    }

    @Test
    void orphanSweepKeepsCurrentRecentAndUnmanagedFilesAndRemovesLegacyOrphans() throws Exception {
        Long id = player("avatar-sweep");
        String current = upload(id, "png");
        Path legacy = Files.write(DIRECTORY.resolve(id + "_1700000000000.jpg"), PNG);
        Path recent = Files.write(DIRECTORY.resolve(id + "_1700000000001.webp"), PNG);
        Path unmanaged = Files.write(DIRECTORY.resolve("preset.png"), PNG);
        Path outside = Files.createTempFile("avatar-outside-", ".png");
        Path link = DIRECTORY.resolve(id + "_1700000000002.png");
        try {
            Files.createSymbolicLink(link, outside);
            age(file(current)); age(legacy); age(unmanaged);
            assertEquals(1, storage.cleanupOrphans(Duration.ofDays(1)));
            assertTrue(Files.exists(file(current)));
            assertTrue(Files.exists(recent));
            assertTrue(Files.exists(unmanaged));
            assertTrue(Files.isSymbolicLink(link));
            assertTrue(Files.exists(outside));
            assertFalse(Files.exists(legacy));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void sweepReleasesDeletedAccountFilesButPreservesLegacySharedReferences() throws Exception {
        Long owner = player("avatar-owner"), other = player("avatar-legacy-share");
        String shared = upload(owner, "png");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Player player = playerRepository.findById(other).orElseThrow();
            player.setCustomAvatar(shared);
            playerRepository.save(player);
        });
        upload(owner, "png");
        assertTrue(Files.exists(file(shared)), "旧数据中仍被其他玩家引用的图片不能误删");
        age(file(shared));
        assertEquals(0, storage.cleanupOrphans(Duration.ofDays(1)));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Player player = playerRepository.findById(other).orElseThrow();
            player.setAccountStatus("DELETED");
            playerRepository.save(player);
        });
        assertEquals(1, storage.cleanupOrphans(Duration.ofDays(1)));
        assertFalse(Files.exists(file(shared)));
    }

    @Test
    void localPathsCannotAdoptAnotherPlayersFileOrResurrectADeletedFile() throws Exception {
        Long owner = player("avatar-path-owner"), other = player("avatar-path-other");
        String current = upload(owner, "png");
        vip.setCustomAvatarUrl(owner, current);
        assertTrue(Files.exists(file(current)));
        assertThrows(IllegalArgumentException.class, () -> vip.setCustomAvatarUrl(other, current));
        assertThrows(IllegalArgumentException.class, () -> vip.setCustomAvatarUrl(owner,
                "img/avatars/custom/../historical/rank-private-v1.webp"));
        upload(owner, "png");
        assertThrows(IllegalArgumentException.class, () -> vip.setCustomAvatarUrl(owner, current));
    }

    @Test
    void concurrentUploadsAndCleanupLeaveOneFileMatchingTheCommittedPlayer() throws Exception {
        Long id = player("avatar-concurrent");
        age(file(upload(id, "png")));
        var pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 8; index++) {
                tasks.add(pool.submit(() -> { start.await(); return upload(id, "png"); }));
            }
            tasks.add(pool.submit(() -> { start.await(); return storage.cleanupOrphans(Duration.ofDays(1)); }));
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
            String current = playerRepository.findById(id).orElseThrow().getCustomAvatar();
            assertTrue(Files.exists(file(current)));
            assertEquals(1, fileCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pathValidationAndTransactionGuardsRejectUnsafeFileOperations() throws Exception {
        Long id = player("avatar-cleanup-retry");
        String current = upload(id, "png");
        assertFalse(storage.isOwnedUpload(id, "img/avatars/custom/../" + Path.of(current).getFileName()));
        assertFalse(storage.isOwnedUpload(id + 1, current));
        assertThrows(IllegalArgumentException.class, () -> storage.cleanupOrphans(Duration.ZERO));
        assertThrows(IllegalStateException.class, () -> storage.store(id, PNG, "png"));
    }

    @Test
    void failedFileWriteRollsBackThePlayerReferenceAndPreservesTheOldAvatar() throws Exception {
        Long id = player("avatar-write-failure");
        String previous = upload(id, "png");
        Path notADirectory = Files.createTempFile("avatar-upload-blocked-", ".tmp");
        ReflectionTestUtils.setField(storage, "directory", notADirectory);
        try {
            assertThrows(IOException.class, () -> upload(id, "png"));
            assertEquals(previous, playerRepository.findById(id).orElseThrow().getCustomAvatar());
            assertTrue(Files.exists(file(previous)));
            assertEquals(1, fileCount());
        } finally {
            ReflectionTestUtils.setField(storage, "directory", DIRECTORY);
            Files.deleteIfExists(notADirectory);
        }
    }

    @Test
    void failedReferenceCheckKeepsTheFileAndNextSweepCanRetry() throws Exception {
        Long id = player("avatar-sweep-retry");
        Path orphan = Files.write(DIRECTORY.resolve(id + "_1700000000000.png"), PNG);
        age(orphan);
        PlayerRepository unavailable = mock(PlayerRepository.class);
        when(unavailable.lockById(id)).thenThrow(new IllegalStateException("模拟数据库暂时不可用"));
        var retrying = new AvatarStorageService(unavailable, transactions, DIRECTORY.toString());
        assertEquals(0, retrying.cleanupOrphans(Duration.ofDays(1)));
        assertTrue(Files.exists(orphan));
        assertEquals(1, storage.cleanupOrphans(Duration.ofDays(1)));
        assertFalse(Files.exists(orphan));
    }

    @Test
    void defaultDirectoryResolvesToTheServedFrontend() {
        var defaultStorage = new AvatarStorageService(playerRepository, transactions, "");
        Path frontend = Files.isRegularFile(Path.of("frontend", "index.html"))
                ? Path.of("frontend") : Path.of("..", "frontend");
        assertTrue(Files.isRegularFile(frontend.resolve("index.html")));
        assertEquals(frontend.resolve(Path.of("img", "avatars", "custom")).toAbsolutePath().normalize(),
                ReflectionTestUtils.getField(defaultStorage, "directory"));
    }

    private Long player(String username) {
        return new TransactionTemplate(transactions).execute(status -> {
            Player player = createTestPlayer(username, 30);
            player.setVipLevel(5);
            return playerRepository.save(player).getId();
        });
    }

    private String upload(Long id, String extension) throws IOException {
        return (String) vip.uploadCustomAvatar(id, PNG, extension).get("customAvatar");
    }

    private Path file(String url) {
        return DIRECTORY.resolve(Path.of(url).getFileName());
    }

    private long fileCount() throws IOException {
        try (var files = Files.list(DIRECTORY)) { return files.count(); }
    }

    private void age(Path file) throws IOException {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - Duration.ofDays(2).toMillis()));
    }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("avatar-storage-test-"); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
