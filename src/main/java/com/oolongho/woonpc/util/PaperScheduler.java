package com.oolongho.woonpc.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.ApiStatus;

/**
 * Paper 平台的 {@link Scheduler} 实现。
 *
 * <p>所有方法委托 {@link BukkitScheduler}。由于 Paper 无 region 概念，
 * {@code runAt*} 与 {@code runAtEntity*} 方法均退化为全局同步调度
 * （与 {@code runSync*} 行为一致）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class PaperScheduler implements Scheduler {

    private final Plugin plugin;
    private final BukkitScheduler scheduler;

    public PaperScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.scheduler = Bukkit.getScheduler();
    }

    private static Scheduler.TaskHandle wrap(BukkitTask task) {
        return task::cancel;
    }

    @Override
    public TaskHandle runSync(Runnable task) {
        return wrap(scheduler.runTask(plugin, task));
    }

    @Override
    public TaskHandle runSyncLater(Runnable task, long delayTicks) {
        return wrap(scheduler.runTaskLater(plugin, task, delayTicks));
    }

    @Override
    public TaskHandle runTimer(Runnable task, long delayTicks, long periodTicks) {
        return wrap(scheduler.runTaskTimer(plugin, task, delayTicks, periodTicks));
    }

    @Override
    public TaskHandle runAt(Location location, Runnable task) {
        // Paper 无 region 概念，退化为全局同步
        return wrap(scheduler.runTask(plugin, task));
    }

    @Override
    public TaskHandle runAtLater(Location location, Runnable task, long delayTicks) {
        return wrap(scheduler.runTaskLater(plugin, task, delayTicks));
    }

    @Override
    public TaskHandle runAtTimer(Location location, Runnable task, long delayTicks, long periodTicks) {
        return wrap(scheduler.runTaskTimer(plugin, task, delayTicks, periodTicks));
    }

    @Override
    public TaskHandle runAtEntity(Entity entity, Runnable task) {
        // Paper 无 region 概念，退化为全局同步
        return wrap(scheduler.runTask(plugin, task));
    }

    @Override
    public TaskHandle runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        return wrap(scheduler.runTaskLater(plugin, task, delayTicks));
    }

    @Override
    public TaskHandle runAtEntityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        return wrap(scheduler.runTaskTimer(plugin, task, delayTicks, periodTicks));
    }

    @Override
    public TaskHandle runAsync(Runnable task) {
        return wrap(scheduler.runTaskAsynchronously(plugin, task));
    }

    @Override
    public TaskHandle runAsyncLater(Runnable task, long delayTicks) {
        return wrap(scheduler.runTaskLaterAsynchronously(plugin, task, delayTicks));
    }

    @Override
    public TaskHandle runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        return wrap(scheduler.runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks));
    }
}
