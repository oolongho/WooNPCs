package com.oolongho.woonpc.nms.versions;

import com.oolongho.woonpc.nms.NmsAdapter;
import com.oolongho.woonpc.nms.dto.MetadataEntry;
import com.oolongho.woonpc.nms.dto.NpcEquipmentData;
import com.oolongho.woonpc.nms.dto.NpcMetadataData;
import com.oolongho.woonpc.nms.dto.NpcSpawnData;
import com.oolongho.woonpc.nms.dto.NpcTexture;
import com.oolongho.woonpc.nms.util.PacketFactory;
import com.oolongho.woonpc.nms.util.ReflectUtil;
import com.oolongho.woonpc.nms.util.WooNPCsReflectException;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Minecraft 1.21.2 - 1.21.4 NMS 适配器实现。
 *
 * <p>覆盖 1.21.2 至 1.21.4 全部补丁版本。此版本区间的 NMS 特征：</p>
 * <ul>
 *   <li>{@code SynchedEntityData.DataValue.create(EntityDataAccessor, Object)} —— 需要 accessor</li>
 *   <li>{@code ClientboundTeleportEntityPacket} 公开构造器签名为
 *       {@code (int, PositionMoveRotation, Set<Relative>, boolean)}</li>
 *   <li>{@code ClientboundRotateHeadPacket} 仅暴露 {@code (Entity, byte)} 构造器，
 *       需通过 {@code RegistryFriendlyByteBuf + STREAM_CODEC.decode} 绕过</li>
 *   <li>{@code com.mojang.authlib.GameProfile} 为普通类，2 参构造器 {@code (UUID, String)}，
 *       通过 {@code getProperties().put(key, property)} 添加皮肤纹理</li>
 * </ul>
 *
 * <p><b>不支持 1.21.0-1.21.1</b>（与 fancynpcs-v2 决策一致，NMS API 差异过大）。</p>
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
 * </ul>
 *
 * <h2>子类覆盖点</h2>
 * <ul>
 *   <li>{@link Nms_1_21_5}：1.21.5+ DataValue.create 签名变化（已由 PacketFactory 反射自适应）</li>
 *   <li>{@code Nms_1_21_11}：1.21.9+ GameProfile 改为 record 风格，需覆盖
 *       {@link #createGameProfile} 使用 3 参构造器 + PropertyMap</li>
 * </ul>
 *
 * <p>本类为内部实现，由 {@link com.oolongho.woonpc.nms.NmsAdapterFactory} 按版本选择实例化。
 * 所有方法必须在主线程调用（PlayerConnection 非线程安全）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public class Nms_1_21 implements NmsAdapter {

    /** GameProfile 反射类缓存（供 createGameProfile 钩子方法使用） */
    protected static final Class<?> GAME_PROFILE_CLASS =
            ReflectUtil.getClass("com.mojang.authlib.GameProfile");

    /** authlib Property 反射类缓存 */
    protected static final Class<?> PROPERTY_CLASS =
            ReflectUtil.getClass("com.mojang.authlib.properties.Property");

    @Override
    public void spawnPlayer(Player player, NpcSpawnData spawnData) {
        // 阶段 1：PlayerInfo(ADD_PLAYER)，始终 listed=true 确保实体被客户端接受
        // GameProfile 由本类 createGameProfile 钩子方法构造（1.21.9+ 子类覆盖）
        Object gameProfile = createGameProfile(
                spawnData.uuid(), spawnData.username(), spawnData.texture());
        Object infoAddPacket = PacketFactory.createPlayerInfoAddPacket(
                spawnData.uuid(), gameProfile, spawnData.displayName(), true, 0);

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
                    spawnData.uuid(), gameProfile, false);
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
        // UPDATE_LISTED 包仅依赖 UUID 匹配客户端已注册条目，但仍需 GameProfile 引用满足 Entry 构造器签名。
        // 1.21.9+ 由于 GameProfile 变为 record，无法用 2 参构造器创建占位实例，故也走 createGameProfile 钩子。
        Object gameProfile = createGameProfile(uuid, "", null);
        Object packet = PacketFactory.createPlayerInfoUpdateListedPacket(uuid, gameProfile, false);
        PacketFactory.sendPacket(player, packet);
    }

    @Override
    public String getVersion() {
        return "1.21";
    }

    /**
     * 构造 NMS {@code GameProfile} 实例（含可选 textures property）。
     *
     * <p><b>版本差异</b>：</p>
     * <ul>
     *   <li>1.21.2-1.21.8（本类实现）：GameProfile 为普通类，使用 2 参构造器
     *       {@code new GameProfile(uuid, name)}，再通过 {@code getProperties().put("textures", property)} 注入皮肤</li>
     *   <li>1.21.9+（{@code Nms_1_21_11} 覆盖）：GameProfile 变为 record 风格，
     *       需使用 3 参构造器 {@code new GameProfile(uuid, name, propertyMap)}，
     *       propertyMap 由 {@code new PropertyMap(ImmutableMultimap.of("textures", property))} 构造</li>
     * </ul>
     *
     * <p>本方法为子类覆盖点，由 {@link PacketFactory#createPlayerInfoAddPacket} 与
     * {@link PacketFactory#createPlayerInfoUpdateListedPacket} 的调用方（本类的 spawnPlayer / sendTabRemove）
     * 调用，将构造好的 GameProfile 传入 PacketFactory。</p>
     *
     * @param uuid     NPC 的 UUID
     * @param username GameProfile 玩家名
     * @param texture  皮肤纹理，null 表示无皮肤（默认 Steve/Alex）
     * @return NMS GameProfile 实例
     */
    protected Object createGameProfile(UUID uuid, String username, @Nullable NpcTexture texture) {
        try {
            Object profile = GAME_PROFILE_CLASS
                    .getConstructor(UUID.class, String.class)
                    .newInstance(uuid, username);
            if (texture != null) {
                Object property = PROPERTY_CLASS
                        .getConstructor(String.class, String.class, String.class)
                        .newInstance("textures", texture.value(), texture.signature());
                Object propertyMap = GAME_PROFILE_CLASS.getMethod("getProperties").invoke(profile);
                propertyMap.getClass().getMethod("put", Object.class, Object.class)
                        .invoke(propertyMap, "textures", property);
            }
            return profile;
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to construct GameProfile for " + username, e);
        }
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
