package com.oolongho.woonpc.api;

import com.oolongho.woonpc.event.NpcModifyEvent;
import com.oolongho.woonpc.npc.ClickType;
import com.oolongho.woonpc.npc.GlowingColor;
import com.oolongho.woonpc.npc.NpcController;
import com.oolongho.woonpc.npc.NpcEffect;
import com.oolongho.woonpc.npc.NpcEquipmentSlot;
import com.oolongho.woonpc.npc.NpcPose;
import com.oolongho.woonpc.skin.SkinData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

// 注：org.jetbrains.annotations.ApiStatus 无 Public 子注解，
// 公共 API 默认不加 @ApiStatus 注解（仅 Internal/Experimental 等才标记）。

/**
 * NPC 抽象基类（公共 API）。
 *
 * <p>定义一个数据包 NPC 的生命周期契约与状态访问接口。持有 {@link NpcData}（数据快照）
 * 与 {@link NpcController}（包发送委托），具体生命周期实现由子类 {@code NpcImpl}
 * 结合 {@code Tracker}、{@code ActionManager} 等组件完成。</p>
 *
 * <h2>架构关系</h2>
 * <pre>
 *   Npc (abstract, public API)
 *     ├─ NpcData          (immutable snapshot + dirty fields)
 *     └─ NpcController    (internal, delegates to NmsAdapter)
 *           └─ NmsAdapter (version-specific packet sending)
 * </pre>
 *
 * <h2>生命周期方法</h2>
 * <ul>
 *   <li>{@link #spawn()}：对当前可见玩家发送 spawn 包（首次生成或重新显示）</li>
 *   <li>{@link #despawn()}：对所有可见玩家发送 despawn 包（不移除 NPC，可再次 spawn）</li>
 *   <li>{@link #remove()}：despawn + 从 NpcManager 注销</li>
 *   <li>{@link #update()}：增量同步 dirty 字段到可见玩家（NpcController 按 dirty 决定发包）</li>
 *   <li>{@link #moveTo(Location)}：移动到新位置（瞬移）</li>
 *   <li>{@link #lookAt(Location)}：头部朝向目标位置</li>
 *   <li>{@link #interact(Player, ClickType)}：触发交互事件 + 执行动作集合</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>所有生命周期方法必须在主线程调用（数据包发送非线程安全）。
 * {@link #data} 字段由子类通过 {@code synchronized} 或 {@code volatile} 保护。</p>
 *
 * @author oolongho
 */
public abstract class Npc {

    /** NPC 数据快照（可变，子类通过 {@code data = data.withXxx(...)} 更新） */
    protected NpcData data;

    /** 包发送委托控制器（不可变，构造时确定） */
    protected final NpcController controller;

    /**
     * 构造 NPC。
     *
     * @param data       初始数据快照，不可为 null
     * @param controller 包发送控制器，不可为 null
     * @throws NullPointerException 当 data 或 controller 为 null
     */
    protected Npc(NpcData data, NpcController controller) {
        this.data = Objects.requireNonNull(data, "data cannot be null");
        this.controller = Objects.requireNonNull(controller, "controller cannot be null");
        if (!data.id().equals(controller.getUuid())) {
            throw new IllegalArgumentException("data.id (" + data.id()
                    + ") must match controller.uuid (" + controller.getUuid() + ")");
        }
    }

    // ==================== 数据访问（委托给 NpcData） ====================

    /**
     * 获取 NPC 数据快照。
     *
     * <p>返回当前 {@link #data} 引用，调用方不应修改。如需安全副本，
     * 可调用 {@link NpcData#cleanCopy()} 或对应 {@code withXxx} 方法。</p>
     *
     * @return 当前数据快照
     */
    public NpcData getData() {
        return data;
    }

    /**
     * 获取 NPC 唯一标识。
     *
     * @return UUID
     */
    public UUID getId() {
        return data.id();
    }

    /**
     * 获取 NPC 名称。
     *
     * @return 名称
     */
    public String getName() {
        return data.name();
    }

    /**
     * 获取 NPC 当前位置（返回 clone，修改不影响内部状态）。
     *
     * @return 位置副本
     */
    public Location getLocation() {
        return data.location();
    }

    /**
     * 获取 NPC 的客户端实体 ID（用于数据包交互匹配）。
     *
     * <p>由 {@code NpcController} 在构造时分配的虚拟实体 ID，
     * {@code NpcInteractListener} 通过此 ID 将客户端 {@code ServerboundInteractPacket}
     * 中的 {@code entityId} 与对应 NPC 关联。</p>
     *
     * @return 客户端实体 ID
     */
    public abstract int getEntityId();

    /**
     * 获取当前对该 NPC 可见的玩家数量。
     *
     * <p>由 {@code NpcController} 维护的可见集合大小，反映已发送 spawn 包且
     * 未发送 despawn 包的玩家数。供 GUI / 调试 / 统计场景使用。</p>
     *
     * @return 可见玩家数量
     */
    public int getVisiblePlayerCount() {
        return controller.getVisiblePlayers().size();
    }

    /**
     * 获取包发送控制器（内部使用）。
     *
     * <p>子类通过此方法访问 {@link NpcController} 发送数据包。
     * 外部不应直接调用此方法。</p>
     *
     * @return 控制器实例
     */
    @ApiStatus.Internal
    protected final NpcController getController() {
        return controller;
    }

    // ==================== 生命周期方法（抽象，由子类实现） ====================

    /**
     * 生成 NPC：对当前可见玩家发送 spawn 包。
     *
     * <p>首次生成或 {@link #despawn()} 后重新显示时调用。
     * 实现通过 {@code Tracker} 确定可见玩家集合，委托 {@link NpcController#spawn} 发包。</p>
     *
     * @throws com.oolongho.woonpc.nms.util.WooNPCsException 当 NmsAdapter 未就绪
     */
    public abstract void spawn();

    /**
     * 销毁 NPC 显示：对所有可见玩家发送 despawn 包。
     *
     * <p>仅移除客户端实体，不从 {@code NpcManager} 注销。
     * 可通过 {@link #spawn()} 重新显示。</p>
     */
    public abstract void despawn();

    /**
     * 移除 NPC：despawn + 从 NpcManager 注销。
     *
     * <p>调用后此 NPC 实例不再可用，不应再次调用生命周期方法。
     * 具体注销逻辑由 {@code NpcManager.remove} 实现。</p>
     */
    public abstract void remove();

    /**
     * 增量同步 dirty 字段到可见玩家。
     *
     * <p>依据 {@link NpcData#dirtyFields()} 决定发送哪些更新包：
     * 如 DISPLAY_NAME dirty 则更新显示名，EQUIPMENT dirty 则更新装备等。
     * 同步完成后应将 {@link #data} 替换为 {@link NpcData#cleanCopy()}。</p>
     */
    public abstract void update();

    /**
     * 移动 NPC 到新位置。
     *
     * <p>当前实现为瞬移（直接发送 {@code TeleportEntityPacket}）。</p>
     *
     * <p>实现应同时更新 {@link #data} 的 location 字段（通过 {@link NpcData#withLocation}），
     * 并触发 {@link NpcController#moveTo} 发包。</p>
     *
     * @param location 新位置，不可为 null
     */
    public abstract void moveTo(Location location);

    /**
     * 头部朝向目标位置。
     *
     * <p>计算当前位置到目标的方向向量，转换为 yaw/pitch，
     * 委托 {@link NpcController#updateHeadRotation} 发包。
     * 不改变 {@link #data} 的 location 字段（仅头部朝向）。</p>
     *
     * @param target 目标位置，不可为 null
     */
    public abstract void lookAt(Location target);

    /**
     * 处理玩家交互。
     *
     * <p>触发 {@code NpcInteractEvent}，未取消时执行该 ClickType 对应的
     * 动作集合（{@code ActionManager}）。同时检查 {@link NpcData#interactionCooldown}
     * 防止短时间重复触发。</p>
     *
     * @param player    交互的玩家
     * @param clickType 点击类型
     */
    public abstract void interact(Player player, ClickType clickType);

    // ==================== 字段修改 API（触发 NpcModifyEvent） ====================

    /**
     * 通用字段修改模板：触发 {@link NpcModifyEvent}（可取消），未取消时应用变更并调用 {@link #update()}。
     *
     * <p>所有 setter 委托本方法。事件触发在 synchronized 块外（避免持锁调用监听器导致死锁），
     * data 替换在 synchronized 块内（与 {@link #update()} 共享 this 锁）。</p>
     *
     * @param field    被修改的字段
     * @param newValue 新值
     * @param getter   从 NpcData 读取旧值的函数
     * @param updater  生成新 NpcData 的函数（调用 withXxx）
     * @param <T>      值类型
     */
    protected abstract <T> void modify(NpcField field, T newValue,
                                       Function<NpcData, T> getter,
                                       Function<NpcData, NpcData> updater);

    /** 设置位置（等价于 moveTo，但触发 NpcModifyEvent，可被取消）。 */
    public final void setLocation(Location location) {
        Objects.requireNonNull(location, "location cannot be null");
        modify(NpcField.LOCATION, location, NpcData::location, d -> d.withLocation(location));
    }

    /** 设置头顶显示名，null 清除。 */
    public final void setDisplayName(@Nullable String displayName) {
        modify(NpcField.DISPLAY_NAME, displayName, NpcData::displayName, d -> d.withDisplayName(displayName));
    }

    /** 设置皮肤。 */
    public final void setSkin(SkinData skin) {
        Objects.requireNonNull(skin, "skin cannot be null");
        modify(NpcField.SKIN, skin, NpcData::skin, d -> d.withSkin(skin));
    }

    /** 设置装备映射（六槽位），null 清空。 */
    public final void setEquipment(@Nullable Map<NpcEquipmentSlot, ItemStack> equipment) {
        modify(NpcField.EQUIPMENT, equipment, NpcData::equipment, d -> d.withEquipment(equipment));
    }

    /** 设置发光颜色。 */
    public final void setGlowColor(GlowingColor glowColor) {
        Objects.requireNonNull(glowColor, "glowColor cannot be null");
        modify(NpcField.GLOW_COLOR, glowColor, NpcData::glowColor, d -> d.withGlowColor(glowColor));
    }

    /** 设置姿势。 */
    public final void setPose(NpcPose pose) {
        Objects.requireNonNull(pose, "pose cannot be null");
        modify(NpcField.POSE, pose, NpcData::pose, d -> d.withPose(pose));
    }

    /** 设置缩放。 */
    public final void setScale(float scale) {
        modify(NpcField.SCALE, scale, NpcData::scale, d -> d.withScale(scale));
    }

    /** 设置效果集合，null 清空。 */
    public final void setEffects(@Nullable Set<NpcEffect> effects) {
        modify(NpcField.EFFECTS, effects, NpcData::effects, d -> d.withEffects(effects));
    }

    /** 设置是否在 tab 列表显示。 */
    public final void setShowInTab(boolean showInTab) {
        modify(NpcField.SHOW_IN_TAB, showInTab, NpcData::showInTab, d -> d.withShowInTab(showInTab));
    }

    /** 设置是否可碰撞。 */
    public final void setCollidable(boolean collidable) {
        modify(NpcField.COLLIDABLE, collidable, NpcData::collidable, d -> d.withCollidable(collidable));
    }

    /** 设置是否转头跟随玩家。 */
    public final void setTurnToPlayer(boolean turnToPlayer) {
        modify(NpcField.TURN_TO_PLAYER, turnToPlayer, NpcData::turnToPlayer, d -> d.withTurnToPlayer(turnToPlayer));
    }

    /** 设置转头跟随触发距离。 */
    public final void setTurnToPlayerDistance(double distance) {
        modify(NpcField.TURN_TO_PLAYER_DISTANCE, distance, NpcData::turnToPlayerDistance, d -> d.withTurnToPlayerDistance(distance));
    }

    /** 设置可见距离。 */
    public final void setVisibilityDistance(double distance) {
        modify(NpcField.VISIBILITY_DISTANCE, distance, NpcData::visibilityDistance, d -> d.withVisibilityDistance(distance));
    }

    /** 设置可见权限集合，null/empty 表示无限制。 */
    public final void setVisibilityPermissions(@Nullable Set<String> permissions) {
        modify(NpcField.VISIBILITY_PERMISSIONS, permissions, NpcData::visibilityPermissions, d -> d.withVisibilityPermissions(permissions));
    }

    /** 设置交互冷却（毫秒）。 */
    public final void setInteractionCooldown(long cooldown) {
        modify(NpcField.INTERACTION_COOLDOWN, cooldown, NpcData::interactionCooldown, d -> d.withInteractionCooldown(cooldown));
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Npc npc)) return false;
        return data.id().equals(npc.data.id());
    }

    @Override
    public int hashCode() {
        return data.id().hashCode();
    }

    @Override
    public String toString() {
        return "Npc{id=" + data.id() + ", name='" + data.name() + "', location=" + data.location() + "}";
    }
}
