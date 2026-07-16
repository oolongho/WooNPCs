package com.oolongho.woonpc.tracker;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.npc.NpcImpl;
import com.oolongho.woonpc.util.MathUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 头部朝向追踪器：对 {@code turnToPlayer=true} 的 NPC 平滑插值头部朝向附近玩家。
 *
 * <h2>算法</h2>
 * <ol>
 *   <li>每 {@code intervalTicks}（默认 2 tick = 100ms）扫描一次所有 NPC</li>
 *   <li>跳过 {@code turnToPlayer=false} 的 NPC</li>
 *   <li>在 NPC 所在世界查找距离最近的玩家（距离 ≤ {@code turnToPlayerDistance}）</li>
 *   <li>计算目标 yaw/pitch 朝向该玩家</li>
 *   <li>对 yaw 进行插值（每次朝目标靠近 20%），pitch 直接设置（俯仰角变化小无需插值）</li>
 *   <li>调用 {@link NpcImpl#setHeadRotation} 发包</li>
 * </ol>
 *
 * <h2>插值原理</h2>
 * <p>使用 {@link MathUtil#lerpAngle}，自动处理 -180°~180° 跨越，保证走最短弧。
 * 步长 0.2 表示每次插值朝目标靠近 20%，5-10 tick 内可收敛到目标。</p>
 *
 * <h2>状态记录</h2>
 * <p>每个 NPC 的当前 yaw 记录在 {@link #currentYaw} 中（不修改 NpcData.location.yaw，
 * 因为 location 是 NPC 的"位置"而非"朝向"）。NPC 被移除时条目不主动清理——
 * UUID 不会复用，遗留条目内存占用可忽略，下次 {@link #shutdown} 时清理。</p>
 *
 * <h2>性能</h2>
 * <p>时间复杂度 O(N × P_world)，N = NPC 数，P_world = 单世界玩家数（通常 &lt;50）。
 * 间隔 2 tick，每秒 10 次，主线程负载可接受。</p>
 *
 * <h2>Scheduler 抽象</h2>
 * <p>当前使用 {@link Bukkit#getScheduler()}，Task 15 完成后切换为 Scheduler 接口
 * （Folia 下按 NPC 所在 region 调度）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class LookTracker {

    /** 插值步长：每次朝目标 yaw 靠近 20%（5 次插值可收敛到目标的 67%） */
    private static final float LERP_STEP = 0.2f;

    private final WooNPCs plugin;
    private final NpcManager npcManager;
    private final long intervalTicks;
    /** NPC UUID → 当前 yaw（度），用于跨 tick 累积插值 */
    private final Map<UUID, Float> currentYaw = new ConcurrentHashMap<>();
    private BukkitTask task;

    /**
     * 构造头部朝向追踪器。
     *
     * @param plugin        插件实例
     * @param npcManager    NPC 管理器
     * @param intervalTicks 插值间隔（tick），默认 2
     */
    public LookTracker(WooNPCs plugin, NpcManager npcManager, long intervalTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager cannot be null");
        this.intervalTicks = intervalTicks;
    }

    /**
     * 启动追踪器：开始周期插值任务。
     *
     * <p>重复调用安全：若已启动直接返回。</p>
     */
    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
    }

    /**
     * 停止追踪器：取消任务 + 清理状态。
     */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        currentYaw.clear();
    }

    /** 单次扫描：遍历所有 NPC 更新头部朝向。 */
    private void tick() {
        for (Npc npc : npcManager.getAll()) {
            updateNpcHeadRotation(npc);
        }
    }

    /**
     * 更新单个 NPC 的头部朝向。
     *
     * <p>查找 NPC 附近最近的玩家，计算目标 yaw/pitch，对 yaw 插值后发包。</p>
     *
     * @param npc 目标 NPC
     */
    private void updateNpcHeadRotation(Npc npc) {
        NpcData data = npc.getData();
        if (!data.turnToPlayer()) {
            return;
        }
        Location npcLoc = data.location();
        World world = npcLoc.getWorld();
        if (world == null) {
            return;
        }
        double triggerDist = data.turnToPlayerDistance();
        double triggerDistSq = triggerDist * triggerDist;

        // 查找距离最近的玩家（world.getPlayers() 已按世界过滤）
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
            return; // 附近无玩家，保持当前朝向
        }

        // 计算目标 yaw/pitch（与 NpcImpl.lookAt 一致的公式）
        Location playerLoc = nearest.getLocation();
        double dx = playerLoc.getX() - npcLoc.getX();
        double dy = playerLoc.getY() - npcLoc.getY();
        double dz = playerLoc.getZ() - npcLoc.getZ();
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float targetPitch = r == 0.0 ? 0.0f : (float) Math.toDegrees(Math.asin(-dy / r));

        // yaw 插值（首次使用 NPC 的 location.yaw 作为起点）
        float curYaw = currentYaw.computeIfAbsent(npc.getId(), k -> npcLoc.getYaw());
        float newYaw = MathUtil.lerpAngle(curYaw, targetYaw, LERP_STEP);
        currentYaw.put(npc.getId(), newYaw);

        // pitch 不插值（俯仰角变化小，直接设置）
        ((NpcImpl) npc).setHeadRotation(newYaw, targetPitch);
    }
}
