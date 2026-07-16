package com.oolongho.woonpc.nms.dto;

import org.bukkit.Location;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * NPC 生成所需数据。
 *
 * <p>调用 {@link com.oolongho.woonpc.nms.NmsAdapter#spawnPlayer} 时传入，
 * 携带生成玩家 NPC 所需的全部信息：实体 ID、UUID、显示名、位置、皮肤纹理等。</p>
 *
 * <p>NmsAdapter 实现按以下顺序发包：</p>
 * <ol>
 *   <li>{@code ClientboundPlayerInfoUpdatePacket}（ADD_PLAYER）—— 注册 tab 条目</li>
 *   <li>{@code ClientboundAddPlayerPacket} —— 客户端生成实体</li>
 *   <li>{@code ClientboundSetEntityDataPacket} —— 元数据（皮肤层、自定义名等）</li>
 *   <li>{@code ClientboundPlayerInfoUpdatePacket}（REMOVE_FROM_LIST）—— 清理 tab 条目（仅 showInTab=false 时）</li>
 * </ol>
 *
 * @param entityId    数据包实体 ID（由 EntityIdGenerator 分配）
 * @param uuid        NPC 的 UUID（用于 PlayerInfo 与实体绑定）
 * @param username    GameProfile 中的玩家名（影响 tab 显示与默认皮肤，建议占位名）
 * @param location    生成位置，不可为 null
 * @param texture     皮肤纹理，null 表示使用默认 Steve/Alex 皮肤
 * @param displayName 头顶自定义名，null 表示不显示
 * @param showInTab   是否在 tab 列表显示（通常 false，spawn 后立即移除）
 * @author oolongho
 */
@ApiStatus.Internal
public record NpcSpawnData(
        int entityId,
        UUID uuid,
        String username,
        Location location,
        @Nullable NpcTexture texture,
        @Nullable String displayName,
        boolean showInTab
) {
    public NpcSpawnData {
        Objects.requireNonNull(uuid, "uuid cannot be null");
        Objects.requireNonNull(location, "location cannot be null");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be null or blank");
        }
    }
}
