package com.oolongho.woonpc.nms;

import com.oolongho.woonpc.nms.dto.NpcDisplayNameData;
import com.oolongho.woonpc.nms.dto.NpcEquipmentData;
import com.oolongho.woonpc.nms.dto.NpcMetadataData;
import com.oolongho.woonpc.nms.dto.NpcSpawnData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

/**
 * NMS 适配器接口。
 *
 * <p>定义与 Minecraft 服务端版本相关的数据包 NPC 操作契约。
 * 所有版本特定的包发送逻辑（{@code Nms_1_21}、{@code Nms_1_21_5}、{@code Nms_26_1} 等）
 * 均实现此接口，由 {@link NmsAdapterFactory} 按服务端版本选择。</p>
 *
 * <p>本接口为内部 API，对外暴露的公共 API 是 {@code Npc} 抽象类（Task 3 创建）。
 * 调用方不应直接持有此接口引用，而应通过 Npc 抽象层间接调用。</p>
 *
 * <h2>线程安全约定</h2>
 * <p>所有方法均需在主线程调用（数据包发送涉及 PlayerConnection，需同步发送）。
 * 上层 Manager 负责 ensureSync 切换。</p>
 *
 * <h2>包发送顺序约定</h2>
 * <ul>
 *   <li>{@link #spawnPlayer}：PlayerInfo(ADD) → AddPlayer → SetEntityData → (若 !showInTab) PlayerInfo(UPDATE_LISTED, listed=false)</li>
 *   <li>{@link #despawn}：RemoveEntities → PlayerInfo(REMOVE)</li>
 *   <li>其他更新方法：发送对应单个包</li>
 * </ul>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public interface NmsAdapter {

    /**
     * 向指定玩家生成一个玩家型 NPC。
     *
     * <p>内部按顺序发送：
     * ClientboundPlayerInfoUpdatePacket(ADD_PLAYER) →
     * ClientboundAddPlayerPacket →
     * ClientboundSetEntityDataPacket →
     * （若 {@code spawnData.showInTab() == false}）ClientboundPlayerInfoUpdatePacket(UPDATE_LISTED, listed=false)</p>
     *
     * @param player    目标玩家（接收数据包者）
     * @param spawnData NPC 生成数据
     */
    void spawnPlayer(Player player, NpcSpawnData spawnData);

    /**
     * 向指定玩家销毁一个 NPC。
     *
     * <p>内部按顺序发送：
     * ClientboundRemoveEntitiesPacket(entityId) →
     * ClientboundPlayerInfoRemovePacket(uuid)</p>
     *
     * @param player   目标玩家
     * @param entityId NPC 实体 ID
     * @param uuid     NPC 的 UUID（用于 PlayerInfo 移除）
     */
    void despawn(Player player, int entityId, UUID uuid);

    /**
     * 向指定玩家更新 NPC 的元数据。
     *
     * <p>内部发送 ClientboundSetEntityDataPacket。</p>
     *
     * @param player 目标玩家
     * @param data   元数据更新数据
     */
    void updateMetadata(Player player, NpcMetadataData data);

    /**
     * 向指定玩家更新 NPC 的位置（瞬移）。
     *
     * <p>内部发送 ClientboundTeleportEntityPacket 或 ClientboundMoveEntityPacket.PosRot，
     * 视位移大小与协议版本而定。</p>
     *
     * @param player    目标玩家
     * @param entityId  NPC 实体 ID
     * @param location  新位置
     * @param onGround  是否在地面（影响动画）
     */
    void updateLocation(Player player, int entityId, Location location, boolean onGround);

    /**
     * 向指定玩家更新 NPC 的装备。
     *
     * <p>内部发送 ClientboundSetEquipmentPacket。</p>
     *
     * @param player   目标玩家
     * @param data     装备更新数据
     */
    void updateEquipment(Player player, NpcEquipmentData data);

    /**
     * 向指定玩家更新 NPC 的头部朝向。
     *
     * <p>内部发送 ClientboundRotateHeadPacket，必要时配合 ClientboundMoveEntityPacket.Rot。</p>
     *
     * @param player   目标玩家
     * @param entityId NPC 实体 ID
     * @param yaw      偏航角（度）
     * @param pitch    俯仰角（度）
     */
    void updateHeadRotation(Player player, int entityId, float yaw, float pitch);

    /**
     * 向指定玩家发送 tab 列表移除包。
     *
     * <p>在 {@link #spawnPlayer} 后调用，确保 NPC 不在 tab 列表显示。
     * 内部发送 ClientboundPlayerInfoUpdatePacket(UPDATE_LISTED, listed=false)。</p>
     *
     * @param player   目标玩家
     * @param entityId NPC 实体 ID
     * @param uuid     NPC 的 UUID
     */
    void sendTabRemove(Player player, int entityId, UUID uuid);

    /**
     * 向指定玩家发送/更新 NPC 头顶显示名。
     *
     * <p>内部通过 TextDisplay 实体跟随 NPC，或直接更新玩家实体的 custom_name。
     * 首次调用时生成 TextDisplay，后续调用更新其 custom_name。</p>
     *
     * @param player 目标玩家
     * @param data   显示名数据
     */
    void sendDisplayName(Player player, NpcDisplayNameData data);

    /**
     * 获取本适配器支持的 Minecraft 版本标识（如 {@code "1.21.5"}）。
     *
     * @return 版本字符串
     */
    String getVersion();
}
