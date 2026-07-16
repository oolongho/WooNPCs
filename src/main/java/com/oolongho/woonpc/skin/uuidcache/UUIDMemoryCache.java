package com.oolongho.woonpc.skin.uuidcache;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 基于访问顺序 {@link LinkedHashMap} 的 LRU 内存 UUID 缓存。
 *
 * <p>容量由构造参数指定（默认 500）。线程安全：{@link Collections#synchronizedMap(Map)} 包装，
 * 复合操作加 synchronized。不设 TTL，由 LRU 容量淘汰保证上限；文件层负责跨重启与防过期。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class UUIDMemoryCache implements UUIDCache {

    private final Map<String, UUID> cache;

    public UUIDMemoryCache(int capacity) {
        final int cap = Math.max(1, capacity);
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, UUID> eldest) {
                return size() > cap;
            }
        });
    }

    public UUIDMemoryCache() {
        this(500);
    }

    @Override
    public @Nullable UUID getUUID(String name) {
        synchronized (cache) {
            return cache.get(name);
        }
    }

    @Override
    public void putUUID(String name, UUID uuid) {
        synchronized (cache) {
            cache.put(name, uuid);
        }
    }

    @Override
    public void invalidate(String name) {
        synchronized (cache) {
            cache.remove(name);
        }
    }

    @Override
    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }
}
