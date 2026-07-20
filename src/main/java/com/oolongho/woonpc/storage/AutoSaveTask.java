package com.oolongho.woonpc.storage;

import com.oolongho.woonpc.util.Scheduler;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 自动保存任务：周期性调用多个保存回调落盘内存数据。
 *
 * <p>通过 {@link Scheduler#runTimer} 调度，Paper 走主线程 / Folia 走 {@code globalRegionScheduler}。
 * 一次调度内顺序执行所有回调（如 {@code storage::saveAll} 与 {@code actionStorage::saveAll}），
 * 任一回调抛异常不中断后续回调的执行（catch + warning）。</p>
 *
 * <h2>装配方式</h2>
 * <pre>{@code
 * AutoSaveTask autoSave = new AutoSaveTask(plugin, scheduler,
 *         storage::saveAll,
 *         () -> actionStorage.saveAll(actionManager.serializeAll()));
 * autoSave.startFromConfig();
 * // onDisable：
 * autoSave.stop();
 * storage.saveAll();
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
    private final Scheduler scheduler;
    private final List<Runnable> saveCallbacks;

    /** 当前调度任务，null 表示未启动 */
    private volatile Scheduler.TaskHandle task;

    /**
     * 创建自动保存任务。
     *
     * @param plugin         插件实例
     * @param scheduler      调度器
     * @param saveCallbacks 保存回调列表（顺序执行，每个回调代表一种数据的 saveAll）
     */
    public AutoSaveTask(Plugin plugin, Scheduler scheduler, Runnable... saveCallbacks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.saveCallbacks = new ArrayList<>(Arrays.asList(saveCallbacks));
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
        task = scheduler.runTimer(this::runAllSaves, intervalTicks, intervalTicks);
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
        plugin.getLogger().info(() -> "自动保存已启用，间隔 " + seconds + " 秒（" + ticks + " tick），"
                + saveCallbacks.size() + " 个保存回调");
    }

    /**
     * 顺序执行所有保存回调，任一抛异常不中断后续。
     */
    private void runAllSaves() {
        for (Runnable cb : saveCallbacks) {
            try {
                cb.run();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("自动保存回调失败: " + e.getMessage());
            }
        }
    }

    /**
     * 停止自动保存任务。
     *
     * <p>任务未运行时调用为空操作。</p>
     */
    public void stop() {
        Scheduler.TaskHandle current = task;
        if (current != null) {
            current.cancel();
            task = null;
        }
    }
}
