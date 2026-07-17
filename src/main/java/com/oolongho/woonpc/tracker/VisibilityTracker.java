package com.oolongho.woonpc.tracker;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.npc.NpcImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.Set;

/**
 * 可见性追踪器：周期性扫描在线玩家，按距离与权限决定 NPC 对玩家的可见性。
 *
 * <h2>算法</h2>
 * <ol>
 *   <li>每 {@code intervalTicks}（默认 20 tick = 1s）扫描一次在线玩家</li>
 *   <li>对每个玩家遍历所有 NPC：
 *     <ul>
 *       <li>跨世界：玩家不应看到此 NPC，调用 {@link NpcImpl#hideFrom}（幂等）</li>
 *       <li>权限校验：玩家无任一权限时 {@link NpcImpl#hideFrom}</li>
 *       <li>距离判断：dist ≤ visibilityDistance → {@link NpcImpl#showTo}；否则 {@link NpcImpl#hideFrom}</li>
 *     </ul>
 *   </li>
 *   <li>玩家下线时（PlayerQuitEvent）遍历所有 NPC 调用 {@link NpcImpl#hideFrom} 清理</li>
 * </ol>
 *
 * <h2>协议层 vs 业务层</h2>
 * <p>调用 {@link NpcImpl#showTo} / {@link NpcImpl#hideFrom} 直接发包，
 * <b>不触发</b> NpcSpawnEvent / NpcDespawnEvent。理由：Tracker 的可见性切换是协议层
 * "对玩家可见"操作，业务层 NPC 已经存在（NpcCreateEvent 已触发），不应重复触发事件。</p>
 *
 * <h2>幂等性</h2>
 * <p>{@link NpcImpl#showTo} / {@link NpcImpl#hideFrom} 内部通过 targetViewers 集合
 * 检查避免重复发包，Tracker 可放心每次 tick 都调用。</p>
 *
 * <h2>性能</h2>
 * <p>时间复杂度 O(P × N)，P = 在线玩家数（通常 &lt;100），N = NPC 数（通常 &lt;1000）。
 * 总迭代 &lt;100000，间隔 20 tick，主线程负载可接受。
 * 未来如需优化可按世界分桶 NPC（避免跨世界 NPC 迭代）。</p>
 *
 * <h2>Scheduler 抽象</h2>
 * <p>当前使用 {@link Bukkit#getScheduler()}，Task 15 完成后切换为 Scheduler 接口
 * （Folia 下按玩家 region 调度）。{@link #start} / {@link #shutdown} 是 Tracker 的
 * 唯一生命周期入口，切换 Scheduler 时仅需替换内部任务调度实现。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class VisibilityTracker implements Listener {

    private final WooNPCs plugin;
    private final NpcManager npcManager;
    private final long intervalTicks;
    private BukkitTask task;

    /**
     * 构造可见性追踪器。
     *
     * @param plugin         插件实例
     * @param npcManager     NPC 管理器
     * @param intervalTicks  扫描间隔（tick），默认 20
     */
    public VisibilityTracker(WooNPCs plugin, NpcManager npcManager, long intervalTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager cannot be null");
        this.intervalTicks = intervalTicks;
    }

    /**
     * 启动追踪器：注册玩家下线监听 + 启动周期扫描任务。
     *
     * <p>重复调用安全：若已启动直接返回。</p>
     */
    public void start() {
        if (task != null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
    }

    /**
     * 停止追踪器：取消任务 + 注销监听。
     *
     * <p>不会主动 despawn 已可见玩家（插件 onDisable 时服务端自动清理客户端实体）。</p>
     */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
    }

    /** 单次扫描：遍历在线玩家更新可见性。 */
    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerVisibility(player);
        }
    }

    /**
     * 更新单个玩家对所有 NPC 的可见性。
     *
     * <p>对每个 NPC 检查世界、权限、距离，调用 {@link NpcImpl#showTo} 或 {@link NpcImpl#hideFrom}。
     * 两个方法都幂等，无需额外状态查询。</p>
     *
     * <p>本方法为 public，供 {@code PlayerTrackerListener} / {@code WorldLoadListener}
     * 在玩家传送、换世界、加入、世界加载等事件后即时触发可见性更新，
     * 不必等待周期 tick 的延迟。</p>
     *
     * @param player 目标玩家
     */
    public void updatePlayerVisibility(Player player) {
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();
        for (Npc npc : npcManager.getAll()) {
            NpcData data = npc.getData();
            Location npcLoc = data.location();
            // 跨世界：玩家不应看到此 NPC
            if (!Objects.equals(npcLoc.getWorld(), world)) {
                ((NpcImpl) npc).hideFrom(player);
                continue;
            }
            // 权限校验：无任一权限则隐藏
            if (!hasVisibilityPermission(player, data)) {
                ((NpcImpl) npc).hideFrom(player);
                continue;
            }
            // 距离判断（用 distanceSquared 避免平方根开销）
            double visDist = data.visibilityDistance();
            if (playerLoc.distanceSquared(npcLoc) <= visDist * visDist) {
                ((NpcImpl) npc).showTo(player);
            } else {
                ((NpcImpl) npc).hideFrom(player);
            }
        }
    }

    /**
     * 检查玩家是否满足 NPC 可见性权限要求。
     *
     * <p>权限集合为空表示无限制；非空时玩家拥有任一权限即通过（OR 语义）。</p>
     *
     * @param player 玩家
     * @param data   NPC 数据
     * @return 满足权限要求返回 true
     */
    private static boolean hasVisibilityPermission(Player player, NpcData data) {
        Set<String> perms = data.visibilityPermissions();
        if (perms.isEmpty()) {
            return true;
        }
        for (String perm : perms) {
            if (player.hasPermission(perm)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 玩家下线时清理所有 NPC 对该玩家的可见性。
     *
     * <p>调用 {@link NpcImpl#hideFrom} 从 targetViewers 与 visiblePlayers 集合移除玩家。
     * 虽然玩家已下线，despawn 包无效，但集合清理是必要的——避免玩家重新上线时残留状态。</p>
     *
     * @param event 玩家退出事件
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        for (Npc npc : npcManager.getAll()) {
            ((NpcImpl) npc).hideFrom(player);
        }
    }
}
