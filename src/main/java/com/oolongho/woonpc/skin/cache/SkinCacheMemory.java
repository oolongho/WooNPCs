package com.oolongho.woonpc.skin.cache;

import com.oolongho.woonpc.skin.SkinData;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于访问顺序 {@link LinkedHashMap} 的 LRU 内存皮肤缓存。
 *
 * <p>容量由构造参数指定（来源：{@code skin.cache-memory-size}），超出容量时淘汰最久未访问条目。
 * 线程安全：通过 {@link Collections#synchronizedMap(Map)} 包装，复合操作（get/put 含淘汰）
 * 由 underlying map 在 synchronized 块内完成；本类的 TTL 检查+移除为复合操作，额外加 synchronized。</p>
 *
 * <p>内存层不设 TTL（由 LRU 容量淘汰保证上限）；文件层负责跨重启持久化与 TTL 防过期。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class SkinCacheMemory implements SkinCache {

    private final Map<String, SkinData> cache;

    public SkinCacheMemory(int capacity) {
        final int cap = Math.max(1, capacity);
        // accessOrder=true：get/put 都将条目移到链表尾；removeEldestEntry 在 put 后淘汰链表头（最久未访问）
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SkinData> eldest) {
                return size() > cap;
            }
        });
    }

    @Override
    public @Nullable SkinData get(String identifier) {
        synchronized (cache) {
            return cache.get(identifier);
        }
    }

    @Override
    public void put(String identifier, SkinData skin) {
        synchronized (cache) {
            cache.put(identifier, skin);
        }
    }

    @Override
    public void invalidate(String identifier) {
        synchronized (cache) {
            cache.remove(identifier);
        }
    }

    @Override
    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    @Override
    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }
}
