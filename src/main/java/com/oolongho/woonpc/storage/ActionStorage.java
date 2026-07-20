package com.oolongho.woonpc.storage;

import org.jetbrains.annotations.ApiStatus;

import java.util.Map;

/**
 * NPC 动作持久化存储接口。
 *
 * <p>独立于 {@link NpcStorage}（NPC 数据存储），专门负责 {@link com.oolongho.woonpc.api.actions.ActionManager}
 * 管理的 {@code npcId → trigger → List<action>} 数据的落盘与加载。</p>
 *
 * <h2>线程约定</h2>
 * <ul>
 *   <li>{@link #saveAll}：必须在主线程调用（{@code YamlConfiguration} 非线程安全）</li>
 *   <li>{@link #loadAll}：必须在主线程调用（涉及 Bukkit 文件 IO）</li>
 * </ul>
 *
 * <h2>持久化语义</h2>
 * <p>{@link #saveAll} 全量覆盖写入；{@link #loadAll} 全量重建返回。
 * 调用方（{@code WooNPCs.onEnable}）应在 {@link NpcStorage#loadAll} 完成后调用
 * {@link #loadAll} 并委托 {@code ActionManager.loadAll} 装载。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public interface ActionStorage {

    /**
     * 全量覆盖写入动作数据。
     *
     * @param data {@link com.oolongho.woonpc.api.actions.ActionManager#serializeAll} 输出的结构
     */
    void saveAll(Map<String, Object> data);

    /**
     * 全量加载动作数据。
     *
     * @return {@link com.oolongho.woonpc.api.actions.ActionManager#loadAll} 接受的结构，无文件时返回空 Map
     */
    Map<String, Object> loadAll();
}
