package com.oolongho.woonpc.npc;

import org.bukkit.inventory.EquipmentSlot;

import java.util.Objects;

/**
 * NPC 装备槽位枚举。
 *
 * <p>对齐 Minecraft {@code EquipmentSlot} 的六类槽位，用于 {@code NpcData.equipment}
 * 映射与 {@code ClientboundSetEquipmentPacket} 构造。</p>
 *
 * <p>与 Bukkit {@link EquipmentSlot} 的命名差异：</p>
 * <ul>
 *   <li>本枚举 {@link #MAINHAND} ↔ Bukkit {@code HAND}</li>
 *   <li>本枚举 {@link #OFFHAND} ↔ Bukkit {@code OFF_HAND}</li>
 *   <li>本枚举 {@link #BOOTS}   ↔ Bukkit {@code FEET}</li>
 * </ul>
 *
 * <p>本枚举对外为公共 API 的一部分（用户配置装备时引用），故不加 {@code @ApiStatus.Internal}。</p>
 *
 * @author oolongho
 */
public enum NpcEquipmentSlot {

    /** 主手 */
    MAINHAND(EquipmentSlot.HAND),

    /** 副手 */
    OFFHAND(EquipmentSlot.OFF_HAND),

    /** 头部 */
    HEAD(EquipmentSlot.HEAD),

    /** 胸部 */
    CHEST(EquipmentSlot.CHEST),

    /** 腿部 */
    LEGS(EquipmentSlot.LEGS),

    /** 脚部（靴子） */
    BOOTS(EquipmentSlot.FEET);

    /** 对应的 Bukkit 槽位 */
    private final EquipmentSlot bukkitSlot;

    NpcEquipmentSlot(EquipmentSlot bukkitSlot) {
        this.bukkitSlot = bukkitSlot;
    }

    /**
     * 转换为 Bukkit {@link EquipmentSlot}。
     *
     * @return Bukkit 装备槽位
     */
    public EquipmentSlot toBukkitSlot() {
        return bukkitSlot;
    }

    /**
     * 由 Bukkit {@link EquipmentSlot} 反向构造本枚举。
     *
     * @param slot Bukkit 装备槽位，不可为 null
     * @return 对应的 NPC 装备槽位
     * @throws IllegalArgumentException 当传入未知槽位（理论不会发生）
     */
    public static NpcEquipmentSlot fromBukkitSlot(EquipmentSlot slot) {
        Objects.requireNonNull(slot, "slot cannot be null");
        for (NpcEquipmentSlot value : values()) {
            if (value.bukkitSlot == slot) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown EquipmentSlot: " + slot);
    }
}
