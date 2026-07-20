package com.oolongho.woonpc.util;

import org.jetbrains.annotations.ApiStatus;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * 调试日志开关（轻量静态工具）。
 *
 * <p>由 {@code WooNPCs.onEnable} 在装配阶段调用 {@link #init} 注入 Logger 与开关状态。
 * 之后所有 {@link #log} 调用仅在 {@code settings.debug=true} 时输出到插件日志。</p>
 *
 * <h2>设计取舍</h2>
 * <ul>
 *   <li>使用静态字段而非实例字段：调用点零开销（无方法分派开销之外的查表），
 *       适合散布在 hot path 的调试日志（如 tracker 扫描、包发送）</li>
 *   <li>volatile 字段保证 reload 后立即可见</li>
 *   <li>不依赖 {@code WooNPCs} 类：仅持有 {@link Logger}，避免循环依赖</li>
 *   <li>日志前缀 {@code [Debug]} 便于筛选</li>
 * </ul>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class DebugManager {

    private static volatile boolean enabled = false;
    private static volatile Logger logger = null;

    private DebugManager() {
    }

    /**
     * 初始化调试日志。
     *
     * @param pluginLogger 插件 Logger
     * @param debugEnabled {@code settings.debug} 配置值
     */
    public static void init(Logger pluginLogger, boolean debugEnabled) {
        logger = pluginLogger;
        enabled = debugEnabled;
    }

    /**
     * 更新调试开关（{@code /woonpc reload} 后调用）。
     *
     * @param debugEnabled 新的调试开关值
     */
    public static void reload(boolean debugEnabled) {
        enabled = debugEnabled;
    }

    /**
     * 查询调试是否启用。
     *
     * @return 启用时返回 true
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 输出调试日志（仅 {@code enabled=true} 时输出）。
     *
     * @param message 日志消息
     */
    public static void log(String message) {
        if (enabled && logger != null) {
            logger.info("[Debug] " + message);
        }
    }

    /**
     * 输出格式化调试日志。
     *
     * <p>使用 {@link Locale#ROOT} 格式化，避免 locale 差异导致输出不一致。
     * 仅在 {@code enabled=true} 时才执行 {@link String#format}，避免无谓的字符串构造。</p>
     *
     * @param format 格式串（{@link String#format} 语法）
     * @param args  格式参数
     */
    public static void log(String format, Object... args) {
        if (enabled && logger != null) {
            logger.info("[Debug] " + String.format(Locale.ROOT, format, args));
        }
    }
}
