package com.oolongho.woonpc.nms.versions;

import com.oolongho.woonpc.nms.NmsAdapter;
import com.oolongho.woonpc.nms.dto.MetadataEntry;
import com.oolongho.woonpc.nms.dto.NpcDisplayNameData;
import com.oolongho.woonpc.nms.dto.NpcEquipmentData;
import com.oolongho.woonpc.nms.dto.NpcMetadataData;
import com.oolongho.woonpc.nms.dto.NpcSpawnData;
import com.oolongho.woonpc.nms.util.PacketFactory;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Minecraft 1.21.0 - 1.21.4 NMS 适配器实现。
 *
 * <p>覆盖 1.21.0 至 1.21.4 全部补丁版本。此版本区间的 NMS 特征：</p>
 * <ul>
 *   <li>{@code SynchedEntityData.DataValue.create(EntityDataAccessor, Object)} —— 需要 accessor</li>
 *   <li>{@code ClientboundTeleportEntityPacket} 公开构造器签名为
 *       {@code (int, PositionMoveRotation, Set<Relative>, boolean)}</li>
 *   <li>{@code ClientboundRotateHeadPacket} 仅暴露 {@code (Entity, byte)} 构造器，
 *       需通过 {@code RegistryFriendlyByteBuf + STREAM_CODEC.decode} 绕过</li>
 * </ul>
 *
 * <h2>包发送流程</h2>
 * <ul>
 *   <li>spawn：PlayerInfo(ADD) → AddPlayer → SetEntityData → (若 !showInTab) PlayerInfo(UPDATE_LISTED, listed=false)</li>
 *   <li>despawn：RemoveEntities → PlayerInfo(REMOVE)</li>
 *   <li>updateMetadata：SetEntityData</li>
 *   <li>updateLocation：TeleportEntity</li>
 *   <li>updateEquipment：SetEquipment</li>
 *   <li>updateHeadRotation：RotateHead</li>
 *   <li>sendTabRemove：PlayerInfo(UPDATE_LISTED, listed=false)</li>
 *   <li>sendDisplayName：AddEntity(TextDisplay) + SetEntityData(text)</li>
 * </ul>
 *
 * <p>本类为内部实现，由 {@link com.oolongho.woonpc.nms.NmsAdapterFactory} 按版本选择实例化。
 * 所有方法必须在主线程调用（PlayerConnection 非线程安全）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public class Nms_1_21 implements NmsAdapter {

    /** TextDisplay 文本内容的 DataWatcher 索引（1.21+） */
    private static final int TEXT_DISPLAY_TEXT_INDEX = 23;

    /** TextDisplay 文本的序列化器 ID（Component = 4） */
    private static final int SER_COMPONENT = 4;

    @Override
    public void spawnPlayer(Player player, NpcSpawnData spawnData) {
        // 阶段 1：PlayerInfo(ADD_PLAYER)，始终 listed=true 确保实体被客户端接受
        Object infoAddPacket = PacketFactory.createPlayerInfoAddPacket(
                spawnData.uuid(), spawnData.username(), spawnData.texture(),
                spawnData.displayName(), true, 0);

        // 阶段 2：AddPlayer（客户端生成玩家实体）
        Object addPlayerPacket = PacketFactory.createAddPlayerPacket(
                spawnData.entityId(), spawnData.uuid(), spawnData.location());

        // 阶段 3：SetEntityData（皮肤层、自定义名、pose、无重力等）
        List<MetadataEntry> entries = EntityMetadataBuilder.build(
                buildSpawnNpcData(spawnData));
        Object metadataPacket = PacketFactory.createMetadataPacket(spawnData.entityId(), entries);

        // 批量发送 spawn 三件套
        PacketFactory.sendPackets(player, List.of(infoAddPacket, addPlayerPacket, metadataPacket));

        // 阶段 4：若 !showInTab，从 tab 列表移除（实体仍保留）
        if (!spawnData.showInTab()) {
            Object removeListedPacket = PacketFactory.createPlayerInfoUpdateListedPacket(
                    spawnData.uuid(), false);
            PacketFactory.sendPacket(player, removeListedPacket);
        }
    }

    @Override
    public void despawn(Player player, int entityId, UUID uuid) {
        Object removeEntitiesPacket = PacketFactory.createRemoveEntitiesPacket(entityId);
        Object playerInfoRemovePacket = PacketFactory.createPlayerInfoRemovePacket(uuid);
        PacketFactory.sendPackets(player, List.of(removeEntitiesPacket, playerInfoRemovePacket));
    }

    @Override
    public void updateMetadata(Player player, NpcMetadataData data) {
        Object packet = PacketFactory.createMetadataPacket(data.entityId(), data.entries());
        PacketFactory.sendPacket(player, packet);
    }

    @Override
    public void updateLocation(Player player, int entityId, Location location, boolean onGround) {
        Object packet = PacketFactory.createTeleportPacket(entityId, location, onGround);
        PacketFactory.sendPacket(player, packet);
    }

    @Override
    public void updateEquipment(Player player, NpcEquipmentData data) {
        Object packet = PacketFactory.createEquipmentPacket(data.entityId(), data.equipment());
        PacketFactory.sendPacket(player, packet);
    }

    @Override
    public void updateHeadRotation(Player player, int entityId, float yaw, float pitch) {
        Object packet = PacketFactory.createRotateHeadPacket(entityId, yaw);
        PacketFactory.sendPacket(player, packet);
    }

    @Override
    public void sendTabRemove(Player player, int entityId, UUID uuid) {
        Object packet = PacketFactory.createPlayerInfoUpdateListedPacket(uuid, false);
        PacketFactory.sendPacket(player, packet);
    }

    @Override
    public void sendDisplayName(Player player, NpcDisplayNameData data) {
        // 生成 TextDisplay 实体
        UUID displayUuid = UUID.randomUUID();
        Object spawnPacket = PacketFactory.createAddTextDisplayPacket(
                data.displayEntityId(), displayUuid, data.location());

        // 构建文本元数据（DataWatcher 索引 23 = Component 文本）
        List<MetadataEntry> entries = new ArrayList<>(1);
        Object textComponent = data.displayName() != null
                ? PacketFactory.createComponent(data.displayName())
                : PacketFactory.createComponent("");
        entries.add(new MetadataEntry(TEXT_DISPLAY_TEXT_INDEX, SER_COMPONENT, textComponent));
        Object metadataPacket = PacketFactory.createMetadataPacket(data.displayEntityId(), entries);

        PacketFactory.sendPackets(player, List.of(spawnPacket, metadataPacket));
    }

    @Override
    public String getVersion() {
        return "1.21";
    }

    /**
     * 从 {@link NpcSpawnData} 构建最小化 {@link com.oolongho.woonpc.api.NpcData} 供
     * {@link EntityMetadataBuilder#build} 使用。
     *
     * <p>NpcSpawnData 不含全部 NpcData 字段（如 effects、pose 等），此处用默认值填充。
     * spawn 阶段的元数据仅包含皮肤层、自定义名、无重力等基础项。</p>
     */
    private static com.oolongho.woonpc.api.NpcData buildSpawnNpcData(NpcSpawnData spawnData) {
        return com.oolongho.woonpc.api.NpcData.builder(
                        spawnData.uuid(), spawnData.username(), spawnData.location())
                .displayName(spawnData.displayName())
                .build();
    }
}
