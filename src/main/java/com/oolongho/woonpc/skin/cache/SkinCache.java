package com.oolongho.woonpc.skin.cache;

import com.oolongho.woonpc.skin.SkinData;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 皮肤缓存抽象。
 *
 * <p>缓存键为皮肤标识符（玩家名 / UUID / {@code mineskin:<url>}），值为已解析的
 * {@link SkinData}。提供双层实现：内存 LRU（{@link SkinCacheMemory}）与文件持久化
 * （{@link SkinCacheFile}）。</p>
 *
 * <p>设计说明：任务原文写 {@code get(SkinData key)}，但 {@link SkinData} 仅含
 * texture+signature，无标识符字段，而查找发生在 texture 尚未获取之前，无法以
 * SkinData 作为查找键。故采用 {@code String identifier} 作为键——这是皮肤系统的
 * 标准做法，亦是最简且无冗余的设计。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public interface SkinCache {

    /**
     * 查询缓存。
     *
     * @param identifier 皮肤标识符
     * @return 命中的皮肤数据，未命中或已过期返回 null
     */
    @Nullable SkinData get(String identifier);

    /**
     * 写入缓存。
     *
     * @param identifier 皮肤标识符
     * @param skin       皮肤数据
     */
    void put(String identifier, SkinData skin);

    /**
     * 使指定标识符失效。
     *
     * @param identifier 皮肤标识符
     */
    void invalidate(String identifier);

    /** 清空全部缓存。 */
    void clear();

    /** 返回当前缓存条目数（文件实现可能为尽力而为的估算）。 */
    int size();
}
