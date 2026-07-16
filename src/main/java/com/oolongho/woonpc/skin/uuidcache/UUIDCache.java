package com.oolongho.woonpc.skin.uuidcache;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 玩家名→UUID 缓存抽象。
 *
 * <p>Mojang session API 需 UUID 查询皮肤，而用户常输入玩家名。本缓存避免对同一玩家名
 * 重复请求 Mojang username→UUID 接口。提供双层实现：内存 LRU（{@link UUIDMemoryCache}）
 * 与文件持久化（{@link UUIDFileCache}）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public interface UUIDCache {

    /**
     * 查询玩家名对应的 UUID。
     *
     * @param name 玩家名
     * @return 命中的 UUID，未命中返回 null
     */
    @Nullable UUID getUUID(String name);

    /**
     * 缓存玩家名→UUID 映射。
     *
     * @param name 玩家名
     * @param uuid 玩家 UUID
     */
    void putUUID(String name, UUID uuid);

    /**
     * 使指定玩家名失效。
     *
     * @param name 玩家名
     */
    void invalidate(String name);

    /** 清空全部缓存。 */
    void clear();
}
