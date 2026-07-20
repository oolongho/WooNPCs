package com.oolongho.woonpc.tracker;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.event.NpcCreateEvent;
import com.oolongho.woonpc.event.NpcDeleteEvent;
import com.oolongho.woonpc.npc.NpcImpl;
import com.oolongho.woonpc.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可见性追踪器：per-NPC region timer 调度，按距离与权限决定 NPC 对玩家的可见性。
 *
 * <h2>调度模型</h2>
 * <p>每个 NPC 注册独立的 {@link Scheduler#runAtTimer} 任务，绑定 NPC 当前 location：</p>
 * <ul>
 *   <li><b>Paper</b>：退化为全局主线程定时任务（与原 runTaskTimer 行为一致）</li>
 *   <li><b>Folia</b>：调度到 NPC 所在 region，每 NPC 独立 tick，避免多 region 重复扫描</li>
 * </ul>
 *
 * <h2>算法（单 NPC 单次扫描）</h2>
 * <ol>
 *   <li>读取 NPC 当前 location / world</li>
 *   <li>检测跨世界：若 NPC 当前 world != 注册时的 world，重新注册（cancel 旧 timer + 新建）</li>
 *   <li>遍历 NPC 所在世界的在线玩家：
 *     <ul>
 *       <li>权限校验失败 → {@link NpcImpl#hideFrom}</li>
 *       <li>距离 ≤ visibilityDistance → {@link NpcImpl#showTo}；否则 {@link NpcImpl#hideFrom}</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h2>生命周期</h2>
 * <ul>
 *   <li>{@link NpcCreateEvent} → 自动注册 per-NPC timer</li>
 *   <li>{@link NpcDeleteEvent} → 自动取消 timer</li>
 *   <li>跨世界（在 scan 回调内自检测）→ cancel + 重新注册</li>
 *   <li>{@link PlayerQuitEvent} → 遍历所有 NPC 清理该玩家可见性</li>
 * </ul>
 *
 * <h2>协议层 vs 业务层</h2>
 * <p>调用 {@link NpcImpl#showTo} / {@link NpcImpl#hideFrom} 直接发包，
 * <b>不触发</b> NpcSpawnEvent / NpcDespawnEvent。</p>
 *
 * <h2>幂等性</h2>
 * <p>{@link NpcImpl#showTo} / {@link NpcImpl#hideFrom} 内部通过 targetViewers 集合
 * 检查避免重复发包，Tracker 可放心每次 tick 都调用。</p>
 *
 * <h2>事件驱动入口</h2>
 * <p>{@link #updatePlayerVisibility} 供 {@code PlayerTrackerListener} / {@code WorldLoadListener}
 * 在玩家传送、换世界、加入、世界加载等事件后即时触发可见性更新（遍历所有 NPC）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class VisibilityTracker implements Listener {

    private final WooNPCs plugin;
    private final NpcManager npcManager;
    private final Scheduler scheduler;
    private final long intervalTicks;

    /** per-NPC timer 句柄 + 注册时的世界 UID（用于跨世界自检测） */
    private final ConcurrentHashMap<UUID, NpcTracker> npcTrackers = new ConcurrentHashMap<>();

    private record NpcTracker(Scheduler.TaskHandle handle, UUID worldUid) {
    }

    /**
     * 构造可见性追踪器。
     *
     * @param plugin         插件实例
     * @param npcManager     NPC 管理器
     * @param scheduler      调度器（per-NPC region timer 使用）
     * @param intervalTicks  扫描间隔（tick），默认 20
     */
    public VisibilityTracker(WooNPCs plugin, NpcManager npcManager, Scheduler scheduler, long intervalTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.intervalTicks = intervalTicks;
    }

    /**
     * 启动追踪器：注册 Listener + 为所有现有 NPC 注册 per-NPC timer。
     *
     * <p>重复调用安全：registerNpc 内部会先取消旧 timer。</p>
     */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Npc npc : npcManager.getAll()) {
            registerNpc(npc);
        }
    }

    /**
     * 停止追踪器：取消所有 per-NPC timer + 注销监听。
     *
     * <p>不会主动 despawn 已可见玩家（插件 onDisable 时服务端自动清理客户端实体）。</p>
     */
    public void shutdown() {
        for (NpcTracker t : npcTrackers.values()) {
            t.handle().cancel();
        }
        npcTrackers.clear();
        HandlerList.unregisterAll(this);
    }

    // ==================== per-NPC timer 注册 / 注销 ====================

    /**
     * 为单个 NPC 注册 per-NPC region timer（绑定 NPC 当前 location）。
     *
     * <p>若已存在旧 timer，先取消再注册（幂等）。</p>
     *
     * @param npc 目标 NPC
     */
    private void registerNpc(Npc npc) {
        Location loc = npc.getData().location();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        UUID npcId = npc.getId();
        unregisterNpc(npcId);
        Scheduler.TaskHandle handle = scheduler.runAtTimer(loc, () -> scanNpc(npcId), intervalTicks, intervalTicks);
        npcTrackers.put(npcId, new NpcTracker(handle, world.getUID()));
    }

    /**
     * 取消单个 NPC 的 timer。
     *
     * @param npcId NPC UUID
     */
    private void unregisterNpc(UUID npcId) {
        NpcTracker t = npcTrackers.remove(npcId);
        if (t != null) {
            t.handle().cancel();
        }
    }

    // ==================== 单次扫描 ====================

    /**
     * 单次扫描指定 NPC 对所有（其所在世界）在线玩家的可见性。
     *
     * <p>回调内自检：NPC 已被删除 → 注销；NPC 跨世界 → 重新注册并立即扫描。</p>
     *
     * @param npcId NPC UUID
     */
    private void scanNpc(UUID npcId) {
        Npc npc = npcManager.getById(npcId).orElse(null);
        if (npc == null) {
            unregisterNpc(npcId);
            return;
        }
        NpcData data = npc.getData();
        Location npcLoc = data.location();
        World currentWorld = npcLoc.getWorld();

        // 跨世界检测：注册时的 worldUid != 当前世界 → 重新注册（新 timer 绑定新世界 region）
        NpcTracker entry = npcTrackers.get(npcId);
        if (entry != null && currentWorld != null && !entry.worldUid().equals(currentWorld.getUID())) {
            registerNpc(npc);
            return;
        }
        if (currentWorld == null) {
            return;
        }
        // 遍历 NPC 所在世界的玩家（world.getPlayers 已按世界过滤）
        for (Player player : currentWorld.getPlayers()) {
            updateNpcVisibilityForPlayer(npc, data, npcLoc, player);
        }
    }

    /**
     * 更新单个 NPC 对单个玩家的可见性。
     *
     * <p>根据 {@link #shouldShow} 结果调用 {@link NpcImpl#showTo} 或 {@link NpcImpl#hideFrom}。
     * 两个方法都幂等。</p>
     *
     * @param npc     NPC
     * @param data    NPC 数据快照
     * @param npcLoc  NPC 位置
     * @param player  目标玩家
     */
    private void updateNpcVisibilityForPlayer(Npc npc, NpcData data, Location npcLoc, Player player) {
        if (shouldShow(data, npcLoc, player)) {
            ((NpcImpl) npc).showTo(player);
        } else {
            ((NpcImpl) npc).hideFrom(player);
        }
    }

    /**
     * 事件驱动入口：更新单个玩家对所有 NPC 的可见性。
     *
     * <p>由 {@code PlayerTrackerListener} / {@code WorldLoadListener} 在玩家传送、换世界、
     * 加入、世界加载等事件后即时触发，遍历所有 NPC 调用
     * {@link #updateNpcVisibilityForPlayer}（幂等）。</p>
     *
     * @param player 目标玩家
     */
    public void updatePlayerVisibility(Player player) {
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();
        for (Npc npc : npcManager.getAll()) {
            NpcData data = npc.getData();
            Location npcLoc = data.location();
            if (shouldShow(data, npcLoc, player, playerLoc, world)) {
                ((NpcImpl) npc).showTo(player);
            } else {
                ((NpcImpl) npc).hideFrom(player);
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 判断 NPC 是否应对玩家可见（同世界 + 权限 + 距离三重检查）。
     *
     * <p>抽取自原 {@code updateNpcVisibilityForPlayer} 与 {@code updatePlayerVisibility} 的
     * 重复逻辑，避免后续维护时双处修改。</p>
     *
     * @param data     NPC 数据快照
     * @param npcLoc   NPC 位置
     * @param player   目标玩家
     * @return true=应显示，false=应隐藏
     */
    private static boolean shouldShow(NpcData data, Location npcLoc, Player player) {
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();
        return shouldShow(data, npcLoc, player, playerLoc, world);
    }

    /**
     * 判断 NPC 是否应对玩家可见（同世界 + 权限 + 距离三重检查）。
     *
     * <p>调用方已持有 playerLoc / world 时使用本重载避免重复 {@link Player#getLocation()}
     * 调用（{@code updatePlayerVisibility} 遍历所有 NPC 时复用同一份玩家状态）。</p>
     *
     * @param data      NPC 数据快照
     * @param npcLoc    NPC 位置
     * @param player    目标玩家
     * @param playerLoc 玩家位置（调用方提供）
     * @param world     玩家所在世界（调用方提供）
     * @return true=应显示，false=应隐藏
     */
    private static boolean shouldShow(NpcData data, Location npcLoc, Player player,
                                      Location playerLoc, World world) {
        if (!Objects.equals(npcLoc.getWorld(), world)) {
            return false;
        }
        if (!hasVisibilityPermission(player, data)) {
            return false;
        }
        double visDist = data.visibilityDistance();
        return playerLoc.distanceSquared(npcLoc) <= visDist * visDist;
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

    // ==================== 生命周期监听 ====================

    /**
     * NPC 创建时自动注册 per-NPC timer。
     *
     * @param event NPC 创建事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onNpcCreate(NpcCreateEvent event) {
        registerNpc(event.getNpc());
    }

    /**
     * NPC 删除时自动取消 per-NPC timer。
     *
     * @param event NPC 删除事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onNpcDelete(NpcDeleteEvent event) {
        unregisterNpc(event.getNpc().getId());
    }

    /**
     * 玩家下线时清理所有 NPC 对该玩家的可见性。
     *
     * <p>调用 {@link NpcImpl#hideFrom} 从 targetViewers 与 visiblePlayers 集合移除玩家。
     * 虽然玩家已下线 despawn 包无效，但集合清理必要——避免玩家重新上线时残留状态。</p>
     *
     * @param event 玩家退出事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        for (Npc npc : npcManager.getAll()) {
            ((NpcImpl) npc).hideFrom(player);
        }
    }
}
