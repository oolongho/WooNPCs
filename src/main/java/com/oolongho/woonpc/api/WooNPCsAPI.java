package com.oolongho.woonpc.api;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.actions.ActionManager;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * WooNPCs 静态门面（公共 API）。
 *
 * <p>外部插件通过本类访问 WooNPCs 的核心管理器与查询接口，避免直接持有插件主类引用。
 * 所有方法在插件 onEnable 完成后才可调用，否则抛出 {@link IllegalStateException}。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * NpcManager mgr = WooNPCsAPI.getNpcManager();
 * Optional<Npc> npc = WooNPCsAPI.lookup("shop_keeper");
 * npc.ifPresent(n -> n.setDisplayName("<green>Hello"));
 * }</pre>
 *
 * <h2>线程安全</h2>
 * <p>{@link #initialize} / {@link #shutdown} 通过 volatile 字段保证可见性。
 * getter 在并发环境下读取安全；查询语义委托给底层 {@link NpcManager}
 * （基于 {@link java.util.concurrent.ConcurrentHashMap}）。</p>
 *
 * @author oolongho
 */
public final class WooNPCsAPI {

    /** 插件实例引用，volatile 保证多线程可见性 */
    private static volatile WooNPCs instance;

    private WooNPCsAPI() {
    }

    /**
     * 注入插件实例（由 {@code WooNPCs.onEnable} 在所有组件装配完成后调用）。
     *
     * @param plugin 插件实例，不可为 null
     */
    public static void initialize(WooNPCs plugin) {
        instance = plugin;
    }

    /**
     * 清除引用（由 {@code WooNPCs.onDisable} 在清理开始时调用）。
     */
    public static void shutdown() {
        instance = null;
    }

    /**
     * 获取插件实例（内部使用，外部应优先使用本类其他静态方法）。
     *
     * @return 插件实例
     * @throws IllegalStateException 当插件未启用
     */
    private static WooNPCs requireInstance() {
        WooNPCs snap = instance;
        if (snap == null) {
            throw new IllegalStateException("WooNPCs is not enabled (API not initialized)");
        }
        return snap;
    }

    /**
     * 获取 NPC 管理器。
     *
     * @return NPC 管理器实例
     */
    public static NpcManager getNpcManager() {
        return requireInstance().getNpcManager();
    }

    /**
     * 获取皮肤管理器。
     *
     * @return 皮肤管理器实例
     */
    public static SkinManager getSkinManager() {
        return requireInstance().getSkinManager();
    }

    /**
     * 获取动作管理器。
     *
     * @return 动作管理器实例
     */
    public static ActionManager getActionManager() {
        return requireInstance().getActionManager();
    }

    /**
     * 按 UUID 或名称查找 NPC。
     *
     * <p>查找策略：先尝试将 {@code idOrName} 当作 UUID 字符串解析（支持带/不带连字符），
     * 若解析成功则按 UUID 查询；若 UUID 未命中或解析失败，再按名称查找。</p>
     *
     * @param idOrName UUID 字符串或 NPC 名称，null/空白返回 {@link Optional#empty()}
     * @return 匹配的 NPC，未找到返回 {@link Optional#empty()}
     */
    public static Optional<Npc> lookup(@Nullable String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return Optional.empty();
        }
        NpcManager mgr = getNpcManager();
        try {
            UUID uuid = UUID.fromString(idOrName);
            Optional<Npc> byId = mgr.getById(uuid);
            if (byId.isPresent()) {
                return byId;
            }
        } catch (IllegalArgumentException ignored) {
            // 非 UUID 字符串，按名称查找
        }
        return mgr.getByName(idOrName);
    }
}
