package com.oolongho.woonpc.npc;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcField;
import com.oolongho.woonpc.event.NpcDespawnEvent;
import com.oolongho.woonpc.event.NpcInteractEvent;
import com.oolongho.woonpc.event.NpcModifyEvent;
import com.oolongho.woonpc.event.NpcSpawnEvent;
import com.oolongho.woonpc.hook.WooHologramsHook;
import com.oolongho.woonpc.manager.NpcManagerImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
 *   <li>{@link #interact(Player, ClickType)}：触发 {@link NpcInteractEvent}（ActionManager 由 Task 18 装配）</li>
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
     * <p>由 Task 7 的 {@code VisibilityTracker} 维护：玩家进入可见范围 → {@link #showTo}；
     * 玩家离开 → {@link #hideFrom}。{@link #spawn()} 遍历此集合发送 spawn 包。</p>
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
            // 同步全息到 NPC 位置（WooHolograms 内部管理 viewer，无需 per-viewer 调用）
            WooHologramsHook.getInstance().onNpcSpawn(getId(), snapshot.location());
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
            // 销毁全息实体（保留 lines 存储，spawn 时重建）
            WooHologramsHook.getInstance().onNpcDespawn(getId());
        }
    }

    /**
     * 静默销毁客户端实体（不触发 NpcDespawnEvent）。
     *
     * <p>供 {@link NpcManagerImpl#remove} 使用：remove 是不可取消的强制操作，
     * 不应触发可取消的 NpcDespawnEvent。直接调用 controller.despawnAll() 销毁所有客户端实体，
     * 同时通知 HologramHook 销毁全息（但保留 lines 存储以支持后续恢复）。</p>
     */
    @ApiStatus.Internal
    public void despawnSilent() {
        synchronized (this) {
            controller.despawnAll();
            WooHologramsHook.getInstance().onNpcDespawn(getId());
        }
    }

    @Override
    public void remove() {
        manager.remove(getId());
        // 释放 lines 存储（全息实体销毁已由 manager.remove 内部 despawnSilent → onNpcDespawn 完成）
        WooHologramsHook.getInstance().onNpcRemove(getId());
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
                // 同步全息位置（TextDisplay 由 controller.updateLocation 内部处理，此处仅 WooHolograms 全息）
                WooHologramsHook.getInstance().onNpcMove(getId(), snapshot.location());
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
            // 同步全息位置
            WooHologramsHook.getInstance().onNpcMove(getId(), location);
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
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(clickType, "clickType cannot be null");
        NpcInteractEvent event = new NpcInteractEvent(player, this, clickType);
        Bukkit.getPluginManager().callEvent(event);
        // ActionManager 监听 NpcInteractEvent 并执行 actions（Task 18 装配后生效）
    }

    // ==================== 字段修改（触发 NpcModifyEvent） ====================

    /**
     * {@inheritDoc}
     *
     * <p>实现流程：
     * <ol>
     *   <li>通过 {@link #getData()}（synchronized）读取当前快照，计算 oldValue</li>
     *   <li>在 synchronized 块<b>外</b>触发 {@link NpcModifyEvent}（避免持锁调用监听器导致死锁）</li>
     *   <li>未取消时，在 synchronized 块内替换 {@code data}（与 {@link #update()} 共享 this 锁）</li>
     *   <li>调用 {@link #update()}（synchronized）增量同步 dirty 字段到客户端</li>
     * </ol>
     *
     * <p>{@code updater} 内部调 {@code withXxx} 会自动标记 dirty，{@code update()} 据此发包
     * 并通过 {@code cleanCopy()} 清零 dirty。</p>
     */
    @Override
    protected <T> void modify(NpcField field, T newValue,
                              Function<NpcData, T> getter,
                              Function<NpcData, NpcData> updater) {
        NpcData snapshot = getData();  // synchronized read
        T oldValue = getter.apply(snapshot);
        NpcModifyEvent event = new NpcModifyEvent(this, field, oldValue, newValue);
        Bukkit.getPluginManager().callEvent(event);  // 主线程触发，不持锁
        if (event.isCancelled()) {
            return;
        }
        synchronized (this) {
            data = updater.apply(data);
        }
        update();  // synchronized，同步 dirty 字段到客户端
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

    /**
     * 获取 NPC 的客户端实体 ID，委托给 {@link NpcController#getEntityId()}。
     *
     * <p>entityId 在 NpcController 构造时一次性分配，不可变，无需同步。</p>
     *
     * @return 客户端实体 ID
     */
    @Override
    public int getEntityId() {
        return controller.getEntityId();
    }

    // ==================== Tracker 接入点（Task 7 使用） ====================

    /**
     * 让 NPC 对指定玩家可见（加入 targetViewers + 发送 spawn 包，不触发事件）。
     *
     * <p>由 {@code VisibilityTracker} 在玩家进入可见距离时调用。
     * 与 {@link #spawn()} 的区别：spawn 触发 NpcSpawnEvent 并遍历全部 targetViewers 发包；
     * 本方法仅对单个玩家发包，不触发事件（协议层操作）。</p>
     *
     * <p>幂等：若玩家已在 targetViewers 中，直接返回（{@link NpcController#spawn} 内部
     * 通过 visiblePlayers 检查避免重复发包）。</p>
     *
     * @param player 目标玩家
     */
    @ApiStatus.Internal
    public void showTo(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        if (!targetViewers.add(player.getUniqueId())) {
            return;
        }
        NpcData snapshot;
        synchronized (this) {
            snapshot = data;
        }
        controller.spawn(player, snapshot);
    }

    /**
     * 让 NPC 对指定玩家不可见（移出 targetViewers + 发送 despawn 包，不触发事件）。
     *
     * <p>由 {@code VisibilityTracker} 在玩家离开可见距离或下线时调用。
     * 幂等：若玩家不在 targetViewers 中，直接返回。</p>
     *
     * @param player 目标玩家
     */
    @ApiStatus.Internal
    public void hideFrom(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        if (!targetViewers.remove(player.getUniqueId())) {
            return;
        }
        controller.despawn(player);
    }

    /**
     * 直接设置头部朝向（不修改 NpcData.location）。
     *
     * <p>由 {@code LookTracker} 调用：插值计算后的 yaw/pitch 通过本方法发包。
     * 与 {@link #lookAt(Location)} 的区别：lookAt 接受目标位置并自行计算 yaw/pitch；
     * 本方法直接接收 yaw/pitch，适合 Tracker 内部插值后调用。</p>
     *
     * @param yaw   偏航角（度）
     * @param pitch 俯仰角（度）
     */
    @ApiStatus.Internal
    public void setHeadRotation(float yaw, float pitch) {
        synchronized (this) {
            controller.updateHeadRotation(yaw, pitch);
        }
    }
}
