package com.oolongho.woonpc.nms;

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
 * 所有版本特定的包发送逻辑（{@code Nms_1_21}、{@code Nms_1_21_5}、{@code Nms_1_21_11} 等）
 * 均实现此接口，由 {@link NmsAdapterFactory} 按服务端版本选择。</p>
 *
 * <p>本接口为内部 API，对外暴露的公共 API 是 {@code Npc} 抽象类。
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
     * 向指定玩家更新 NPC 的 tab 列表显示状态。
     *
     * <p>内部发送 {@code ClientboundPlayerInfoUpdatePacket(UPDATE_LISTED, listed=showInTab)}。
     * 客户端通过 UUID 匹配已注册的条目，GameProfile 内容被忽略。</p>
     *
     * <p>使用场景：</p>
     * <ul>
     *   <li>spawn 时若 {@code showInTab=false}：在 Nms_1_21.spawnPlayer 内部直接调用此协议包</li>
     *   <li>运行时切换 SHOW_IN_TAB 字段：由 {@code NpcController.updateTabListVisibility} 调用本方法</li>
     * </ul>
     *
     * @param player    目标玩家
     * @param uuid      NPC 的 UUID
     * @param showInTab true=显示在 tab 列表，false=从 tab 列表移除
     */
    void updateTabListVisibility(Player player, UUID uuid, boolean showInTab);

    /**
     * 向指定玩家更新 NPC 的缩放比例。
     *
     * <p>内部发送 {@code ClientboundUpdateAttributesPacket}，更新 {@code minecraft:scale}
     * 属性的基础值。1.21+ 玩家实体的缩放只能通过 attribute 包发送，metadata 中无对应索引。</p>
     *
     * <p><b>版本兼容</b>：若当前服务端版本不支持 {@code Attributes.SCALE} 字段（理论不存在于
     * 1.21.2+ 全部支持版本），实现可选择 no-op。本接口契约要求方法不抛异常。</p>
     *
     * @param player   目标玩家
     * @param entityId NPC 实体 ID
     * @param scale    缩放比例（1.0 = 原始大小）
     */
    void updateScale(Player player, int entityId, float scale);

    /**
     * 获取本适配器支持的 Minecraft 版本标识（如 {@code "1.21.5"}）。
     *
     * @return 版本字符串
     */
    String getVersion();
}
