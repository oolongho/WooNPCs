package com.oolongho.woonpc.api;

import com.oolongho.woonpc.skin.SkinData;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * 皮肤管理器：根据标识符异步解析玩家皮肤。
 *
 * <p>标识符格式：</p>
 * <ul>
 *   <li>玩家名（如 {@code "Notch"}）→ Mojang 优先，Ashcon 兜底</li>
 *   <li>UUID（带/不带连字符）→ Mojang 优先，Ashcon 兜底</li>
 *   <li>{@code mineskin:<url>} → MineSkin 从图片 URL 生成</li>
 * </ul>
 *
 * <h2>异步约定</h2>
 * <p>{@link #getSkin(String, Consumer)} 的回调<b>始终在异步线程执行</b>。
 * 调用方（如 NpcManagerImpl 或命令处理器）若需更新 {@code NpcData.skin} 或触发 NPC 重新 spawn，
 * 必须自行用 Bukkit/Paper scheduler 切回主线程，例如：</p>
 * <pre>{@code
 * skinManager.getSkin(identifier, skin -> {
 *     Bukkit.getScheduler().runTask(plugin, () -> {
 *         npc.update(npc.getData().withSkin(skin));
 *     });
 * });
 * }</pre>
 *
 * <p>仅查缓存且需同步返回的场景使用 {@link #getSkinSync(String)}，它在调用线程执行、仅查内存缓存，
 * 未命中返回 null，不会发起网络请求、不阻塞。</p>
 *
 * @author oolongho
 */
public interface SkinManager {

    /**
     * 异步获取皮肤，结果通过回调返回（回调在异步线程执行）。
     *
     * <p>查找顺序：内存缓存 → 文件缓存（回填内存）→ Mojang/Ashcon 或 MineSkin → 写入双层缓存。
     * 全部失败回调 {@link SkinData#defaultSkin()}。</p>
     *
     * @param identifier 皮肤标识符（玩家名 / UUID / {@code mineskin:<url>}）
     * @param callback   结果回调，在异步线程执行
     */
    void getSkin(String identifier, Consumer<SkinData> callback);

    /**
     * 同步快速路径：仅查询内存缓存，未命中返回 null。
     *
     * <p>不访问文件、不发起网络请求，可在主线程安全调用。</p>
     *
     * @param identifier 皮肤标识符
     * @return 内存缓存命中则返回皮肤数据，否则 null
     */
    @Nullable SkinData getSkinSync(String identifier);
}
