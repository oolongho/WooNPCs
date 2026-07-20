package com.oolongho.woonpc.util;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

/**
 * 调度抽象接口，统一 Paper 与 Folia 两种平台的调度 API。
 *
 * <p>业务代码通过本接口调度任务，无需感知运行平台。{@link Schedulers#create}
 * 在插件启动时根据反射检测结果返回 {@link PaperScheduler} 或 {@link FoliaScheduler}。</p>
 *
 * <h2>方法分类</h2>
 * <ul>
 *   <li><b>全局同步</b>：{@link #runSync} / {@link #runSyncLater} / {@link #runTimer}
 *       — Paper 走主线程，Folia 走 {@code globalRegionScheduler}</li>
 *   <li><b>Region 同步</b>：{@link #runAt} / {@link #runAtLater} / {@link #runAtTimer}
 *       — Paper 退化为全局同步，Folia 走 {@code regionScheduler}（按 location 所在 region 调度）</li>
 *   <li><b>Entity 同步</b>：{@link #runAtEntity} / {@link #runAtEntityLater} / {@link #runAtEntityTimer}
 *       — Paper 退化为全局同步，Folia 走 {@code EntityScheduler}（绑定实体所在 region，
 *       实体退休后任务自动取消）</li>
 *   <li><b>异步</b>：{@link #runAsync} / {@link #runAsyncLater} / {@link #runAsyncTimer}
 *       — Paper 走异步线程池，Folia 走 {@code asyncScheduler}</li>
 * </ul>
 *
 * <h2>TaskHandle</h2>
 * <p>所有方法返回 {@link TaskHandle} 用于取消任务。{@link #cancel()} 后下次不再执行。
 * 不实现 AutoCloseable（与 {@code BukkitTask.cancel()} 一致，最简方案）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public interface Scheduler {

    /** 任务句柄，用于取消调度任务。 */
    @FunctionalInterface
    interface TaskHandle {
        /** 取消任务。若任务已执行完毕或已取消，调用无副作用。 */
        void cancel();
    }

    // ==================== 全局同步 ====================

    /** 立即在主线程（Paper）或全局 region（Folia）执行。 */
    TaskHandle runSync(Runnable task);

    /** 延迟 delayTicks 后在主线程或全局 region 执行。 */
    TaskHandle runSyncLater(Runnable task, long delayTicks);

    /** 周期性在主线程或全局 region 执行（delayTicks 后开始，每 periodTicks 一次）。 */
    TaskHandle runTimer(Runnable task, long delayTicks, long periodTicks);

    // ==================== Region 同步（按 location） ====================

    /** 立即在 location 所在 region 执行（Paper 退化为全局同步）。 */
    TaskHandle runAt(Location location, Runnable task);

    /** 延迟 delayTicks 后在 location 所在 region 执行。 */
    TaskHandle runAtLater(Location location, Runnable task, long delayTicks);

    /** 周期性在 location 所在 region 执行（用于 per-NPC timer，Folia 下随 region 自动迁移）。 */
    TaskHandle runAtTimer(Location location, Runnable task, long delayTicks, long periodTicks);

    // ==================== Entity 同步（按 entity 所在 region） ====================

    /**
     * 立即在 entity 所在 region 执行（Paper 退化为全局同步，Folia 走 EntityScheduler）。
     *
     * <p>适用于回调内调用玩家 API（sendMessage / openInventory / performCommand / 发包等）的场景，
     * Folia 上必须绑定玩家所在 region 才能安全操作玩家状态。entity 退休后任务自动取消。</p>
     */
    TaskHandle runAtEntity(Entity entity, Runnable task);

    /** 延迟 delayTicks 后在 entity 所在 region 执行（Paper 退化为全局同步）。 */
    TaskHandle runAtEntityLater(Entity entity, Runnable task, long delayTicks);

    /** 周期性在 entity 所在 region 执行（Paper 退化为全局同步）。 */
    TaskHandle runAtEntityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks);

    // ==================== 异步 ====================

    /** 立即在异步线程执行。 */
    TaskHandle runAsync(Runnable task);

    /** 延迟 delayTicks 后在异步线程执行。 */
    TaskHandle runAsyncLater(Runnable task, long delayTicks);

    /** 周期性在异步线程执行。 */
    TaskHandle runAsyncTimer(Runnable task, long delayTicks, long periodTicks);
}
