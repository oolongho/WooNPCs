package com.oolongho.woonpc.listener;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.tracker.VisibilityTracker;
import com.oolongho.woonpc.util.Scheduler;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * 世界加载监听器：WorldLoad 后触发该世界在线玩家的可见性更新。
 *
 * <p>使用场景：服务器启动时若 NPC 创建早于世界加载（如 Multiverse 异步加载世界），
 * NPC 的 {@code spawn} 包可能在该世界尚无玩家时发送失败。
 * 本监听器在世界加载完成后立即触发可见性更新，确保该世界玩家尽快看到 NPC，
 * 不必等待 {@link VisibilityTracker} 周期 tick。</p>
 *
 * <h2>实现策略</h2>
 * <p>遍历新加载世界的在线玩家，调用 {@link VisibilityTracker#updatePlayerVisibility}。
 * 该方法内部遍历所有 NPC 按世界 / 距离 / 权限决策 showTo / hideFrom（幂等）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class WorldLoadListener implements Listener {

    private final WooNPCs plugin;
    private final VisibilityTracker tracker;
    private final Scheduler scheduler;

    /**
     * 构造世界加载监听器。
     *
     * @param plugin    插件实例
     * @param tracker   可见性追踪器
     * @param scheduler 调度器（切回玩家所在 region 使用）
     */
    public WorldLoadListener(WooNPCs plugin, VisibilityTracker tracker, Scheduler scheduler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.tracker = Objects.requireNonNull(tracker, "tracker cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
    }

    /**
     * 世界加载：立即遍历该世界玩家，触发可见性更新。
     *
     * <p>{@link WorldLoadEvent} 触发时世界已加载完成（实体、区块就绪），
     * 无需延迟即可调用 {@link World#getPlayers}。</p>
     *
     * <p>调度策略：通过 {@link Scheduler#runAtEntity} 绑定每个玩家所在 region，
     * Folia 上确保回调内对玩家 API 的调用（tracker.showTo/hideFrom 发包等）线程安全。</p>
     *
     * @param event 世界加载事件
     */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        for (Player player : world.getPlayers()) {
            scheduler.runAtEntity(player, () -> tracker.updatePlayerVisibility(player));
        }
    }
}
