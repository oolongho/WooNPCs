package com.oolongho.woonpc.util;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

/**
 * {@link Scheduler} 工厂：检测运行平台并返回对应实现。
 *
 * <p>检测策略：反射 {@code Class.forName("io.papermc.paper.threadedregions.RegionizedServer")}
 * 不抛异常 → Folia，否则 Paper。检测结果在类加载时缓存（{@link #FOLIA}），
 * 避免每次创建 Scheduler 重复反射。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class Schedulers {

    /** Folia 平台检测结果（类加载时缓存）。 */
    public static final boolean FOLIA = detectFolia();

    private Schedulers() {
    }

    /** 检测当前运行平台是否为 Folia。 */
    public static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** 根据平台返回 {@link PaperScheduler} 或 {@link FoliaScheduler}。 */
    public static Scheduler create(Plugin plugin) {
        return FOLIA ? new FoliaScheduler(plugin) : new PaperScheduler(plugin);
    }
}
