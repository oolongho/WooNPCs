package com.oolongho.woonpc.storage;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * 自动保存任务：周期性调用 {@link NpcStorage#saveAll} 落盘内存中的 NPC 数据。
 *
 * <p>当前基于 Bukkit Scheduler（{@code Bukkit.getScheduler().runTaskTimer}）实现，
 * 在主线程周期执行。Task 15 切换 Folia 后改用 Folia 的 RegionScheduler / GlobalScheduler。</p>
 *
 * <h2>装配方式</h2>
 * <pre>{@code
 * YamlNpcStorage storage = new YamlNpcStorage(plugin);
 * AutoSaveTask autoSave = new AutoSaveTask(plugin, storage);
 * autoSave.startFromConfig();   // 读取 config.yml 的 settings.auto-save-interval
 * // onDisable 时：
 * autoSave.stop();
 * storage.saveAll();            // 关闭前最后保存一次
 * }</pre>
 *
 * <h2>线程安全</h2>
 * <ul>
 *   <li>{@link #start} / {@link #stop} 通过 {@code volatile} task 字段保证可见性</li>
 *   <li>重复 {@link #start} 会先停止旧任务再启动新任务</li>
 * </ul>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class AutoSaveTask {

    /** 默认自动保存间隔（秒），与 {@code config.yml} 默认值一致 */
    private static final int DEFAULT_INTERVAL_SECONDS = 300;

    /** 每秒对应的 tick 数（用于秒 → tick 转换） */
    private static final int TICKS_PER_SECOND = 20;

    private final Plugin plugin;
    private final NpcStorage storage;

    /** 当前调度任务，null 表示未启动 */
    private volatile BukkitTask task;

    /**
     * 创建自动保存任务。
     *
     * @param plugin  插件实例
     * @param storage 存储实现
     */
    public AutoSaveTask(Plugin plugin, NpcStorage storage) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
    }

    /**
     * 启动自动保存任务。
     *
     * <p>若任务已在运行，先停止旧任务。间隔 ≤ 0 时不启动（用于禁用自动保存）。</p>
     *
     * @param intervalTicks 调度间隔（tick），必须 > 0 才会启动
     */
    public void start(int intervalTicks) {
        stop();
        if (intervalTicks <= 0) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                storage::saveAll,
                intervalTicks,
                intervalTicks
        );
    }

    /**
     * 从 {@code config.yml} 读取间隔并启动任务。
     *
     * <p>读取 {@code settings.auto-save-interval}（秒），乘以 20 转换为 tick。
     * 默认 300 秒 = 6000 tick = 5 分钟。值 ≤ 0 表示禁用自动保存。</p>
     */
    public void startFromConfig() {
        int seconds = plugin.getConfig().getInt("settings.auto-save-interval", DEFAULT_INTERVAL_SECONDS);
        if (seconds <= 0) {
            plugin.getLogger().info("自动保存已禁用（settings.auto-save-interval ≤ 0）");
            return;
        }
        int ticks = seconds * TICKS_PER_SECOND;
        start(ticks);
        plugin.getLogger().info(() -> "自动保存已启用，间隔 " + seconds + " 秒（" + ticks + " tick）");
    }

    /**
     * 停止自动保存任务。
     *
     * <p>任务未运行时调用为空操作。</p>
     */
    public void stop() {
        BukkitTask current = task;
        if (current != null) {
            current.cancel();
            task = null;
        }
    }
}
