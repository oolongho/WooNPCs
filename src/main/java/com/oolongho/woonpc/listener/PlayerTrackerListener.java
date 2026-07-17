package com.oolongho.woonpc.listener;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.tracker.VisibilityTracker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * 玩家位置变化监听器：在传送 / 换世界 / 加入事件后即时触发可见性更新。
 *
 * <p>弥补 {@link VisibilityTracker} 周期扫描（默认 1s 间隔）的延迟，
 * 让玩家在跨世界或长距离传送后立刻看到/失去 NPC。</p>
 *
 * <h2>覆盖场景</h2>
 * <ul>
 *   <li>{@link PlayerTeleportEvent}：长距离传送，距离判断可能从 "可见" 变 "不可见" 或反之</li>
 *   <li>{@link PlayerChangedWorldEvent}：跨世界，原世界 NPC 应隐藏，新世界 NPC 应显示</li>
 *   <li>{@link PlayerJoinEvent}：玩家加入，首次建立可见性（与 {@link NpcInteractListener#onPlayerJoin}
 *       监听同一事件但职责不同，互不冲突）</li>
 * </ul>
 *
 * <h2>调度策略</h2>
 * <p>所有事件均延迟 1 tick 调用 {@link VisibilityTracker#updatePlayerVisibility}：
 * 传送事件触发时玩家 location 尚未更新到最终位置，需等下一 tick 才能拿到正确坐标。</p>
 *
 * <h2>不监听 PlayerQuitEvent</h2>
 * <p>{@link VisibilityTracker#onPlayerQuit} 已处理玩家下线的可见性清理，
 * 本监听器不重复。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class PlayerTrackerListener implements Listener {

    private final WooNPCs plugin;
    private final VisibilityTracker tracker;

    /**
     * 构造玩家追踪监听器。
     *
     * @param plugin  插件实例
     * @param tracker 可见性追踪器
     */
    public PlayerTrackerListener(WooNPCs plugin, VisibilityTracker tracker) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.tracker = Objects.requireNonNull(tracker, "tracker cannot be null");
    }

    /**
     * 玩家传送：延迟 1 tick 触发可见性更新。
     *
     * <p>使用 {@link EventPriority#MONITOR}：在其他插件处理传送逻辑之后再触发，
     * 避免被取消的传送也触发更新。监听器不取消事件。</p>
     *
     * @param event 传送事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> tracker.updatePlayerVisibility(player));
    }

    /**
     * 玩家换世界：延迟 1 tick 触发可见性更新（跨世界 spawn/despawn 即时生效）。
     *
     * @param event 换世界事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> tracker.updatePlayerVisibility(player));
    }

    /**
     * 玩家加入：延迟 1 tick 触发首次可见性建立。
     *
     * <p>玩家加入瞬间 location 已就绪，但延迟 1 tick 确保其他加入处理完成
     * （如权限插件加载完毕）。</p>
     *
     * @param event 加入事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> tracker.updatePlayerVisibility(player));
    }
}
