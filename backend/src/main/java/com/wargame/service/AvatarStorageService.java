package com.wargame.service;

import com.wargame.repository.PlayerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/** 管理本地头像的文件生命周期；数据库始终只保留当前文件的相对路径。 */
@Slf4j
@Service
public class AvatarStorageService {
    public static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private static final String URL_PREFIX = "img/avatars/custom/";
    // 兼容旧版时间戳文件名及新版 UUID，只处理上传目录直属的受管文件。
    private static final Pattern FILE_NAME = Pattern.compile("([1-9][0-9]*)_([0-9]{13}|[0-9a-f]{32})\\.(png|jpg|webp)");
    private final PlayerRepository players;
    private final TransactionTemplate cleanupTransactions;
    private final Path directory;

    public AvatarStorageService(PlayerRepository players, PlatformTransactionManager transactions,
                                @Value("${wargame.avatar-upload-dir:}") String uploadDirectory) {
        this.players = players;
        this.cleanupTransactions = new TransactionTemplate(transactions);
        // 后台扫描逐个使用独立短事务，不持有整个目录扫描的数据库锁。
        this.cleanupTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.cleanupTransactions.setTimeout(5);
        this.directory = resolveDirectory(uploadDirectory).toAbsolutePath().normalize();
    }

    /** 调用方持有玩家行锁；新文件在事务回滚时删除，避免失败上传留下垃圾。 */
    public String store(Long playerId, byte[] image, String extension) throws IOException {
        requireTransaction();
        if (image == null || image.length == 0 || image.length > MAX_IMAGE_BYTES
                || !java.util.Set.of("png", "jpg", "webp").contains(extension)) {
            throw new IllegalArgumentException("头像文件格式或大小无效");
        }
        Files.createDirectories(directory);
        String filename = playerId + "_" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path file = Files.createFile(directory.resolve(filename));
        String url = URL_PREFIX + filename;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) deleteManagedFile(url);
            }
        });
        Files.write(file, image);
        return url;
    }

    /** 只在新头像事务提交成功后清理旧文件；外链和预设头像不进入删除逻辑。 */
    public void removeAfterCommit(Long playerId, String previousUrl) {
        if (!isOwnedUpload(playerId, previousUrl)) return;
        requireTransaction();
        // 旧路径不能再次被采用，因此当前事务确认无引用后，提交回调可以直接删文件。
        // 不在提交回调中另取数据库连接，避免大量并发上传耗尽连接池。
        if (players.existsByCustomAvatar(previousUrl)) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) deleteManagedFile(previousUrl);
            }
        });
    }

    public boolean isOwnedUpload(Long playerId, String url) {
        if (playerId == null || url == null || !url.startsWith(URL_PREFIX)) return false;
        var match = FILE_NAME.matcher(url.substring(URL_PREFIX.length()));
        return match.matches() && match.group(1).equals(playerId.toString());
    }

    /** 流式扫描超过宽限期的遗留文件，逐个核对数据库，内存中不装载全部图片。 */
    public int cleanupOrphans(Duration gracePeriod) throws IOException {
        if (gracePeriod.isNegative() || gracePeriod.isZero()) {
            throw new IllegalArgumentException("头像清理宽限期必须大于零");
        }
        if (!Files.isDirectory(directory)) return 0;
        long cutoff = System.currentTimeMillis() - gracePeriod.toMillis();
        int removed = 0;
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                if (!FILE_NAME.matcher(file.getFileName().toString()).matches()
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    if (Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis() <= cutoff
                            && deleteIfUnused(URL_PREFIX + file.getFileName())) removed++;
                } catch (NoSuchFileException ignored) {
                    // 用户切换头像时可能已经删除此文件，留待下一项即可。
                }
            }
        }
        return removed;
    }

    /** 与上传使用同一玩家行锁，防止清理任务删除尚在提交中的新头像。 */
    private boolean deleteIfUnused(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) return false;
        var match = FILE_NAME.matcher(url.substring(URL_PREFIX.length()));
        if (!match.matches()) return false;
        try {
            long ownerId = Long.parseLong(match.group(1));
            return Boolean.TRUE.equals(cleanupTransactions.execute(status -> {
                players.lockById(ownerId);
                if (players.existsByCustomAvatar(url)) return false;
                return deleteManagedFile(url);
            }));
        } catch (RuntimeException e) {
            // 清理失败不能把已成功保存的头像响应变成失败，后续扫描会重试。
            log.warn("头像引用检查失败，暂不删除文件: {}", url, e);
            return false;
        }
    }

    private boolean deleteManagedFile(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) return false;
        String filename = url.substring(URL_PREFIX.length());
        if (!FILE_NAME.matcher(filename).matches()) return false;
        Path file = directory.resolve(filename);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            return Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            log.warn("头像文件清理失败，保留给定时任务重试: {}", file, e);
            return false;
        }
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("头像文件变更必须处于数据库事务中");
        }
    }

    /** 兼容从仓库根目录或 backend 启动，以及容器共享目录的显式配置。 */
    private Path resolveDirectory(String configured) {
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        Path frontend = Path.of("frontend");
        if (!Files.isRegularFile(frontend.resolve("index.html"))
                && Files.isRegularFile(Path.of("..", "frontend", "index.html"))) {
            frontend = Path.of("..", "frontend");
        }
        return frontend.resolve(Path.of("img", "avatars", "custom"));
    }
}
