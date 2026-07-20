package com.oolongho.woonpc.api;

import com.oolongho.woonpc.npc.GlowingColor;
import com.oolongho.woonpc.npc.NpcEffect;
import com.oolongho.woonpc.npc.NpcEquipmentSlot;
import com.oolongho.woonpc.npc.NpcPose;
import com.oolongho.woonpc.skin.SkinData;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * NPC 数据快照（不可变值对象）。
 *
 * <p>承载一个 NPC 在某一时刻的全部可配置状态，是 {@link Npc} 的内部数据载体。
 * 所有字段为 final，修改通过 {@code withXxx} 方法返回<b>新实例</b>（with 风格），
 * 并自动将对应字段加入 {@link #dirtyFields()} 集合，供 {@code NpcController}
 * 决定增量同步哪些更新包。</p>
 *
 * <h2>不可变性约定</h2>
 * <ul>
 *   <li>所有集合字段在 compact constructor 中防御性复制为不可修改视图</li>
 *   <li>{@link #location()} accessor 返回 clone，外部修改不影响内部状态</li>
 *   <li>{@link ItemStack} 本身可变，调用方应自行 clone 后传入或接收</li>
 * </ul>
 *
 * <h2>Dirty 标记机制</h2>
 * <ul>
 *   <li>{@link Builder#build()} 返回的初始快照 dirty = {@code EnumSet.allOf(NpcField.class)}
 *       （全新 NPC 首次 spawn 需全量同步）</li>
 *   <li>每次 {@code withXxx} 返回的新快照 dirty = 旧 dirty ∪ {修改字段}</li>
 *   <li>{@link #cleanCopy()} 返回 dirty 为空的新快照（同步完成后由 NpcController 调用）</li>
 * </ul>
 *
 * <p>本类为内部 API，对外公共 API 是 {@link Npc}。用户不应直接构造 NpcData，
 * 而应通过 {@code NpcManager.create(...)} 间接创建。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public record NpcData(
        UUID id,
        String name,
        Location location,
        @Nullable String displayName,
        SkinData skin,
        Map<NpcEquipmentSlot, ItemStack> equipment,
        GlowingColor glowColor,
        NpcPose pose,
        float scale,
        Set<NpcEffect> effects,
        boolean showInTab,
        boolean collidable,
        boolean turnToPlayer,
        double turnToPlayerDistance,
        double visibilityDistance,
        Set<String> visibilityPermissions,
        long interactionCooldown,
        Set<NpcField> dirtyFields
) {

    /** 默认缩放比例 */
    public static final float DEFAULT_SCALE = 1.0f;

    /** 默认转头跟随触发距离（方块） */
    public static final double DEFAULT_TURN_TO_PLAYER_DISTANCE = 8.0;

    /** 默认可见距离（方块），{@code <=0} 表示使用服务端视图距离 */
    public static final double DEFAULT_VISIBILITY_DISTANCE = 32.0;

    /** 默认交互冷却（毫秒） */
    public static final long DEFAULT_INTERACTION_COOLDOWN = 0L;

    /**
     * Compact constructor：防御性复制 + 非空校验。
     *
     * @throws NullPointerException 当 id / name / location / skin / glowColor / pose / dirtyFields 为 null
     */
    public NpcData {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(location, "location cannot be null");
        Objects.requireNonNull(skin, "skin cannot be null (use SkinData.defaultSkin())");
        Objects.requireNonNull(glowColor, "glowColor cannot be null (use GlowingColor.NONE)");
        Objects.requireNonNull(pose, "pose cannot be null (use NpcPose.STANDING)");
        Objects.requireNonNull(dirtyFields, "dirtyFields cannot be null");

        // 防御性复制：Location 克隆，集合转为不可修改副本
        location = location.clone();
        equipment = equipment == null ? Map.of() : Map.copyOf(equipment);
        effects = effects == null || effects.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(effects));
        visibilityPermissions = visibilityPermissions == null || visibilityPermissions.isEmpty()
                ? Set.of()
                : Set.copyOf(visibilityPermissions);
        dirtyFields = dirtyFields.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(NpcField.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(dirtyFields));
    }

    /**
     * 覆盖默认 accessor，返回 Location 副本，防止外部修改内部状态。
     *
     * @return Location 的克隆
     */
    @Override
    public Location location() {
        return location.clone();
    }

    // ==================== Dirty 查询 ====================

    /**
     * 判断指定字段是否处于 dirty 状态。
     *
     * @param field 字段
     * @return 该字段需要增量同步时返回 true
     */
    public boolean isDirty(NpcField field) {
        return dirtyFields.contains(field);
    }

    /**
     * 判断是否存在任何 dirty 字段。
     *
     * @return 存在 dirty 字段时返回 true
     */
    public boolean hasDirty() {
        return !dirtyFields.isEmpty();
    }

    /**
     * 返回 dirty 字段集合的不可修改视图。
     *
     * @return dirty 字段集合
     */
    @Override
    public Set<NpcField> dirtyFields() {
        return dirtyFields;
    }

    /**
     * 返回 dirty 集合为空的新快照（同步完成后由 NpcController 调用）。
     *
     * @return dirty 清零的新 NpcData 实例
     */
    public NpcData cleanCopy() {
        return new NpcData(
                id, name, location, displayName, skin, equipment, glowColor, pose,
                scale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                EnumSet.noneOf(NpcField.class)
        );
    }

    // ==================== With 方法（返回新实例，自动累积 dirty） ====================

    /**
     * 修改名称，返回新快照（dirty += NAME）。
     *
     * @param newName 新名称，不可为 null
     * @return 新 NpcData 实例
     */
    public NpcData withName(String newName) {
        Objects.requireNonNull(newName, "name cannot be null");
        return new NpcData(
                id, newName, location, displayName, skin, equipment, glowColor, pose,
                scale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.NAME)
        );
    }

    /**
     * 修改位置，返回新快照（dirty += LOCATION）。
     *
     * @param newLocation 新位置，不可为 null
     * @return 新 NpcData 实例
     */
    public NpcData withLocation(Location newLocation) {
        Objects.requireNonNull(newLocation, "location cannot be null");
        return new NpcData(
                id, name, newLocation, displayName, skin, equipment, glowColor, pose,
                scale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.LOCATION)
        );
    }

    /**
     * 修改显示名，返回新快照（dirty += DISPLAY_NAME）。
     *
     * @param newDisplayName 新显示名，null 表示不显示
     * @return 新 NpcData 实例
     */
    public NpcData withDisplayName(@Nullable String newDisplayName) {
        return new NpcData(
                id, name, location, newDisplayName, skin, equipment, glowColor, pose,
                scale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.DISPLAY_NAME)
        );
    }

    /**
     * 修改皮肤，返回新快照（dirty += SKIN）。
     *
     * @param newSkin 新皮肤，不可为 null
     * @return 新 NpcData 实例
     */
    public NpcData withSkin(SkinData newSkin) {
        Objects.requireNonNull(newSkin, "skin cannot be null");
        return new NpcData(
                id, name, location, displayName, newSkin, equipment, glowColor, pose,
                scale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.SKIN)
        );
    }

    /**
     * 修改装备映射，返回新快照（dirty += EQUIPMENT）。
     *
     * @param newEquipment 新装备映射，null 视为空映射
     * @return 新 NpcData 实例
     */
    public NpcData withEquipment(@Nullable Map<NpcEquipmentSlot, ItemStack> newEquipment) {
        return new NpcData(
                id, name, location, displayName, skin, newEquipment, glowColor, pose,
                scale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.EQUIPMENT)
        );
    }

    /**
     * 修改发光颜色，返回新快照（dirty += GLOW_COLOR）。
     *
     * @param newGlowColor 新发光颜色，不可为 null
     * @return 新 NpcData 实例
     */
    public NpcData withGlowColor(GlowingColor newGlowColor) {
        Objects.requireNonNull(newGlowColor, "glowColor cannot be null");
        return new NpcData(
                id, name, location, displayName, skin, equipment, newGlowColor, pose,
                scale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.GLOW_COLOR)
        );
    }

    /**
     * 修改姿势，返回新快照（dirty += POSE）。
     *
     * @param newPose 新姿势，不可为 null
     * @return 新 NpcData 实例
     */
    public NpcData withPose(NpcPose newPose) {
        Objects.requireNonNull(newPose, "pose cannot be null");
        return new NpcData(
                id, name, location, displayName, skin, equipment, glowColor, newPose,
                scale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.POSE)
        );
    }

    /**
     * 修改缩放比例，返回新快照（dirty += SCALE）。
     *
     * @param newScale 新缩放比例（1.0 = 原始大小）
     * @return 新 NpcData 实例
     */
    public NpcData withScale(float newScale) {
        return new NpcData(
                id, name, location, displayName, skin, equipment, glowColor, pose,
                newScale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.SCALE)
        );
    }

    /**
     * 修改实体效果集合，返回新快照（dirty += EFFECTS）。
     *
     * @param newEffects 新效果集合，null 视为空集
     * @return 新 NpcData 实例
     */
    public NpcData withEffects(@Nullable Set<NpcEffect> newEffects) {
        return new NpcData(
                id, name, location, displayName, skin, equipment, glowColor, pose,
                scale, newEffects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.EFFECTS)
        );
    }

    /**
     * 修改 tab 列表显示，返回新快照（dirty += SHOW_IN_TAB）。
     *
     * @param newShowInTab 是否在 tab 列表显示
     * @return 新 NpcData 实例
     */
    public NpcData withShowInTab(boolean newShowInTab) {
        return new NpcData(
                id, name, location, displayName, skin, equipment, glowColor, pose,
                scale, effects, newShowInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.SHOW_IN_TAB)
        );
    }

    /**
     * 修改可碰撞性，返回新快照（dirty += COLLIDABLE）。
     *
     * @param newCollidable 是否可碰撞
     * @return 新 NpcData 实例
     */
    public NpcData withCollidable(boolean newCollidable) {
        return new NpcData(
                id, name, location, displayName, skin, equipment, glowColor, pose,
                scale, effects, showInTab, newCollidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.COLLIDABLE)
        );
    }

    /**
     * 修改转头跟随，返回新快照（dirty += TURN_TO_PLAYER）。
     *
     * @param newTurnToPlayer 是否转头跟随玩家
     * @return 新 NpcData 实例
     */
    public NpcData withTurnToPlayer(boolean newTurnToPlayer) {
        return new NpcData(
                id, name, location, displayName, skin, equipment, glowColor, pose,
                scale, effects, showInTab, collidable, newTurnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.TURN_TO_PLAYER)
        );
    }

    /**
     * 修改转头跟随触发距离，返回新快照（dirty += TURN_TO_PLAYER_DISTANCE）。
     *
     * @param newDistance 新触发距离（方块）
     * @return 新 NpcData 实例
     */
    public NpcData withTurnToPlayerDistance(double newDistance) {
        return new NpcData(
                id, name, location, displayName, skin, equipment, glowColor, pose,
                scale, effects, showInTab, collidable, turnToPlayer, newDistance,
                visibilityDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.TURN_TO_PLAYER_DISTANCE)
        );
    }

    /**
     * 修改可见距离，返回新快照（dirty += VISIBILITY_DISTANCE）。
     *
     * @param newDistance 新可见距离（方块），{@code <=0} 表示使用服务端视图距离
     * @return 新 NpcData 实例
     */
    public NpcData withVisibilityDistance(double newDistance) {
        return new NpcData(
                id, name, location, displayName, skin, equipment, glowColor, pose,
                scale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                newDistance, visibilityPermissions, interactionCooldown,
                withDirty(NpcField.VISIBILITY_DISTANCE)
        );
    }

    /**
     * 修改可见权限集合，返回新快照（dirty += VISIBILITY_PERMISSIONS）。
     *
     * @param newPermissions 新权限集合，null 视为空集（无限制）
     * @return 新 NpcData 实例
     */
    public NpcData withVisibilityPermissions(@Nullable Set<String> newPermissions) {
        return new NpcData(
                id, name, location, displayName, skin, equipment, glowColor, pose,
                scale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, newPermissions, interactionCooldown,
                withDirty(NpcField.VISIBILITY_PERMISSIONS)
        );
    }

    /**
     * 修改交互冷却，返回新快照（dirty += INTERACTION_COOLDOWN）。
     *
     * @param newCooldown 新冷却时长（毫秒），0 表示无冷却
     * @return 新 NpcData 实例
     */
    public NpcData withInteractionCooldown(long newCooldown) {
        return new NpcData(
                id, name, location, displayName, skin, equipment, glowColor, pose,
                scale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                visibilityDistance, visibilityPermissions, newCooldown,
                withDirty(NpcField.INTERACTION_COOLDOWN)
        );
    }

    /**
     * 内部辅助：构造 dirty 集合 = 当前 dirty ∪ {field}。
     *
     * @param field 新增的 dirty 字段
     * @return 新的 dirty 集合（可变 EnumSet，由 compact constructor 再包装为不可修改）
     */
    private EnumSet<NpcField> withDirty(NpcField field) {
        EnumSet<NpcField> next = dirtyFields.isEmpty()
                ? EnumSet.noneOf(NpcField.class)
                : EnumSet.copyOf(dirtyFields);
        next.add(field);
        return next;
    }

    // ==================== Builder ====================

    /**
     * 创建 Builder，用于构造初始 NpcData 快照。
     *
     * <p>必填字段：id、name、location。其余字段提供默认值。
     * {@link Builder#build()} 返回的快照 dirty = 全部字段（首次 spawn 需全量同步）。</p>
     *
     * @param id       NPC 唯一标识
     * @param name     NPC 名称
     * @param location 初始位置
     * @return Builder 实例
     */
    public static Builder builder(UUID id, String name, Location location) {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(location, "location cannot be null");
        return new Builder(id, name, location);
    }

    /**
     * NpcData 构造器（可变，链式 setter 返回 this）。
     *
     * <p>{@link #build()} 返回的快照 dirty = {@code EnumSet.allOf(NpcField.class)}，
     * 因为全新 NPC 首次 spawn 需要全量同步所有字段到客户端。</p>
     */
    public static final class Builder {

        private final UUID id;
        private final String name;
        private final Location location;
        private @Nullable String displayName;
        private SkinData skin = SkinData.defaultSkin();
        private @Nullable Map<NpcEquipmentSlot, ItemStack> equipment;
        private GlowingColor glowColor = GlowingColor.NONE;
        private NpcPose pose = NpcPose.STANDING;
        private float scale = DEFAULT_SCALE;
        private @Nullable Set<NpcEffect> effects;
        private boolean showInTab = false;
        private boolean collidable = false;
        private boolean turnToPlayer = false;
        private double turnToPlayerDistance = DEFAULT_TURN_TO_PLAYER_DISTANCE;
        private double visibilityDistance = DEFAULT_VISIBILITY_DISTANCE;
        private @Nullable Set<String> visibilityPermissions;
        private long interactionCooldown = DEFAULT_INTERACTION_COOLDOWN;

        private Builder(UUID id, String name, Location location) {
            this.id = id;
            this.name = name;
            this.location = location;
        }

        public Builder displayName(@Nullable String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder skin(SkinData skin) {
            this.skin = Objects.requireNonNull(skin, "skin cannot be null");
            return this;
        }

        public Builder equipment(@Nullable Map<NpcEquipmentSlot, ItemStack> equipment) {
            this.equipment = equipment;
            return this;
        }

        public Builder glowColor(GlowingColor glowColor) {
            this.glowColor = Objects.requireNonNull(glowColor, "glowColor cannot be null");
            return this;
        }

        public Builder pose(NpcPose pose) {
            this.pose = Objects.requireNonNull(pose, "pose cannot be null");
            return this;
        }

        public Builder scale(float scale) {
            this.scale = scale;
            return this;
        }

        public Builder effects(@Nullable Set<NpcEffect> effects) {
            this.effects = effects;
            return this;
        }

        public Builder showInTab(boolean showInTab) {
            this.showInTab = showInTab;
            return this;
        }

        public Builder collidable(boolean collidable) {
            this.collidable = collidable;
            return this;
        }

        public Builder turnToPlayer(boolean turnToPlayer) {
            this.turnToPlayer = turnToPlayer;
            return this;
        }

        public Builder turnToPlayerDistance(double distance) {
            this.turnToPlayerDistance = distance;
            return this;
        }

        public Builder visibilityDistance(double distance) {
            this.visibilityDistance = distance;
            return this;
        }

        public Builder visibilityPermissions(@Nullable Set<String> permissions) {
            this.visibilityPermissions = permissions;
            return this;
        }

        public Builder interactionCooldown(long cooldown) {
            this.interactionCooldown = cooldown;
            return this;
        }

        /**
         * 构建初始 NpcData 快照。
         *
         * <p>返回实例的 dirty 集合为 {@link NpcField} 全部值，
         * 表示这是全新快照，{@code NpcController} 首次 spawn 时应全量发送。</p>
         *
         * @return 不可变 NpcData 快照
         */
        public NpcData build() {
            Set<String> perms = visibilityPermissions == null
                    ? Set.of()
                    : new LinkedHashSet<>(visibilityPermissions);
            return new NpcData(
                    id, name, location, displayName, skin, equipment, glowColor, pose,
                    scale, effects, showInTab, collidable, turnToPlayer, turnToPlayerDistance,
                    visibilityDistance, perms, interactionCooldown,
                    EnumSet.allOf(NpcField.class)
            );
        }
    }
}
