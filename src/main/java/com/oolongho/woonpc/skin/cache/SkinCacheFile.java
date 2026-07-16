package com.oolongho.woonpc.skin.cache;

import com.oolongho.woonpc.skin.SkinData;
import com.oolongho.woonpc.skin.json.Json;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;

/**
 * 文件持久化皮肤缓存。
 *
 * <p>存储于 {@code plugins/WooNPCs/skin-cache/}，每个皮肤一个 JSON 文件，文件名为标识符的
 * SHA-256 前 32 位十六进制（避免标识符含非法文件名字符）。文件内容：
 * {@code {"texture":"<base64>","signature":"<sig>","cachedAt":<ms>}}。</p>
 *
 * <p>TTL 为 7 天，读取时校验过期则删除并视为未命中。文件 IO 全部在调用线程执行——
 * 本类仅在异步线程被调用（SkinManager 异步路径），不阻塞主线程。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class SkinCacheFile implements SkinCache {

    /** 缓存有效期：7 天。 */
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private final Path dir;

    public SkinCacheFile(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create skin cache directory: " + dir, e);
        }
    }

    @Override
    public @Nullable SkinData get(String identifier) {
        Path file = pathFor(identifier);
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
            String texture = obj.str("texture");
            if (texture == null) {
                return null;
            }
            String signature = obj.str("signature");
            return new SkinData(texture, signature == null ? "" : signature);
        } catch (Exception e) {
            // 损坏的缓存文件：忽略，视为未命中
            return null;
        }
    }

    @Override
    public void put(String identifier, SkinData skin) {
        Path file = pathFor(identifier);
        String json = "{\"texture\":\"" + escape(skin.texture())
                + "\",\"signature\":\"" + escape(skin.signature())
                + "\",\"cachedAt\":" + System.currentTimeMillis() + "}";
        try {
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 写入失败不影响主流程，仅丢失持久化
        }
    }

    @Override
    public void invalidate(String identifier) {
        try {
            Files.deleteIfExists(pathFor(identifier));
        } catch (IOException ignored) {
        }
    }

    @Override
    public void clear() {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    @Override
    public int size() {
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(p -> p.toString().endsWith(".json")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private Path pathFor(String identifier) {
        return dir.resolve(hash(identifier) + ".json");
    }

    /** SHA-256 取前 32 位十六进制作为文件名，规避标识符中的非法文件名字符。 */
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
            throw new RuntimeException("SHA-256 unavailable", e); // JDK 保证可用
        }
    }

    /** 极简 JSON 字符串转义（texture/signature 为 Base64，实际不会触发，但保证正确性）。 */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
