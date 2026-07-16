package com.oolongho.woonpc.nms.dto;

import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.Objects;

/**
 * NPC 装备更新数据。
 *
 * <p>对应 NMS {@code ClientboundSetEquipmentPacket} 的载荷，
 * 携带实体 ID 与槽位-物品映射。</p>
 *
 * @param entityId 目标实体 ID
 * @param equipment 槽位到物品的映射（HEAD/CHEST/LEGS/FEET/MAIN_HAND/OFF_HAND），
 *                  物品可为 null 表示清空该槽位
 * @author oolongho
 */
@ApiStatus.Internal
public record NpcEquipmentData(
        int entityId,
        Map<EquipmentSlot, ItemStack> equipment
) {
    public NpcEquipmentData {
        Objects.requireNonNull(equipment, "equipment cannot be null");
        if (equipment.isEmpty()) {
            throw new IllegalArgumentException("equipment map must not be empty");
        }
        equipment = Map.copyOf(equipment);
    }
}
