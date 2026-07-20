package com.oolongho.woonpc.tracker;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcField;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.event.NpcCreateEvent;
import com.oolongho.woonpc.event.NpcDeleteEvent;
import com.oolongho.woonpc.event.NpcModifyEvent;
import com.oolongho.woonpc.npc.NpcImpl;
import com.oolongho.woonpc.util.MathUtil;
import com.oolongho.woonpc.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 头部朝向追踪器：per-NPC region timer 平滑插值头部朝向附近玩家。
 *
 * <h2>调度模型</h2>
 * <p>仅对 {@code turnToPlayer=true} 的 NPC 注册独立的 {@link Scheduler#runAtTimer}：</p>
 * <ul>
 *   <li><b>Paper</b>：退化为全局主线程定时任务</li>
 *   <li><b>Folia</b>：调度到 NPC 所在 region，每 NPC 独立 tick</li>
 * </ul>
 *
 * <h2>算法（单 NPC 单次扫描）</h2>
 * <ol>
 *   <li>读取 NPC 当前 location / world（跨世界自检测后必要时重新注册）</li>
 *   <li>在 NPC 所在世界查找距离最近的玩家（距离 ≤ {@code turnToPlayerDistance}）</li>
 *   <li>计算目标 yaw/pitch</li>
 *   <li>对 yaw 进行插值（每次朝目标靠近 20%），pitch 直接设置</li>
 *   <li>调用 {@link NpcImpl#setHeadRotation} 发包</li>
 * </ol>
 *
 * <h2>插值原理</h2>
 * <p>使用 {@link MathUtil#lerpAngle}，自动处理 -180°~180° 跨越，保证走最短弧。
 * 步长 0.2 表示每次插值朝目标靠近 20%。</p>
 *
 * <h2>状态记录</h2>
 * <p>每个 NPC 的当前 yaw 记录在 {@link #currentYaw} 中（不修改 NpcData.location.yaw，
 * 因为 location 是 NPC 的"位置"而非"朝向"）。NPC 删除时由 {@link #onNpcDelete} 清理。</p>
 *
 * <h2>生命周期</h2>
 * <ul>
 *   <li>{@link NpcCreateEvent}：仅 {@code turnToPlayer=true} 才注册</li>
 *   <li>{@link NpcDeleteEvent}：取消 timer + 清理 yaw 状态</li>
 *   <li>{@link NpcModifyEvent}（字段 = {@link NpcField#TURN_TO_PLAYER}）：
 *       切换为 true 注册，切换为 false 取消并清理 yaw</li>
 *   <li>跨世界（scan 回调内自检测）→ cancel + 重新注册</li>
 * </ul>
 *
 * <h2>幂等性</h2>
 * <p>{@link #registerNpc} 内部先调用 {@link #unregisterNpc} 取消旧 timer，重复调用安全。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class LookTracker implements Listener {

    /** 插值步长：每次朝目标 yaw 靠近 20%（5 次插值可收敛到目标的 67%） */
    private static final float LERP_STEP = 0.2f;

    private final WooNPCs plugin;
    private final NpcManager npcManager;
    private final Scheduler scheduler;
    private final long intervalTicks;

    /** per-NPC timer 句柄 + 注册时的世界 UID（用于跨世界自检测） */
    private final ConcurrentHashMap<UUID, NpcTracker> npcTrackers = new ConcurrentHashMap<>();

    /** NPC UUID → 当前 yaw（度），用于跨 tick 累积插值 */
    private final Map<UUID, Float> currentYaw = new ConcurrentHashMap<>();

    private record NpcTracker(Scheduler.TaskHandle handle, UUID worldUid) {
    }

    /**
     * 构造头部朝向追踪器。
     *
     * @param plugin         插件实例
     * @param npcManager     NPC 管理器
     * @param scheduler      调度器（per-NPC region timer 使用）
     * @param intervalTicks  插值间隔（tick），默认 2
     */
    public LookTracker(WooNPCs plugin, NpcManager npcManager, Scheduler scheduler, long intervalTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.intervalTicks = intervalTicks;
    }

    /**
     * 启动追踪器：注册 Listener + 为所有现有 turnToPlayer=true 的 NPC 注册 per-NPC timer。
     */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Npc npc : npcManager.getAll()) {
            registerNpc(npc);
        }
    }

    /**
     * 停止追踪器：取消所有 per-NPC timer + 清理状态 + 注销监听。
     */
    public void shutdown() {
        for (NpcTracker t : npcTrackers.values()) {
            t.handle().cancel();
        }
        npcTrackers.clear();
        currentYaw.clear();
        HandlerList.unregisterAll(this);
    }

    // ==================== per-NPC timer 注册 / 注销 ====================

    /**
     * 为单个 NPC 注册 per-NPC region timer（仅 turnToPlayer=true 才注册）。
     *
     * <p>若已存在旧 timer，先取消再注册（幂等）。</p>
     *
     * @param npc 目标 NPC
     */
    private void registerNpc(Npc npc) {
        NpcData data = npc.getData();
        if (!data.turnToPlayer()) {
            return;
        }
        Location loc = data.location();
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
     * 取消单个 NPC 的 timer + 清理 yaw 状态。
     *
     * @param npcId NPC UUID
     */
    private void unregisterNpc(UUID npcId) {
        NpcTracker t = npcTrackers.remove(npcId);
        if (t != null) {
            t.handle().cancel();
        }
        currentYaw.remove(npcId);
    }

    // ==================== 单次扫描 ====================

    /**
     * 单次扫描指定 NPC：跨世界检测 + 查找最近玩家 + 插值发包。
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
        if (!data.turnToPlayer()) {
            // 极端情况：modify 事件未触发就已变更（理论不会，但防御）
            unregisterNpc(npcId);
            return;
        }
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
        updateNpcHeadRotation(npc, data, npcLoc, currentWorld);
    }

    /**
     * 查找 NPC 附近最近的玩家，计算目标 yaw/pitch，对 yaw 插值后发包。
     *
     * @param npc       NPC
     * @param data      NPC 数据快照
     * @param npcLoc    NPC 位置
     * @param world     NPC 所在世界
     */
    private void updateNpcHeadRotation(Npc npc, NpcData data, Location npcLoc, World world) {
        double triggerDist = data.turnToPlayerDistance();
        double triggerDistSq = triggerDist * triggerDist;

        Player nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Player player : world.getPlayers()) {
            double distSq = player.getLocation().distanceSquared(npcLoc);
            if (distSq <= triggerDistSq && distSq < nearestDistSq) {
                nearest = player;
                nearestDistSq = distSq;
            }
        }
        if (nearest == null) {
            return;
        }

        Location playerLoc = nearest.getLocation();
        double dx = playerLoc.getX() - npcLoc.getX();
        double dy = playerLoc.getY() - npcLoc.getY();
        double dz = playerLoc.getZ() - npcLoc.getZ();
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float targetPitch = r == 0.0 ? 0.0f : (float) Math.toDegrees(Math.asin(-dy / r));

        float curYaw = currentYaw.computeIfAbsent(npc.getId(), k -> npcLoc.getYaw());
        float newYaw = MathUtil.lerpAngle(curYaw, targetYaw, LERP_STEP);
        currentYaw.put(npc.getId(), newYaw);

        ((NpcImpl) npc).setHeadRotation(newYaw, targetPitch);
    }

    // ==================== 生命周期监听 ====================

    /**
     * NPC 创建时按需注册 per-NPC timer（仅 turnToPlayer=true）。
     *
     * @param event NPC 创建事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onNpcCreate(NpcCreateEvent event) {
        registerNpc(event.getNpc());
    }

    /**
     * NPC 删除时取消 timer + 清理 yaw 状态。
     *
     * @param event NPC 删除事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onNpcDelete(NpcDeleteEvent event) {
        unregisterNpc(event.getNpc().getId());
    }

    /**
     * TURN_TO_PLAYER 字段变更时按需注册/注销。
     *
     * <p>仅监听 TURN_TO_PLAYER 字段，其他字段修改不触发 timer 变化。
     * LOCATION 变化由 scan 回调内跨世界自检测处理（避免每次 moveTo 都重建 timer）。</p>
     *
     * @param event NPC 修改事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onNpcModify(NpcModifyEvent event) {
        if (event.getField() != NpcField.TURN_TO_PLAYER) {
            return;
        }
        Npc npc = event.getNpc();
        boolean newValue = Boolean.TRUE.equals(event.getNewValue());
        if (newValue) {
            registerNpc(npc);
        } else {
            unregisterNpc(npc.getId());
        }
    }
}
