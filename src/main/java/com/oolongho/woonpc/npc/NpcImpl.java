package com.oolongho.woonpc.npc;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcField;
import com.oolongho.woonpc.event.NpcDespawnEvent;
import com.oolongho.woonpc.event.NpcSpawnEvent;
import com.oolongho.woonpc.manager.NpcManagerImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Npc} 的具体实现（内部）。
 *
 * <p>继承 {@link Npc} 抽象基类，实现 7 个生命周期方法。持有 {@link NpcManagerImpl} 引用
 * 用于 {@link #remove()} 时注销，以及 {@code targetViewers} 集合记录"应可见"的玩家
 * （由 Task 7 的 Tracker 维护，Task 5 阶段为空集）。</p>
 *
 * <h2>线程安全</h2>
 * <ul>
 *   <li>所有生命周期方法用 {@code synchronized(this)} 保护 {@link Npc#data} 字段的读写</li>
 *   <li>{@link #getData()} / {@link #getLocation()} 覆盖为 synchronized 方法，保证可见性
 *       （这两个值会随生命周期方法改变）</li>
 *   <li>{@link #getId()} / {@link #getName()} 不覆盖：id 与 name 在所有 {@code withXxx} 中不变，
 *       即使读到旧 data 引用，返回值也始终正确</li>
 *   <li>{@code targetViewers} 使用 {@link ConcurrentHashMap#newKeySet()} 支持并发读写</li>
 * </ul>
 *
 * <h2>生命周期方法实现</h2>
 * <ul>
 *   <li>{@link #spawn()}：触发 {@link NpcSpawnEvent} →（未取消）遍历 targetViewers 调用 controller.spawn</li>
 *   <li>{@link #despawn()}：触发 {@link NpcDespawnEvent} →（未取消）调用 controller.despawnAll</li>
 *   <li>{@link #remove()}：委托 {@link NpcManagerImpl#remove} 完成 despawn + 事件 + 注销</li>
 *   <li>{@link #update()}：按 {@link NpcData#dirtyFields()} 增量同步，完成后 data = data.cleanCopy()</li>
 *   <li>{@link #moveTo(Location)}：data = data.withLocation(loc) + controller.moveTo</li>
 *   <li>{@link #lookAt(Location)}：计算 yaw/pitch + controller.updateHeadRotation</li>
 *   <li>{@link #interact(Player, ClickType)}：留 TODO（Task 8/12 实现 Action 执行）</li>
 * </ul>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcImpl extends Npc {

    /** 所属 Manager，用于 remove 时注销 */
    private final NpcManagerImpl manager;

    /**
     * 目标可见玩家集合（应看到此 NPC 的玩家 UUID）。
     *
     * <p>由 Task 7 的 {@code VisibilityTracker} 维护：玩家进入可见范围 → {@link #addViewer}；
     * 玩家离开 → {@link #removeViewer}。{@link #spawn()} 遍历此集合发送 spawn 包。</p>
     *
     * <p>Task 5 阶段此集合为空，spawn 为空操作。与 {@link NpcController#getVisiblePlayers()}
     * 的区别：后者记录"已收到 spawn 包"的玩家（协议层状态），本字段记录"应收到 spawn 包"
     * 的玩家（业务层决策）。</p>
     */
    private final Set<UUID> targetViewers = ConcurrentHashMap.newKeySet();

    /**
     * 构造 NpcImpl。
     *
     * <p>构造时创建 {@link NpcController}（内部分配 EntityId + displayEntityId + NmsAdapter），
     * GameProfile username 由 {@link #generateUsername(NpcData)} 基于 UUID 生成。</p>
     *
     * @param data    初始数据快照
     * @param manager 所属 NpcManagerImpl，用于 remove 时注销
     */
    public NpcImpl(NpcData data, NpcManagerImpl manager) {
        super(data, new NpcController(data.id(), generateUsername(data)));
        this.manager = manager;
    }

    /**
     * 基于 NPC 的 UUID 生成 GameProfile 占位玩家名。
     *
     * <p>Minecraft GameProfile username 上限 16 字符。格式：{@code wn_} + UUID 去连字符前 12 位 = 15 字符。
     * username 仅用于 PlayerInfo 与默认皮肤查找（离线模式），不影响 tab 显示（由 showInTab 控制）。</p>
     *
     * @param data NPC 数据
     * @return 15 字符占位名
     */
    private static String generateUsername(NpcData data) {
        String hex = data.id().toString().replace("-", "");
        return "wn_" + hex.substring(0, 12);
    }

    // ==================== 生命周期方法 ====================

    @Override
    public void spawn() {
        synchronized (this) {
            NpcSpawnEvent event = new NpcSpawnEvent(this);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return;
            }
            NpcData snapshot = data;
            for (UUID playerId : targetViewers) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    controller.spawn(player, snapshot);
                }
            }
        }
    }

    @Override
    public void despawn() {
        synchronized (this) {
            NpcDespawnEvent event = new NpcDespawnEvent(this);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return;
            }
            controller.despawnAll();
        }
    }

    /**
     * 静默销毁客户端实体（不触发 NpcDespawnEvent）。
     *
     * <p>供 {@link NpcManagerImpl#remove} 使用：remove 是不可取消的强制操作，
     * 不应触发可取消的 NpcDespawnEvent。直接调用 controller.despawnAll() 销毁所有客户端实体。</p>
     */
    @ApiStatus.Internal
    public void despawnSilent() {
        synchronized (this) {
            controller.despawnAll();
        }
    }

    @Override
    public void remove() {
        manager.remove(getId());
    }

    @Override
    public void update() {
        synchronized (this) {
            NpcData snapshot = data;
            Set<NpcField> dirty = snapshot.dirtyFields();
            if (dirty.isEmpty()) {
                return;
            }

            boolean skinDirty = dirty.contains(NpcField.SKIN);

            // LOCATION：瞬移包（SKIN dirty 时跳过，spawn 会重发）
            if (!skinDirty && dirty.contains(NpcField.LOCATION)) {
                controller.updateLocation(snapshot.location());
            }
            // DISPLAY_NAME：更新 TextDisplay 文本（SKIN dirty 时跳过，spawn 会重发）
            if (!skinDirty && dirty.contains(NpcField.DISPLAY_NAME)) {
                controller.updateDisplayName(snapshot);
            }
            // SKIN：皮肤纹理变更，需重新 spawn（despawnAll + spawn）
            if (skinDirty) {
                controller.despawnAll();
                for (UUID playerId : targetViewers) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        controller.spawn(player, snapshot);
                    }
                }
            }
            // EQUIPMENT：装备包
            if (dirty.contains(NpcField.EQUIPMENT)) {
                controller.updateEquipment(snapshot.equipment());
            }
            // GLOW_COLOR / POSE / SCALE / EFFECTS / COLLIDABLE：合并为元数据包
            // 注意：SHOW_IN_TAB 是 PlayerInfo 层属性（UPDATE_LISTED），非 entity metadata，
            // 运行时切换需单独发包，暂未实现（TODO Task 7+）
            if (dirty.contains(NpcField.GLOW_COLOR) || dirty.contains(NpcField.POSE)
                    || dirty.contains(NpcField.SCALE) || dirty.contains(NpcField.EFFECTS)
                    || dirty.contains(NpcField.COLLIDABLE)) {
                controller.updateMetadata(snapshot);
            }
            // TURN_TO_PLAYER / TURN_TO_PLAYER_DISTANCE / VISIBILITY_DISTANCE / VISIBILITY_PERMISSIONS / INTERACTION_COOLDOWN：
            // 纯业务层字段，不发包（由 Tracker / ActionManager 读取 data 决策）

            // 同步完成，清零 dirty
            data = snapshot.cleanCopy();
        }
    }

    @Override
    public void moveTo(Location location) {
        synchronized (this) {
            data = data.withLocation(location);
            controller.moveTo(location);
        }
    }

    @Override
    public void lookAt(Location target) {
        synchronized (this) {
            Location current = data.location();
            double dx = target.getX() - current.getX();
            double dy = target.getY() - current.getY();
            double dz = target.getZ() - current.getZ();
            double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // Minecraft yaw：0=朝+z，90=朝-x，180=朝-z，270=朝+x
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            // pitch：0=水平，正=朝下，负=朝上
            float pitch = r == 0.0 ? 0.0f : (float) Math.toDegrees(Math.asin(-dy / r));
            controller.updateHeadRotation(yaw, pitch);
        }
    }

    @Override
    public void interact(Player player, ClickType clickType) {
        // TODO Task 8/12：触发 NpcInteractEvent + 执行 ActionManager 动作集合
        // 流程：检查 interactionCooldown → 触发 NpcInteractEvent →（未取消）执行 ClickType 对应动作
    }

    // ==================== 数据访问覆盖（保证可见性） ====================

    /**
     * 覆盖父类方法，加 synchronized 保证 {@link #data} 引用的可见性。
     *
     * <p>父类的 {@code getData()} 直接读取 {@code data} 字段，无同步保护。
     * 由于 {@link #update()} / {@link #moveTo(Location)} 等写操作在 synchronized 块内替换 {@code data}，
     * 此处必须同步读取才能获得 happens-before 保证。</p>
     *
     * @return 当前数据快照
     */
    @Override
    public synchronized NpcData getData() {
        return data;
    }

    /**
     * 覆盖父类方法，加 synchronized 保证 location 读取的可见性。
     *
     * @return 位置副本
     */
    @Override
    public synchronized Location getLocation() {
        return data.location();
    }

    // ==================== Tracker 接入点（Task 7 使用） ====================

    /**
     * 添加目标可见玩家（由 {@code VisibilityTracker} 调用）。
     *
     * <p>玩家进入可见范围时调用。后续 {@link #spawn()} 会遍历此集合发送 spawn 包。</p>
     *
     * @param playerId 玩家 UUID
     * @return 玩家先前不在集合中返回 true
     */
    @ApiStatus.Internal
    public boolean addViewer(UUID playerId) {
        return targetViewers.add(playerId);
    }

    /**
     * 移除目标可见玩家（由 {@code VisibilityTracker} 调用）。
     *
     * <p>玩家离开可见范围时调用。Tracker 应同时调用 {@link NpcController#despawn(Player)}
     * 发送 despawn 包（从已可见集合移除）。</p>
     *
     * @param playerId 玩家 UUID
     * @return 玩家先前在集合中返回 true
     */
    @ApiStatus.Internal
    public boolean removeViewer(UUID playerId) {
        return targetViewers.remove(playerId);
    }
}
