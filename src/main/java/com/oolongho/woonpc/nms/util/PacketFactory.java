package com.oolongho.woonpc.nms.util;

import com.oolongho.woonpc.nms.dto.MetadataEntry;
import com.oolongho.woonpc.nms.dto.NpcTexture;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 数据包构建辅助类。
 *
 * <p>封装各类 {@code ClientboundXxxPacket} 的反射构造逻辑，供 {@code Nms_1_21} 等适配器实现调用。
 * 设计原则：<b>Paper 已暴露的包 API 优先 + 反射兜底</b>。所有 NMS 类通过 {@link ReflectUtil}
 * 加载并缓存 Field/Method/Constructor，避免重复反射开销。</p>
 *
 * <h2>版本自适应</h2>
 * <p>1.21.0 - 1.21.5+ 的 NMS API 在反射视角下大部分一致。本类对少数差异点
 * （如 {@code DataValue.create} 签名变化、{@code ClientboundRotateHeadPacket} 构造器
 * 需要 Entity 对象等）通过反射自动适配，使 {@code Nms_1_21} 与 {@code Nms_1_21_5}
 * 可共用同一套发包逻辑。</p>
 *
 * <h2>包发送</h2>
 * <p>{@link #sendPacket} 通过反射调用 {@code CraftPlayer.getHandle().connection.send(packet)}
 * 发送；{@link #sendPackets} 优先使用 {@code ClientboundBundlePacket} 批量发送，
 * 单个包时直接走 {@link #sendPacket} 避免包封装开销。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class PacketFactory {

    private PacketFactory() {
        throw new IllegalAccessError("Utility class");
    }

    // ==================== NMS 类缓存 ====================

    private static final Class<?> PACKET_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.Packet");
    private static final Class<?> REGISTRY_FRIENDLY_BYTE_BUF_CLASS =
            ReflectUtil.getClass("net.minecraft.network.RegistryFriendlyByteBuf");
    private static final Class<?> VEC3_CLASS =
            ReflectUtil.getClass("net.minecraft.world.phys.Vec3");
    private static final Class<?> ENTITY_TYPE_CLASS =
            ReflectUtil.getClass("net.minecraft.world.entity.EntityType");
    private static final Class<?> ENTITY_CLASS =
            ReflectUtil.getClass("net.minecraft.world.entity.Entity");
    private static final Class<?> PLAYER_ENTITY_CLASS =
            ReflectUtil.getClass("net.minecraft.world.entity.player.Player");
    private static final Class<?> TEXT_DISPLAY_CLASS =
            ReflectUtil.getClass("net.minecraft.world.entity.Display$TextDisplay");
    private static final Class<?> NMS_EQUIPMENT_SLOT_CLASS =
            ReflectUtil.getClass("net.minecraft.world.entity.EquipmentSlot");
    private static final Class<?> COMPONENT_CLASS =
            ReflectUtil.getClass("net.minecraft.network.chat.Component");
    private static final Class<?> GAME_PROFILE_CLASS =
            ReflectUtil.getClass("com.mojang.authlib.GameProfile");
    private static final Class<?> PROPERTY_CLASS =
            ReflectUtil.getClass("com.mojang.authlib.properties.Property");
    private static final Class<?> GAME_TYPE_CLASS =
            ReflectUtil.getClass("net.minecraft.world.level.GameType");
    private static final Class<?> DATA_VALUE_CLASS =
            ReflectUtil.getClass("net.minecraft.network.syncher.SynchedEntityData$DataValue");
    private static final Class<?> ENTITY_DATA_ACCESSOR_CLASS =
            ReflectUtil.getClass("net.minecraft.network.syncher.EntityDataAccessor");
    private static final Class<?> PAIR_CLASS =
            ReflectUtil.getClass("com.mojang.datafixers.util.Pair");
    private static final Class<?> POSITION_MOVE_ROTATION_CLASS =
            ReflectUtil.getClass("net.minecraft.world.entity.PositionMoveRotation");

    // ==================== Netty Unpooled 反射缓存（ByteBuf 创建用） ====================

    private static final Class<?> UNPOOLED_CLASS =
            ReflectUtil.getClass("io.netty.buffer.Unpooled");
    private static final Method UNPOOLED_BUFFER_METHOD =
            ReflectUtil.getMethod(UNPOOLED_CLASS, "buffer");

    // ==================== 包类缓存 ====================

    private static final Class<?> PLAYER_INFO_UPDATE_PACKET_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
    private static final Class<?> PLAYER_INFO_ENTRY_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");
    private static final Class<?> PLAYER_INFO_ACTION_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
    private static final Class<?> PLAYER_INFO_REMOVE_PACKET_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");
    private static final Class<?> ADD_ENTITY_PACKET_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
    private static final Class<?> REMOVE_ENTITIES_PACKET_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
    private static final Class<?> SET_ENTITY_DATA_PACKET_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
    private static final Class<?> TELEPORT_PACKET_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket");
    private static final Class<?> ROTATE_HEAD_PACKET_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundRotateHeadPacket");
    private static final Class<?> SET_EQUIPMENT_PACKET_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket");
    private static final Class<?> BUNDLE_PACKET_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundBundlePacket");

    // ==================== CraftPlayer / ServerPlayer ====================

    private static final Class<?> CRAFT_PLAYER_CLASS =
            ReflectUtil.getClass("org.bukkit.craftbukkit.entity.CraftPlayer");
    private static final Method CRAFT_PLAYER_GET_HANDLE =
            ReflectUtil.getMethod(CRAFT_PLAYER_CLASS, "getHandle");
    private static final Class<?> SERVER_PLAYER_CLASS =
            ReflectUtil.getClass("net.minecraft.server.level.ServerPlayer");
    private static final java.lang.reflect.Field SERVER_PLAYER_CONNECTION_FIELD =
            ReflectUtil.getField(SERVER_PLAYER_CLASS, "connection");
    private static final Method CONNECTION_SEND_METHOD =
            ReflectUtil.getMethod(SERVER_PLAYER_CONNECTION_FIELD.getType(), "send", PACKET_CLASS);

    // ==================== NMS 常量缓存 ====================

    private static final Object PLAYER_ENTITY_TYPE =
            ReflectUtil.getFieldValue(ENTITY_TYPE_CLASS, "PLAYER");

    private static final Object TEXT_DISPLAY_ENTITY_TYPE =
            ReflectUtil.getFieldValue(ENTITY_TYPE_CLASS, "TEXT_DISPLAY");

    private static final Object VEC3_ZERO =
            ReflectUtil.getFieldValue(VEC3_CLASS, "ZERO");

    private static final Object GAME_TYPE_SURVIVAL =
            ReflectUtil.getFieldValue(GAME_TYPE_CLASS, "SURVIVAL");

    // ==================== Component.literal 缓存 ====================

    private static final Method COMPONENT_LITERAL =
            ReflectUtil.getMethod(COMPONENT_CLASS, "literal", String.class);

    // ==================== DataValue 构造策略缓存 ====================

    /** 1.21.5+ 的 DataValue.create(value, id) 静态方法，不存在则 null */
    private static final Method DATA_VALUE_CREATE_V115 =
            ReflectUtil.getMethodOrNull(DATA_VALUE_CLASS, "create", Object.class, int.class);

    /** 1.21.4- 的 DataValue.create(accessor, value) 静态方法，不存在则 null */
    private static final Method DATA_VALUE_CREATE_V114 =
            DATA_VALUE_CREATE_V115 != null ? null
                    : ReflectUtil.getMethod(DATA_VALUE_CLASS, "create",
                            ENTITY_DATA_ACCESSOR_CLASS, Object.class);

    // ==================== Pair.of 静态方法缓存 ====================

    private static final Method PAIR_OF_METHOD =
            ReflectUtil.getMethod(PAIR_CLASS, "of", Object.class, Object.class);

    // ==================== index → accessor 字段映射（1.21.4- 用） ====================

    /**
     * metadata index → (holder class, accessor 字段名) 映射，用于 1.21.4- 的 DataValue 构造。
     * 包含 Player 与 TextDisplay 两类实体的 DataWatcher 索引。
     */
    private static final Map<Integer, AccessorRef> ACCESSOR_REFS = Map.of(
            0, new AccessorRef(ENTITY_CLASS, "DATA_SHARED_FLAGS_ID"),
            2, new AccessorRef(ENTITY_CLASS, "DATA_CUSTOM_NAME"),
            3, new AccessorRef(ENTITY_CLASS, "DATA_CUSTOM_NAME_VISIBLE"),
            5, new AccessorRef(ENTITY_CLASS, "DATA_NO_GRAVITY"),
            6, new AccessorRef(ENTITY_CLASS, "DATA_POSE"),
            16, new AccessorRef(PLAYER_ENTITY_CLASS, "DATA_PLAYER_MODE_CUSTOMISATION"),
            23, new AccessorRef(TEXT_DISPLAY_CLASS, "DATA_TEXT_ID")
    );

    /** accessor 字段引用（holder class + 字段名） */
    private record AccessorRef(Class<?> holderClass, String fieldName) {
    }

    // ==================== RegistryAccess 缓存（RegistryFriendlyByteBuf 构造用） ====================

    private static volatile Object cachedRegistryAccess;

    // ==================== PlayerInfo 包 ====================

    /**
     * 创建 PlayerInfo 添加包（ADD_PLAYER）。
     *
     * <p>对应 {@code ClientboundPlayerInfoUpdatePacket}（Action=ADD_PLAYER + UPDATE_LISTED + UPDATE_DISPLAY_NAME），
     * 用于在客户端注册 NPC 的 tab 条目（为后续 AddPlayer 包做准备）。</p>
     *
     * @param uuid        NPC 的 UUID
     * @param username    GameProfile 玩家名
     * @param texture     皮肤纹理，null 表示默认 Steve/Alex
     * @param displayName tab 显示名文本，null 表示不显示
     * @param listed      是否在 tab 列表显示（通常 false，spawn 后立即移除）
     * @param latency     模拟延迟（毫秒），通常 0
     * @return NMS PlayerInfoUpdatePacket 实例
     */
    public static Object createPlayerInfoAddPacket(UUID uuid, String username,
                                                   @Nullable NpcTexture texture,
                                                   @Nullable String displayName,
                                                   boolean listed, int latency) {
        Object gameProfile = createGameProfile(uuid, username, texture);
        Object displayComponent = displayName != null ? createComponent(displayName) : null;
        Object entry = createPlayerInfoEntry(uuid, gameProfile, listed, latency, displayComponent);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Object actionSet = EnumSet.of(
                Enum.valueOf((Class<Enum>) PLAYER_INFO_ACTION_CLASS, "ADD_PLAYER"),
                Enum.valueOf((Class<Enum>) PLAYER_INFO_ACTION_CLASS, "UPDATE_LISTED"),
                Enum.valueOf((Class<Enum>) PLAYER_INFO_ACTION_CLASS, "UPDATE_DISPLAY_NAME"));

        // 构造 ClientboundPlayerInfoUpdatePacket(EnumSet<Action>, List<Entry>)
        return ReflectUtil.newInstance(PLAYER_INFO_UPDATE_PACKET_CLASS,
                new Class[]{EnumSet.class, List.class}, actionSet, List.of(entry));
    }

    /**
     * 创建 PlayerInfo 移除包（REMOVE_PLAYER）。
     *
     * <p>对应 {@code ClientboundPlayerInfoRemovePacket}，用于从客户端 tab 列表移除 NPC 条目。</p>
     *
     * @param uuid NPC 的 UUID
     * @return NMS PlayerInfoRemovePacket 实例
     */
    public static Object createPlayerInfoRemovePacket(UUID uuid) {
        return ReflectUtil.newInstance(PLAYER_INFO_REMOVE_PACKET_CLASS,
                new Class[]{List.class}, List.of(uuid));
    }

    /**
     * 创建 PlayerInfo 更新 listed 状态包（UPDATE_LISTED）。
     *
     * <p>用于在 spawn 后将 NPC 从 tab 列表移除（listed=false）或重新加入（listed=true）。
     * 客户端通过 UUID 匹配已注册的条目，GameProfile 内容被忽略。</p>
     *
     * @param uuid   NPC 的 UUID
     * @param listed 是否在 tab 列表显示
     * @return NMS PlayerInfoUpdatePacket 实例
     */
    public static Object createPlayerInfoUpdateListedPacket(UUID uuid, boolean listed) {
        Object gameProfile = createGameProfile(uuid, "", null);
        Object entry = createPlayerInfoEntry(uuid, gameProfile, listed, 0, null);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Object actionSet = EnumSet.of(
                Enum.valueOf((Class<Enum>) PLAYER_INFO_ACTION_CLASS, "UPDATE_LISTED"));

        return ReflectUtil.newInstance(PLAYER_INFO_UPDATE_PACKET_CLASS,
                new Class[]{EnumSet.class, List.class}, actionSet, List.of(entry));
    }

    // ==================== 实体生成/移除包 ====================

    /**
     * 创建添加玩家实体包。
     *
     * <p>对应 {@code ClientboundAddEntityPacket}，使客户端生成玩家型实体。
     * 使用 1.21.2+ 的 11 参数构造器（含 headYaw）。</p>
     *
     * @param entityId 数据包实体 ID
     * @param uuid     NPC 的 UUID
     * @param location 生成位置
     * @return NMS AddEntityPacket 实例
     */
    public static Object createAddPlayerPacket(int entityId, UUID uuid, Location location) {
        return ReflectUtil.newInstance(ADD_ENTITY_PACKET_CLASS,
                new Class[]{int.class, UUID.class, double.class, double.class, double.class,
                        float.class, float.class, ENTITY_TYPE_CLASS, int.class, VEC3_CLASS, float.class},
                entityId, uuid, location.getX(), location.getY(), location.getZ(),
                location.getPitch(), location.getYaw(), PLAYER_ENTITY_TYPE, 0, VEC3_ZERO, location.getYaw());
    }

    /**
     * 创建 TextDisplay 添加实体包（用于显示名）。
     *
     * <p>spawn 在 NPC 头顶，通过 TextDisplay 实体的 DATA_TEXT_ID 元数据显示文本。
     * 复用 {@link #createAddPlayerPacket} 的构造逻辑，仅替换 EntityType。</p>
     *
     * @param entityId  TextDisplay 实体 ID
     * @param uuid      TextDisplay 的 UUID（独立于 NPC UUID）
     * @param location  生成位置（NPC 头顶偏移）
     * @return NMS AddEntityPacket 实例
     */
    public static Object createAddTextDisplayPacket(int entityId, UUID uuid, Location location) {
        return ReflectUtil.newInstance(ADD_ENTITY_PACKET_CLASS,
                new Class[]{int.class, UUID.class, double.class, double.class, double.class,
                        float.class, float.class, ENTITY_TYPE_CLASS, int.class, VEC3_CLASS, float.class},
                entityId, uuid, location.getX(), location.getY(), location.getZ(),
                location.getPitch(), location.getYaw(), TEXT_DISPLAY_ENTITY_TYPE, 0, VEC3_ZERO, location.getYaw());
    }

    /**
     * 创建移除实体包。
     *
     * <p>对应 {@code ClientboundRemoveEntitiesPacket}，从客户端销毁指定实体。
     * 支持批量移除多个实体 ID。</p>
     *
     * @param entityIds 待移除的实体 ID 数组
     * @return NMS RemoveEntitiesPacket 实例
     */
    public static Object createRemoveEntitiesPacket(int... entityIds) {
        try {
            Constructor<?> ctor = REMOVE_ENTITIES_PACKET_CLASS.getDeclaredConstructor(int[].class);
            ctor.setAccessible(true);
            return ctor.newInstance((Object) entityIds);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to construct ClientboundRemoveEntitiesPacket", e);
        }
    }

    // ==================== 元数据包 ====================

    /**
     * 创建实体元数据包。
     *
     * <p>对应 {@code ClientboundSetEntityDataPacket}，更新实体的 DataWatcher 值
     * （状态位、自定义名、pose 等）。</p>
     *
     * <p>版本自适应：1.21.5+ 用 {@code DataValue.create(value, id)} 静态方法构造 DataValue；
     * 1.21.4- 用 {@code DataValue.create(accessor, value)} 通过反射获取 accessor
     * （由 {@link #ACCESSOR_REFS} 提供 index → accessor 字段映射）。</p>
     *
     * @param entityId 目标实体 ID
     * @param entries  元数据项列表
     * @return NMS SetEntityDataPacket 实例
     */
    public static Object createMetadataPacket(int entityId, List<MetadataEntry> entries) {
        List<Object> dataValues = new ArrayList<>(entries.size());
        for (MetadataEntry entry : entries) {
            dataValues.add(toDataValue(entry));
        }
        return ReflectUtil.newInstance(SET_ENTITY_DATA_PACKET_CLASS,
                new Class[]{int.class, List.class}, entityId, dataValues);
    }

    // ==================== 位置/移动包 ====================

    /**
     * 创建实体传送包。
     *
     * <p>对应 {@code ClientboundTeleportEntityPacket}，用于大幅位移（跨区块或初次定位）。
     * 使用 1.21+ 的公开构造器 {@code (int, PositionMoveRotation, Set<Relative>, boolean)}。</p>
     *
     * @param entityId  目标实体 ID
     * @param location  目标位置
     * @param onGround  是否在地面
     * @return NMS TeleportEntityPacket 实例
     */
    public static Object createTeleportPacket(int entityId, Location location, boolean onGround) {
        Object position = ReflectUtil.newInstance(VEC3_CLASS,
                new Class[]{double.class, double.class, double.class},
                location.getX(), location.getY(), location.getZ());
        Object movement = ReflectUtil.newInstance(POSITION_MOVE_ROTATION_CLASS,
                new Class[]{VEC3_CLASS, VEC3_CLASS, float.class, float.class},
                position, VEC3_ZERO, location.getYaw(), location.getPitch());
        return ReflectUtil.newInstance(TELEPORT_PACKET_CLASS,
                new Class[]{int.class, POSITION_MOVE_ROTATION_CLASS, Set.class, boolean.class},
                entityId, movement, Set.of(), onGround);
    }

    /**
     * 创建头部旋转包。
     *
     * <p>对应 {@code ClientboundRotateHeadPacket}，更新实体头部朝向。
     * 由于公开构造器需要 Entity 对象（NPC 为数据包实体无真实引用），
     * 此处通过 {@code RegistryFriendlyByteBuf + STREAM_CODEC.decode(buf)} 方式构造。</p>
     *
     * @param entityId 目标实体 ID
     * @param yaw      偏航角（度）
     * @return NMS RotateHeadPacket 实例
     */
    public static Object createRotateHeadPacket(int entityId, float yaw) {
        Object buf = createRegistryFriendlyByteBuf();
        ReflectUtil.invokeMethod(buf, "writeVarInt", entityId);
        ReflectUtil.invokeMethod(buf, "writeByte", (byte) (yaw * 256.0f / 360.0f));
        return decodeViaStreamCodec(ROTATE_HEAD_PACKET_CLASS, buf);
    }

    // ==================== 装备包 ====================

    /**
     * 创建装备更新包。
     *
     * <p>对应 {@code ClientboundSetEquipmentPacket}，更新实体指定槽位的装备。
     * Bukkit {@link EquipmentSlot} 与 NMS {@code EquipmentSlot} 通过 {@code byName} 转换。</p>
     *
     * @param entityId 目标实体 ID
     * @param equipment 槽位到物品的映射（物品可为 null 清空槽位）
     * @return NMS SetEquipmentPacket 实例
     */
    public static Object createEquipmentPacket(int entityId, Map<EquipmentSlot, ItemStack> equipment) {
        List<Object> pairList = new ArrayList<>(equipment.size());
        for (Map.Entry<EquipmentSlot, ItemStack> entry : equipment.entrySet()) {
            Object nmsSlot = bukkitToNmsEquipmentSlot(entry.getKey());
            Object nmsItem = bukkitToNmsItemStack(entry.getValue());
            try {
                pairList.add(PAIR_OF_METHOD.invoke(null, nmsSlot, nmsItem));
            } catch (ReflectiveOperationException e) {
                throw new WooNPCsReflectException("Failed to invoke Pair.of", e);
            }
        }
        return ReflectUtil.newInstance(SET_EQUIPMENT_PACKET_CLASS,
                new Class[]{int.class, List.class}, entityId, pairList);
    }

    // ==================== 包发送 ====================

    /**
     * 向玩家发送一个 NMS 数据包。
     *
     * <p>通过反射调用 {@code CraftPlayer.getHandle().connection.send(packet)} 发送。
     * 必须在主线程调用（PlayerConnection 非线程安全）。</p>
     *
     * @param player 目标玩家
     * @param packet NMS Packet&lt;?&gt; 实例
     */
    public static void sendPacket(Player player, Object packet) {
        try {
            Object serverPlayer = CRAFT_PLAYER_GET_HANDLE.invoke(player);
            Object connection = SERVER_PLAYER_CONNECTION_FIELD.get(serverPlayer);
            CONNECTION_SEND_METHOD.invoke(connection, packet);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to send packet to " + player.getName(), e);
        }
    }

    /**
     * 向玩家批量发送多个 NMS 数据包。
     *
     * <p>多包时优先封装为 {@code ClientboundBundlePacket} 一次性发送，
     * 减少网络往返。单包时直接走 {@link #sendPacket} 避免包封装开销。</p>
     *
     * @param player  目标玩家
     * @param packets NMS Packet&lt;?&gt; 实例列表
     */
    public static void sendPackets(Player player, List<Object> packets) {
        if (packets.isEmpty()) {
            return;
        }
        if (packets.size() == 1) {
            sendPacket(player, packets.get(0));
            return;
        }
        try {
            Object bundlePacket = ReflectUtil.newInstance(BUNDLE_PACKET_CLASS,
                    new Class[]{List.class}, packets);
            sendPacket(player, bundlePacket);
        } catch (WooNPCsReflectException e) {
            // Bundle 包构造失败时回退到逐个发送
            for (Object packet : packets) {
                sendPacket(player, packet);
            }
        }
    }

    // ==================== Component 构造 ====================

    /**
     * 通过反射调用 {@code Component.literal(String)} 创建 NMS Component。
     *
     * @param text 文本内容
     * @return NMS Component 实例
     */
    public static Object createComponent(String text) {
        try {
            return COMPONENT_LITERAL.invoke(null, text);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to invoke Component.literal(String)", e);
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 将 {@link MetadataEntry} 转换为 NMS {@code DataValue} 实例。
     *
     * <p>版本自适应：</p>
     * <ul>
     *   <li>1.21.5+：调用 {@code DataValue.create(value, id)} 静态方法</li>
     *   <li>1.21.4-：通过 {@link #ACCESSOR_REFS} 查找 accessor 字段，
     *       调用 {@code DataValue.create(accessor, value)}</li>
     * </ul>
     */
    private static Object toDataValue(MetadataEntry entry) {
        // 1.21.5+ 路径：DataValue.create(value, id)
        if (DATA_VALUE_CREATE_V115 != null) {
            try {
                return DATA_VALUE_CREATE_V115.invoke(null, entry.value(), entry.index());
            } catch (ReflectiveOperationException e) {
                throw new WooNPCsReflectException(
                        "Failed to create DataValue via 1.21.5+ path (index=" + entry.index() + ")", e);
            }
        }

        // 1.21.4- 路径：DataValue.create(accessor, value)
        AccessorRef ref = ACCESSOR_REFS.get(entry.index());
        if (ref == null) {
            throw new WooNPCsReflectException(
                    "No accessor mapping for metadata index " + entry.index()
                            + " (1.21.4- requires accessor field mapping)");
        }
        Object accessor = ReflectUtil.getFieldValue(ref.holderClass(), ref.fieldName());
        try {
            return DATA_VALUE_CREATE_V114.invoke(null, accessor, entry.value());
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException(
                    "Failed to create DataValue via 1.21.4- path (index=" + entry.index() + ")", e);
        }
    }

    /**
     * 创建 GameProfile 实例，附加 textures property（若有皮肤纹理）。
     */
    private static Object createGameProfile(UUID uuid, String username, @Nullable NpcTexture texture) {
        try {
            Object profile = GAME_PROFILE_CLASS.getConstructor(UUID.class, String.class)
                    .newInstance(uuid, username);
            if (texture != null) {
                Object property = PROPERTY_CLASS.getConstructor(String.class, String.class, String.class)
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
     * 创建 PlayerInfoUpdatePacket.Entry 实例。
     *
     * <p>1.21+ Entry 签名：(UUID, GameProfile, listed, latency, GameType, displayName, showHat, listOrder, chatSession)
     * chatSession 传 null 表示无聊天会话。通过扫描构造器按参数数量匹配，避免 chatSession
     * 类型在不同版本间的差异（RemoteChatSession.Data 等）。</p>
     */
    private static Object createPlayerInfoEntry(UUID uuid, Object gameProfile,
                                                boolean listed, int latency,
                                                @Nullable Object displayName) {
        for (Constructor<?> ctor : PLAYER_INFO_ENTRY_CLASS.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 9) {
                ctor.setAccessible(true);
                try {
                    return ctor.newInstance(uuid, gameProfile, listed, latency,
                            GAME_TYPE_SURVIVAL, displayName, true, 0, null);
                } catch (ReflectiveOperationException e) {
                    throw new WooNPCsReflectException("Failed to construct PlayerInfoUpdatePacket.Entry", e);
                }
            }
        }
        throw new WooNPCsReflectException("No 9-param constructor found in PlayerInfoUpdatePacket.Entry");
    }

    /**
     * 创建 RegistryFriendlyByteBuf 实例（包装 unpooled heap buffer + 服务端 RegistryAccess）。
     *
     * <p>1.21+ 的 {@code ClientboundRotateHeadPacket.STREAM_CODEC.decode} 要求
     * RegistryFriendlyByteBuf 而非 FriendlyByteBuf。RegistryAccess 从
     * {@code MinecraftServer.getServer().registryAccess()} 获取并缓存。</p>
     */
    private static Object createRegistryFriendlyByteBuf() {
        try {
            Object registry = getRegistryAccess();
            Object byteBuf = UNPOOLED_BUFFER_METHOD.invoke(null);
            for (Constructor<?> ctor : REGISTRY_FRIENDLY_BYTE_BUF_CLASS.getConstructors()) {
                if (ctor.getParameterCount() == 2) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(byteBuf, registry);
                }
            }
            throw new WooNPCsReflectException("No 2-param constructor in RegistryFriendlyByteBuf");
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to construct RegistryFriendlyByteBuf", e);
        }
    }

    /**
     * 通过 STREAM_CODEC.decode(buf) 构造包对象。
     * 用于构造器需要 Entity 对象或参数复杂的包类型。
     */
    private static Object decodeViaStreamCodec(Class<?> packetClass, Object buf) {
        try {
            Object codec = ReflectUtil.getFieldValue(packetClass, "STREAM_CODEC");
            for (Method m : codec.getClass().getMethods()) {
                if (m.getName().equals("decode") && m.getParameterCount() == 1) {
                    if (m.getParameterTypes()[0].isInstance(buf)) {
                        return m.invoke(codec, buf);
                    }
                }
            }
            throw new WooNPCsReflectException(
                    "No decode method accepting " + buf.getClass().getName()
                            + " on STREAM_CODEC of " + packetClass.getName());
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException(
                    "Failed to decode packet via STREAM_CODEC: " + packetClass.getName(), e);
        }
    }

    /**
     * 获取并缓存 MinecraftServer 的 RegistryAccess。
     * 双重检查锁定保证只初始化一次。
     */
    private static Object getRegistryAccess() {
        Object snapshot = cachedRegistryAccess;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (PacketFactory.class) {
            if (cachedRegistryAccess == null) {
                try {
                    Class<?> minecraftServerClass =
                            ReflectUtil.getClass("net.minecraft.server.MinecraftServer");
                    Method getServer = ReflectUtil.getMethod(minecraftServerClass, "getServer");
                    Object server = getServer.invoke(null);
                    Method registryAccess = ReflectUtil.getMethod(minecraftServerClass, "registryAccess");
                    cachedRegistryAccess = registryAccess.invoke(server);
                } catch (ReflectiveOperationException e) {
                    throw new WooNPCsReflectException("Failed to get RegistryAccess from MinecraftServer", e);
                }
            }
            return cachedRegistryAccess;
        }
    }

    /**
     * 将 Bukkit {@link EquipmentSlot} 转换为 NMS {@code EquipmentSlot}。
     * 通过反射调用 {@code EquipmentSlot.byName(String)}，name 取 NMS 枚举名。
     */
    private static Object bukkitToNmsEquipmentSlot(EquipmentSlot bukkitSlot) {
        String nmsName = switch (bukkitSlot) {
            case HAND -> "mainhand";
            case OFF_HAND -> "offhand";
            case FEET -> "feet";
            case LEGS -> "legs";
            case CHEST -> "chest";
            case HEAD -> "head";
            case BODY -> "body";
        };
        Method byName = ReflectUtil.getMethod(NMS_EQUIPMENT_SLOT_CLASS, "byName", String.class);
        try {
            return byName.invoke(null, nmsName);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to convert EquipmentSlot: " + bukkitSlot, e);
        }
    }

    /**
     * 将 Bukkit {@link ItemStack} 转换为 NMS {@code ItemStack}。
     * 通过反射调用 {@code CraftItemStack.asNMSCopy(ItemStack)}。
     */
    private static Object bukkitToNmsItemStack(ItemStack bukkitItem) {
        Class<?> craftItemStackClass = ReflectUtil.getClass(
                "org.bukkit.craftbukkit.inventory.CraftItemStack");
        Method asNmsCopy = ReflectUtil.getMethod(craftItemStackClass, "asNMSCopy", ItemStack.class);
        try {
            return asNmsCopy.invoke(null, bukkitItem);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to convert ItemStack to NMS", e);
        }
    }
}
