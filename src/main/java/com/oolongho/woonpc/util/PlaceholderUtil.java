package com.oolongho.woonpc.util;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI 集成工具（软依赖）。
 *
 * <p>当服务端安装并启用 PlaceholderAPI 时，调用 {@link PlaceholderAPI#setPlaceholders}
 * 解析 {@code %...%} 占位符；未安装时原样返回输入字符串。</p>
 *
 * <h2>性能</h2>
 * <p>启用状态通过 {@code Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")} 检测，
 * 结果缓存到 {@link #ENABLED} volatile 字段，避免每次调用都查询插件管理器。
 * 重新加载 PAPI 时插件会重启，因此无需支持运行时切换。</p>
 *
 * <h2>使用约定</h2>
 * <ul>
 *   <li>需要玩家上下文的占位符（如 {@code %player_name%}）调用 {@link #setPlaceholders(Player, String)}</li>
 *   <li>无需玩家上下文（如 {@code %server_online%}）可传 {@code null}</li>
 *   <li>输入 {@code null} 时返回空串（避免 NPE）</li>
 * </ul>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class PlaceholderUtil {

    /** PlaceholderAPI 插件启用状态缓存（volatile 保证可见性） */
    private static volatile boolean ENABLED = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");

    private PlaceholderUtil() {
    }

    /**
     * 解析字符串中的 PlaceholderAPI 占位符。
     *
     * <p>当 PAPI 未启用时直接返回原字符串（无任何处理）。
     * 输入 {@code null} 返回空串。</p>
     *
     * @param player 玩家上下文，可为 null（仅服务端级占位符可用）
     * @param input 待解析字符串，可为 null
     * @return 解析后的字符串，PAPI 未启用或 input 为 null 时返回 input 或空串
     */
    public static @NotNull String setPlaceholders(@Nullable Player player, @Nullable String input) {
        if (input == null) return "";
        if (!ENABLED) return input;
        try {
            return PlaceholderAPI.setPlaceholders(player, input);
        } catch (Throwable t) {
            // PAPI 内部异常不应中断调用方逻辑
            return input;
        }
    }

    /**
     * 重新检测 PlaceholderAPI 启用状态。
     *
     * <p>仅在 {@code onEnable} 阶段（PAPI 软依赖加载后）调用一次即可。
     * 运行时 PAPI 状态变化需重启插件，无需热刷新。</p>
     */
    public static void refresh() {
        ENABLED = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /**
     * PlaceholderAPI 是否启用。
     *
     * @return true 表示 PAPI 已加载并启用
     */
    public static boolean isEnabled() {
        return ENABLED;
    }
}
