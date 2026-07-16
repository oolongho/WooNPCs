package com.oolongho.woonpc.nms.dto;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Objects;

/**
 * NPC 元数据更新数据。
 *
 * <p>对应 NMS {@code ClientboundSetEntityDataPacket} 的载荷，
 * 携带实体 ID 与待更新的元数据项列表。</p>
 *
 * <p>NmsAdapter 实现将 entries 反射转回 {@code SynchedEntityData.DataValue} 列表后发包。</p>
 *
 * @param entityId 目标实体 ID
 * @param entries 元数据项列表，不可为 null 或空
 * @author oolongho
 */
@ApiStatus.Internal
public record NpcMetadataData(
        int entityId,
        List<MetadataEntry> entries
) {
    public NpcMetadataData {
        Objects.requireNonNull(entries, "entries cannot be null");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }
        entries = List.copyOf(entries);
    }
}
