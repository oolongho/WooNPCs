package com.oolongho.woonpc.skin.uuidcache;

import com.oolongho.woonpc.skin.json.Json;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 文件持久化 UUID 缓存。
 *
 * <p>存储于 {@code plugins/WooNPCs/uuid-cache/}，每个玩家名一个 JSON 文件，文件名为
 * 玩家名 SHA-256 前 32 位十六进制。文件内容：{@code {"uuid":"<dashed>","cachedAt":<ms>}}。</p>
 *
 * <p>TTL 为 30 天。文件 IO 在调用线程执行——本类仅在异步线程（MojangQueue）被调用，不阻塞主线程。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class UUIDFileCache implements UUIDCache {

    /** 缓存有效期：30 天。 */
    private static final long TTL_MS = 30L * 24 * 60 * 60 * 1000;

    private final Path dir;

    public UUIDFileCache(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create uuid cache directory: " + dir, e);
        }
    }

    @Override
    public @Nullable UUID getUUID(String name) {
        Path file = pathFor(name);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Json.Obj obj = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            Long cachedAt = obj.lng("cachedAt");
            if (cachedAt != null && System.currentTimeMillis() - cachedAt > TTL_MS) {
                Files.deleteIfExists(file);
                return null;
            }
            String uuidStr = obj.str("uuid");
            if (uuidStr == null) {
                return null;
            }
            return UUID.fromString(uuidStr);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void putUUID(String name, UUID uuid) {
        Path file = pathFor(name);
        String json = "{\"uuid\":\"" + uuid.toString()
                + "\",\"cachedAt\":" + System.currentTimeMillis() + "}";
        try {
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void invalidate(String name) {
        try {
            Files.deleteIfExists(pathFor(name));
        } catch (IOException ignored) {
        }
    }

    @Override
    public void clear() {
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private Path pathFor(String name) {
        return dir.resolve(hash(name) + ".json");
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
