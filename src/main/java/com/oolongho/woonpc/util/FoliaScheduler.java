package com.oolongho.woonpc.util;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Consumer;

/**
 * Folia 平台的 {@link Scheduler} 实现。
 *
 * <p>通过反射 + {@link MethodHandle} 委托 Folia 的 {@code GlobalRegionScheduler} /
 * {@code RegionScheduler} / {@code AsyncScheduler} / {@code EntityScheduler}。
 * Paper API 不包含这些类，故采用反射（仅 Folia 运行时调用，Paper 不会构造本类）。</p>
 *
 * <p>方法分类：
 * <ul>
 *   <li>{@code runSync*} / {@code runTimer} → {@code globalRegionScheduler}</li>
 *   <li>{@code runAt*} → {@code regionScheduler}（按 location 所在 region）</li>
 *   <li>{@code runAtEntity*} → {@code EntityScheduler}（绑定实体所在 region，实体退休后任务自动取消）</li>
 *   <li>{@code runAsync*} → {@code asyncScheduler}</li>
 * </ul>
 * </p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class FoliaScheduler implements Scheduler {

    private final Plugin plugin;
    private final Object globalRegionScheduler;
    private final Object regionScheduler;
    private final Object asyncScheduler;
    private final MethodHandle globalRun;
    private final MethodHandle globalRunDelayed;
    private final MethodHandle globalRunAtFixedRate;
    private final MethodHandle regionRun;
    private final MethodHandle regionRunDelayed;
    private final MethodHandle regionRunAtFixedRate;
    private final MethodHandle asyncRunNow;
    private final MethodHandle asyncRunDelayed;
    private final MethodHandle asyncRunAtFixedRate;
    private final MethodHandle entityGetScheduler;
    private final MethodHandle entityExecute;
    private final MethodHandle entityRunDelayed;
    private final MethodHandle entityRunAtFixedRate;
    private final MethodHandle scheduledTaskCancel;

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
        try {
            Class<?> globalClass = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class<?> regionClass = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            Class<?> asyncClass = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            Class<?> entitySchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            Class<?> scheduledTaskClass = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            Class<?> consumerClass = Consumer.class;

            this.globalRegionScheduler = plugin.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(plugin.getServer());
            this.regionScheduler = plugin.getServer().getClass().getMethod("getRegionScheduler").invoke(plugin.getServer());
            this.asyncScheduler = plugin.getServer().getClass().getMethod("getAsyncScheduler").invoke(plugin.getServer());

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            this.globalRun = lookup.findVirtual(globalClass, "run", MethodType.methodType(scheduledTaskClass, Plugin.class, consumerClass));
            this.globalRunDelayed = lookup.findVirtual(globalClass, "runDelayed", MethodType.methodType(scheduledTaskClass, Plugin.class, consumerClass, long.class));
            this.globalRunAtFixedRate = lookup.findVirtual(globalClass, "runAtFixedRate", MethodType.methodType(scheduledTaskClass, Plugin.class, consumerClass, long.class, long.class));
            this.regionRun = lookup.findVirtual(regionClass, "run", MethodType.methodType(scheduledTaskClass, Plugin.class, Location.class, consumerClass));
            this.regionRunDelayed = lookup.findVirtual(regionClass, "runDelayed", MethodType.methodType(scheduledTaskClass, Plugin.class, Location.class, consumerClass, long.class));
            this.regionRunAtFixedRate = lookup.findVirtual(regionClass, "runAtFixedRate", MethodType.methodType(scheduledTaskClass, Plugin.class, Location.class, consumerClass, long.class, long.class));
            this.asyncRunNow = lookup.findVirtual(asyncClass, "runNow", MethodType.methodType(scheduledTaskClass, Plugin.class, consumerClass));
            this.asyncRunDelayed = lookup.findVirtual(asyncClass, "runDelayed", MethodType.methodType(scheduledTaskClass, Plugin.class, consumerClass, long.class));
            this.asyncRunAtFixedRate = lookup.findVirtual(asyncClass, "runAtFixedRate", MethodType.methodType(scheduledTaskClass, Plugin.class, consumerClass, long.class, long.class));
            // EntityScheduler 方法签名（Folia 官方 JavaDoc）：
            //   boolean execute(Plugin, Runnable run, Runnable retired, long delay)
            //   ScheduledTask runDelayed(Plugin, Consumer<ScheduledTask> task, Runnable retired, long delay)
            //   ScheduledTask runAtFixedRate(Plugin, Consumer<ScheduledTask> task, Runnable retired, long initialDelay, long period)
            // retired 参数：实体退休（removed from world）后执行的回调，null 表示无退休回调。
            // 本插件业务场景（玩家可见性 / 动作链延迟 / GUI 异步回调）不需要退休回调，
            // 全部传 null —— 实体退休时任务被静默丢弃，调用方已有 isOnline / 事件监听器清理逻辑。
            this.entityGetScheduler = lookup.findVirtual(Entity.class, "getScheduler", MethodType.methodType(entitySchedulerClass));
            this.entityExecute = lookup.findVirtual(entitySchedulerClass, "execute",
                    MethodType.methodType(boolean.class, Plugin.class, Runnable.class, Runnable.class, long.class));
            this.entityRunDelayed = lookup.findVirtual(entitySchedulerClass, "runDelayed",
                    MethodType.methodType(scheduledTaskClass, Plugin.class, consumerClass, Runnable.class, long.class));
            this.entityRunAtFixedRate = lookup.findVirtual(entitySchedulerClass, "runAtFixedRate",
                    MethodType.methodType(scheduledTaskClass, Plugin.class, consumerClass, Runnable.class, long.class, long.class));
            this.scheduledTaskCancel = lookup.findVirtual(scheduledTaskClass, "cancel", MethodType.methodType(void.class));
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize FoliaScheduler", e);
        }
    }

    /** 包装 ScheduledTask 为 TaskHandle（cancel 委托 ScheduledTask.cancel）。 */
    private Scheduler.TaskHandle wrap(Object scheduledTask) {
        return () -> {
            try {
                scheduledTaskCancel.invoke(scheduledTask);
            } catch (Throwable e) {
                // 忽略：任务已执行完毕或已取消
            }
        };
    }

    /** 将 Runnable 包装为 Consumer<ScheduledTask>（Folia 回调签名为 Consumer<ScheduledTask>）。 */
    private Consumer<Object> wrapConsumer(Runnable task) {
        return t -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                // 不让单次回调异常中断 timer，记录日志后继续
                plugin.getLogger().warning("Scheduler callback failed: " + e.getMessage());
            }
        };
    }

    @Override
    public TaskHandle runSync(Runnable task) {
        try {
            return wrap(globalRun.invoke(globalRegionScheduler, plugin, wrapConsumer(task)));
        } catch (Throwable e) {
            throw new RuntimeException("FoliaScheduler.runSync failed", e);
        }
    }

    @Override
    public TaskHandle runSyncLater(Runnable task, long delayTicks) {
        try {
            return wrap(globalRunDelayed.invoke(globalRegionScheduler, plugin, wrapConsumer(task), delayTicks));
        } catch (Throwable e) {
            throw new RuntimeException("FoliaScheduler.runSyncLater failed", e);
        }
    }

    @Override
    public TaskHandle runTimer(Runnable task, long delayTicks, long periodTicks) {
        try {
            return wrap(globalRunAtFixedRate.invoke(globalRegionScheduler, plugin, wrapConsumer(task), delayTicks, periodTicks));
        } catch (Throwable e) {
            throw new RuntimeException("FoliaScheduler.runTimer failed", e);
        }
    }

    @Override
    public TaskHandle runAt(Location location, Runnable task) {
        try {
            return wrap(regionRun.invoke(regionScheduler, plugin, location, wrapConsumer(task)));
        } catch (Throwable e) {
            throw new RuntimeException("FoliaScheduler.runAt failed", e);
        }
    }

    @Override
    public TaskHandle runAtLater(Location location, Runnable task, long delayTicks) {
        try {
            return wrap(regionRunDelayed.invoke(regionScheduler, plugin, location, wrapConsumer(task), delayTicks));
        } catch (Throwable e) {
            throw new RuntimeException("FoliaScheduler.runAtLater failed", e);
        }
    }

    @Override
    public TaskHandle runAtTimer(Location location, Runnable task, long delayTicks, long periodTicks) {
        try {
            return wrap(regionRunAtFixedRate.invoke(regionScheduler, plugin, location, wrapConsumer(task), delayTicks, periodTicks));
        } catch (Throwable e) {
            throw new RuntimeException("FoliaScheduler.runAtTimer failed", e);
        }
    }

    @Override
    public TaskHandle runAtEntity(Entity entity, Runnable task) {
        try {
            Object scheduler = entityGetScheduler.invoke(entity);
            // execute 返回 boolean（true=已调度，false=scheduler 已退休），返回值无关注必要；
            // 返回 no-op TaskHandle（execute 是立即执行语义，无法取消）
            entityExecute.invoke(scheduler, plugin, wrapRunnable(task), null, 0L);
            return () -> {
            };
        } catch (Throwable e) {
            throw new RuntimeException("FoliaScheduler.runAtEntity failed", e);
        }
    }

    @Override
    public TaskHandle runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        try {
            Object scheduler = entityGetScheduler.invoke(entity);
            return wrap(entityRunDelayed.invoke(scheduler, plugin, wrapConsumer(task), null, delayTicks));
        } catch (Throwable e) {
            throw new RuntimeException("FoliaScheduler.runAtEntityLater failed", e);
        }
    }

    @Override
    public TaskHandle runAtEntityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        try {
            Object scheduler = entityGetScheduler.invoke(entity);
            return wrap(entityRunAtFixedRate.invoke(scheduler, plugin, wrapConsumer(task), null, delayTicks, periodTicks));
        } catch (Throwable e) {
            throw new RuntimeException("FoliaScheduler.runAtEntityTimer failed", e);
        }
    }

    @Override
    public TaskHandle runAsync(Runnable task) {
        try {
            return wrap(asyncRunNow.invoke(asyncScheduler, plugin, wrapConsumer(task)));
        } catch (Throwable e) {
            throw new RuntimeException("FoliaScheduler.runAsync failed", e);
        }
    }

    @Override
    public TaskHandle runAsyncLater(Runnable task, long delayTicks) {
        try {
            return wrap(asyncRunDelayed.invoke(asyncScheduler, plugin, wrapConsumer(task), delayTicks));
        } catch (Throwable e) {
            throw new RuntimeException("FoliaScheduler.runAsyncLater failed", e);
        }
    }

    @Override
    public TaskHandle runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        try {
            return wrap(asyncRunAtFixedRate.invoke(asyncScheduler, plugin, wrapConsumer(task), delayTicks, periodTicks));
        } catch (Throwable e) {
            throw new RuntimeException("FoliaScheduler.runAsyncTimer failed", e);
        }
    }

    /**
     * 将 Runnable 包装为 Runnable（execute 方法接受 Runnable 而非 Consumer）。
     * 异常隔离：避免单次回调异常影响 EntityScheduler 调度。
     */
    private Runnable wrapRunnable(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Scheduler callback failed: " + e.getMessage());
            }
        };
    }
}
