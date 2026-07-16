package com.oolongho.woonpc.nms.dto;

import org.bukkit.Location;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * NPC 头顶显示名数据。
 *
 * <p>携带一个独立的 TextDisplay 实体 ID（由 EntityIdGenerator 分配），
 * 通过 {@code ClientboundAddEntityPacket} 生成 TextDisplay 并跟随 NPC 头顶。</p>
 *
 * <p>NmsAdapter 实现负责：生成 TextDisplay → 设置 custom_name → 绑定为 NPC 的乘客，
 * 或通过 setCustomNameVisible 直接在玩家实体上显示（视版本协议而定）。</p>
 *
 * @param displayEntityId 显示名实体的 ID（独立于 NPC entityId）
 * @param displayName     显示文本（含颜色代码，可为 Adventure Component 序列化）
 * @param location        显示名实体生成位置（通常为 NPC 头顶偏移），不可为 null
 * @author oolongho
 */
@ApiStatus.Internal
public record NpcDisplayNameData(
        int displayEntityId,
        @Nullable String displayName,
        Location location
) {
    public NpcDisplayNameData {
        Objects.requireNonNull(location, "location cannot be null");
    }
}
