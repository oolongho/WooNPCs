package com.oolongho.woonpc.nms.util;

import com.oolongho.woonpc.nms.dto.MetadataEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    private static final Class<?> GAME_TYPE_CLASS =
            ReflectUtil.getClass("net.minecraft.world.level.GameType");
    private static final Class<?> DATA_VALUE_CLASS =
            ReflectUtil.getClass("net.minecraft.network.syncher.SynchedEntityData$DataValue");
    private static final Class<?> ENTITY_DATA_ACCESSOR_CLASS =
            ReflectUtil.getClass("net.minecraft.network.syncher.EntityDataAccessor");
    private static final Class<?> PAIR_CLASS =
            ReflectUtil.getClass("com.mojang.datafixers.util.Pair");

    /**
     * {@code PositionMoveRotation} 反射类缓存。
     *
     * <p><b>版本可选</b>：仅 1.21.2+ 存在；1.21.0-1.21.1 为 null。
     * {@link #createTeleportPacket} 按本字段是否为 null 分支：
     * 非 null 走 4 参公开构造器，null 走 STREAM_CODEC.decode 路径。</p>
     */
    private static final Class<?> POSITION_MOVE_ROTATION_CLASS =
            ReflectUtil.getClassOrNull("net.minecraft.world.entity.PositionMoveRotation");

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
    private static final Class<?> UPDATE_ATTRIBUTES_PACKET_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket");
    private static final Class<?> ATTRIBUTE_SNAPSHOT_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket$AttributeSnapshot");
    private static final Class<?> ATTRIBUTES_CLASS =
            ReflectUtil.getClass("net.minecraft.world.entity.ai.attributes.Attributes");

    // ==================== Team 包相关 NMS 类缓存 ====================

    private static final Class<?> TEAM_PACKET_CLASS =
            ReflectUtil.getClass("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket");
    private static final Class<?> PLAYER_TEAM_CLASS =
            ReflectUtil.getClass("net.minecraft.world.scores.PlayerTeam");
    private static final Class<?> SCOREBOARD_CLASS =
            ReflectUtil.getClass("net.minecraft.world.scores.Scoreboard");
    private static final Class<?> TEAM_VISIBILITY_CLASS =
            ReflectUtil.getClass("net.minecraft.world.scores.Team$Visibility");
    private static final Class<?> TEAM_COLLISION_RULE_CLASS =
            ReflectUtil.getClass("net.minecraft.world.scores.Team$CollisionRule");
    private static final Class<?> CHAT_FORMATTING_CLASS =
            ReflectUtil.getClass("net.minecraft.ChatFormatting");

    /**
     * {@code TeamColor} 反射类缓存。
     *
     * <p><b>版本可选</b>：1.21.7+ 引入，替代 {@code ChatFormatting} 作为
     * {@code PlayerTeam.setColor} 的参数类型。1.21.0-1.21.6 为 null，
     * 此时 {@code setColor} 接受 {@code ChatFormatting}。</p>
     */
    private static final Class<?> TEAM_COLOR_CLASS =
            ReflectUtil.getClassOrNull("net.minecraft.world.scores.TeamColor");

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

    /**
     * {@code Attributes.SCALE} 静态字段值（{@code Holder<Attribute>}）。
     * 1.21.2+ 全部支持；若某版本无此字段（理论不存在），缓存为 null，发包时跳过。
     */
    private static final Object SCALE_ATTRIBUTE_HOLDER = getStaticFieldOrNull(ATTRIBUTES_CLASS, "SCALE");

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
     * <p><b>版本兼容</b>：GameProfile 在 1.21.9+ 由普通类变为 record（构造器从 2 参变为 3 参），
     * 因此 GameProfile 的构造由调用方（{@code NmsAdapter} 实现类）通过
     * {@code createGameProfile} 钩子方法完成，本方法仅消费预构造好的实例。</p>
     *
     * @param uuid        NPC 的 UUID
     * @param gameProfile 预构造的 NMS GameProfile 实例（包含 textures property）
     * @param displayName tab 显示名文本，null 表示不显示
     * @param listed      是否在 tab 列表显示（通常 false，spawn 后立即移除）
     * @param latency     模拟延迟（毫秒），通常 0
     * @return NMS PlayerInfoUpdatePacket 实例
     */
    public static Object createPlayerInfoAddPacket(UUID uuid, Object gameProfile,
                                                   @Nullable String displayName,
                                                   boolean listed, int latency) {
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
     * <p><b>版本兼容</b>：与 {@link #createPlayerInfoAddPacket} 同理，GameProfile 由调用方预构造。
     * 尽管 UPDATE_LISTED 不依赖 GameProfile 内容，但 Entry 构造器仍要求非 null 的 GameProfile
     * 引用（1.21.9+ 由于 GameProfile 为 record，无法用 2 参构造器创建占位实例）。</p>
     *
     * @param uuid        NPC 的 UUID
     * @param gameProfile 预构造的 NMS GameProfile 实例（占位即可）
     * @param listed      是否在 tab 列表显示
     * @return NMS PlayerInfoUpdatePacket 实例
     */
    public static Object createPlayerInfoUpdateListedPacket(UUID uuid, Object gameProfile, boolean listed) {
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
     * 使用 11 参数构造器（含 headYaw）。</p>
     *
     * <p><b>版本自适应</b>：第 11 参（headYaw）类型在版本间存在差异，
     * 1.21.2+ 为 {@code float}，1.21.0-1.21.1 为 {@code double}。
     * 通过扫描 11 参构造器并按实际参数类型装箱 headYaw 实现。</p>
     *
     * @param entityId 数据包实体 ID
     * @param uuid     NPC 的 UUID
     * @param location 生成位置
     * @return NMS AddEntityPacket 实例
     */
    public static Object createAddPlayerPacket(int entityId, UUID uuid, Location location) {
        return createAddEntityPacket(entityId, uuid, location, PLAYER_ENTITY_TYPE);
    }

    /**
     * 创建 TextDisplay 添加实体包（用于显示名）。
     *
     * <p>spawn 在 NPC 头顶，通过 TextDisplay 实体的 DATA_TEXT_ID 元数据显示文本。
     * 复用 {@link #createAddEntityPacket} 的构造逻辑，仅替换 EntityType。</p>
     *
     * @param entityId  TextDisplay 实体 ID
     * @param uuid      TextDisplay 的 UUID（独立于 NPC UUID）
     * @param location  生成位置（NPC 头顶偏移）
     * @return NMS AddEntityPacket 实例
     */
    public static Object createAddTextDisplayPacket(int entityId, UUID uuid, Location location) {
        return createAddEntityPacket(entityId, uuid, location, TEXT_DISPLAY_ENTITY_TYPE);
    }

    /**
     * 构造 AddEntityPacket 的共享逻辑（11 参构造器）。
     *
     * <p>扫描所有 declared constructors 找 11 参签名，按第 11 参的实际类型
     * （float 或 double）装箱 headYaw 后反射调用。其余参数顺序固定为：
     * {@code (int, UUID, double, double, double, float, float, EntityType, int, Vec3, headYaw)}。</p>
     */
    private static Object createAddEntityPacket(int entityId, UUID uuid, Location location,
                                                 Object entityType) {
        float yaw = location.getYaw();
        float pitch = location.getPitch();
        for (Constructor<?> ctor : ADD_ENTITY_PACKET_CLASS.getDeclaredConstructors()) {
            if (ctor.getParameterCount() != 11) {
                continue;
            }
            Class<?>[] paramTypes = ctor.getParameterTypes();
            // 第 11 参（headYaw）类型可能是 float（1.21.2+）或 double（1.21.0-1.21.1）
            if (paramTypes[10] != float.class && paramTypes[10] != double.class) {
                continue;
            }
            ctor.setAccessible(true);
            Object headYawArg = paramTypes[10] == float.class ? yaw : (double) yaw;
            try {
                return ctor.newInstance(entityId, uuid, location.getX(), location.getY(),
                        location.getZ(), pitch, yaw, entityType, 0, VEC3_ZERO, headYawArg);
            } catch (ReflectiveOperationException e) {
                throw new WooNPCsReflectException("Failed to construct ClientboundAddEntityPacket", e);
            }
        }
        throw new WooNPCsReflectException(
                "No 11-param constructor found in ClientboundAddEntityPacket");
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
     * 版本自适应：</p>
     * <ul>
     *   <li>1.21.2+（{@code PositionMoveRotation} 类存在）：使用 4 参公开构造器
     *       {@code (int, PositionMoveRotation, Set<Relative>, boolean)}</li>
     *   <li>1.21.0-1.21.1（{@code PositionMoveRotation} 类不存在）：通过
     *       {@code STREAM_CODEC.decode(FriendlyByteBuf)} 构造，buf 内容为
     *       {@code writeVarInt(id) + 3×writeDouble(xyz) + 2×writeByte(yaw,pitch) + writeBoolean(onGround)}，
     *       与 {@code WooHolograms/LegacyEntityPacketHelper} 风格一致</li>
     * </ul>
     *
     * @param entityId  目标实体 ID
     * @param location  目标位置
     * @param onGround  是否在地面
     * @return NMS TeleportEntityPacket 实例
     */
    public static Object createTeleportPacket(int entityId, Location location, boolean onGround) {
        // 1.21.2+：4 参公开构造器
        if (POSITION_MOVE_ROTATION_CLASS != null) {
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

        // 1.21.0-1.21.1：STREAM_CODEC.decode(FriendlyByteBuf) 路径
        Object buf = createRegistryFriendlyByteBuf();
        ReflectUtil.invokeMethod(buf, "writeVarInt", entityId);
        ReflectUtil.invokeMethod(buf, "writeDouble", location.getX());
        ReflectUtil.invokeMethod(buf, "writeDouble", location.getY());
        ReflectUtil.invokeMethod(buf, "writeDouble", location.getZ());
        ReflectUtil.invokeMethod(buf, "writeByte",
                (byte) (location.getYaw() * 256.0f / 360.0f));
        ReflectUtil.invokeMethod(buf, "writeByte",
                (byte) (location.getPitch() * 256.0f / 360.0f));
        ReflectUtil.invokeMethod(buf, "writeBoolean", onGround);
        return decodeViaStreamCodec(TELEPORT_PACKET_CLASS, buf);
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

    // ==================== 属性包 ====================

    /**
     * 创建实体缩放属性更新包。
     *
     * <p>对应 {@code ClientboundUpdateAttributesPacket}，通过 {@code minecraft:scale} 属性
     * 设置玩家型 NPC 的缩放比例。1.21+ 玩家实体的缩放只能通过 attribute 包发送，
     * metadata 中无对应索引。</p>
     *
     * <p>若当前服务端版本无 {@code Attributes.SCALE} 字段（不支持），
     * 返回 null，调用方应跳过发包。</p>
     *
     * @param entityId 目标实体 ID
     * @param scale    缩放比例（1.0 = 原始大小）
     * @return NMS UpdateAttributesPacket 实例，或 null 表示本版本不支持
     */
    public static @Nullable Object createScaleAttributePacket(int entityId, float scale) {
        if (SCALE_ATTRIBUTE_HOLDER == null) {
            return null;
        }
        Object snapshot = createAttributeSnapshot(SCALE_ATTRIBUTE_HOLDER, scale);
        return ReflectUtil.newInstance(UPDATE_ATTRIBUTES_PACKET_CLASS,
                new Class[]{int.class, List.class}, entityId, List.of(snapshot));
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

    // ==================== Team 包 ====================

    // ---- 反射缓存 ----

    private static final Method TEAM_CREATE_OR_MODIFY_METHOD =
            ReflectUtil.getMethod(TEAM_PACKET_CLASS, "createAddOrModifyPacket",
                    PLAYER_TEAM_CLASS, boolean.class);

    private static final Method TEAM_REMOVE_METHOD =
            ReflectUtil.getMethod(TEAM_PACKET_CLASS, "createRemovePacket", PLAYER_TEAM_CLASS);

    private static final Method TEAM_SET_NAME_TAG_VISIBILITY_METHOD =
            ReflectUtil.getMethod(PLAYER_TEAM_CLASS, "setNameTagVisibility", TEAM_VISIBILITY_CLASS);

    private static final Method TEAM_SET_COLLISION_RULE_METHOD =
            ReflectUtil.getMethod(PLAYER_TEAM_CLASS, "setCollisionRule", TEAM_COLLISION_RULE_CLASS);

    private static final Method TEAM_GET_PLAYERS_METHOD =
            ReflectUtil.getMethod(PLAYER_TEAM_CLASS, "getPlayers");

    /**
     * {@code setColor} 方法版本自适应缓存。
     *
     * <p>1.21.0-1.21.6：{@code setColor(ChatFormatting)}；
     * 1.21.7+：{@code setColor(Optional<TeamColor>)}。
     * 通过 {@link #resolveTeamSetColorMethod()} 在类初始化时扫描确定。</p>
     */
    private static final Method TEAM_SET_COLOR_METHOD = resolveTeamSetColorMethod();

    /** {@code Team.Visibility.ALWAYS} 枚举常量 */
    private static final Object VISIBILITY_ALWAYS =
            ReflectUtil.getFieldValue(TEAM_VISIBILITY_CLASS, "ALWAYS");

    /** {@code Team.Visibility.NEVER} 枚举常量 */
    private static final Object VISIBILITY_NEVER =
            ReflectUtil.getFieldValue(TEAM_VISIBILITY_CLASS, "NEVER");

    /** {@code Team.CollisionRule.ALWAYS} 枚举常量 */
    private static final Object COLLISION_RULE_ALWAYS =
            ReflectUtil.getFieldValue(TEAM_COLLISION_RULE_CLASS, "ALWAYS");

    /** {@code Team.CollisionRule.NEVER} 枚举常量 */
    private static final Object COLLISION_RULE_NEVER =
            ReflectUtil.getFieldValue(TEAM_COLLISION_RULE_CLASS, "NEVER");

    /**
     * 共享 Scoreboard 实例（仅用于构造 PlayerTeam）。
     *
     * <p>NMS {@code new PlayerTeam(Scoreboard, String)} 构造器要求传入 Scoreboard 参数，
     * 但实际 {@code ClientboundSetPlayerTeamPacket.createAddOrModifyPacket} 工厂方法
     * 仅读取 PlayerTeam 的属性（name、color、visibility、collisionRule、players 等），
     * 不依赖 Scoreboard 实例本身。故复用一个临时 Scoreboard 即可，
     * 不会影响服务端真实记分板状态。</p>
     */
    private static final Object SHARED_SCOREBOARD =
            ReflectUtil.newInstance(SCOREBOARD_CLASS, new Class<?>[0]);

    /**
     * Adventure {@link NamedTextColor} → NMS 颜色名（大写，对应 ChatFormatting 静态字段名）映射。
     *
     * <p>手工映射，避免依赖 {@code PaperAdventure.asVanilla} 内部 API。
     * 大写名用于：① 取 {@code ChatFormatting.<NAME>} 静态字段（1.21.0-1.21.6）；
     * ② 转小写后传 {@code TeamColor.byName(name)}（1.21.7+）。</p>
     */
    private static final Map<NamedTextColor, String> NAMED_TEXT_COLOR_TO_NAME = Map.ofEntries(
            Map.entry(NamedTextColor.BLACK, "BLACK"),
            Map.entry(NamedTextColor.DARK_BLUE, "DARK_BLUE"),
            Map.entry(NamedTextColor.DARK_GREEN, "DARK_GREEN"),
            Map.entry(NamedTextColor.DARK_AQUA, "DARK_AQUA"),
            Map.entry(NamedTextColor.DARK_RED, "DARK_RED"),
            Map.entry(NamedTextColor.DARK_PURPLE, "DARK_PURPLE"),
            Map.entry(NamedTextColor.GOLD, "GOLD"),
            Map.entry(NamedTextColor.GRAY, "GRAY"),
            Map.entry(NamedTextColor.DARK_GRAY, "DARK_GRAY"),
            Map.entry(NamedTextColor.BLUE, "BLUE"),
            Map.entry(NamedTextColor.GREEN, "GREEN"),
            Map.entry(NamedTextColor.AQUA, "AQUA"),
            Map.entry(NamedTextColor.RED, "RED"),
            Map.entry(NamedTextColor.LIGHT_PURPLE, "LIGHT_PURPLE"),
            Map.entry(NamedTextColor.YELLOW, "YELLOW"),
            Map.entry(NamedTextColor.WHITE, "WHITE"));

    // ---- 公共方法 ----

    /**
     * 构造 Team 创建包（mode=0）。
     *
     * <p>对应 {@code ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true)}。
     * 用于在客户端创建 Scoreboard Team，主要场景：隐藏 NPC 头顶 nametag、
     * 控制碰撞/推挤规则、设置发光颜色。</p>
     *
     * <p><b>collisionNever 与 pushNever 合并</b>：NMS 中 {@code Team.CollisionRule}
     * 同时控制碰撞与推挤，无独立 push 规则，故任一为 {@code true} 即设为
     * {@code CollisionRule.NEVER}，否则 {@code ALWAYS}。</p>
     *
     * <p><b>版本自适应</b>：{@code setColor} 方法签名在 1.21.7+ 变更
     * （{@code ChatFormatting} → {@code Optional<TeamColor>}），
     * 由 {@link #TEAM_SET_COLOR_METHOD} 在类初始化时反射扫描确定。</p>
     *
     * @param teamName         队伍名（不可为 null，建议 {@code woonpc_<entityId>}）
     * @param entries          队伍成员名（GameProfile username 列表，至少 1 个）
     * @param nameTagInvisible {@code true} 时 nameTagVisibility=NEVER，否则 ALWAYS
     * @param collisionNever   {@code true} 时 collisionRule=NEVER
     * @param pushNever        {@code true} 时 collisionRule=NEVER（与 collisionNever 合并判定）
     * @param color            发光色，{@code null} 表示不设置（继承默认 RESET，无发光）
     * @return NMS {@code ClientboundSetPlayerTeamPacket} 实例（mode=0）
     */
    public static Object createTeamCreatePacket(String teamName, Collection<String> entries,
                                                boolean nameTagInvisible, boolean collisionNever,
                                                boolean pushNever, @Nullable NamedTextColor color) {
        Object playerTeam = createConfiguredPlayerTeam(
                teamName, nameTagInvisible, collisionNever, pushNever, color);
        try {
            @SuppressWarnings("unchecked")
            Collection<String> players = (Collection<String>) TEAM_GET_PLAYERS_METHOD.invoke(playerTeam);
            players.addAll(entries);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to add entries to PlayerTeam: " + teamName, e);
        }
        return invokeTeamCreateOrModify(playerTeam, true);
    }

    /**
     * 构造 Team 更新包（mode=2），不传 entries。
     *
     * <p>对应 {@code ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false)}。
     * 用于在队伍已存在的情况下更新属性（nametag 可见性、碰撞规则、发光色等）。</p>
     *
     * @param teamName         队伍名
     * @param nameTagInvisible {@code true} 时 nameTagVisibility=NEVER，否则 ALWAYS
     * @param collisionNever   {@code true} 时 collisionRule=NEVER
     * @param pushNever        {@code true} 时 collisionRule=NEVER（与 collisionNever 合并判定）
     * @param color            发光色，{@code null} 表示不设置
     * @return NMS {@code ClientboundSetPlayerTeamPacket} 实例（mode=2）
     */
    public static Object createTeamUpdatePacket(String teamName, boolean nameTagInvisible,
                                                boolean collisionNever, boolean pushNever,
                                                @Nullable NamedTextColor color) {
        Object playerTeam = createConfiguredPlayerTeam(
                teamName, nameTagInvisible, collisionNever, pushNever, color);
        return invokeTeamCreateOrModify(playerTeam, false);
    }

    /**
     * 构造 Team 移除包（mode=1）。
     *
     * <p>对应 {@code ClientboundSetPlayerTeamPacket.createRemovePacket(team)}。
     * 用于在客户端移除已存在的 Scoreboard Team。仅依赖 teamName，
     * PlayerTeam 实例为占位（无属性设置）。</p>
     *
     * @param teamName 队伍名
     * @return NMS {@code ClientboundSetPlayerTeamPacket} 实例（mode=1）
     */
    public static Object createTeamRemovePacket(String teamName) {
        Object playerTeam = ReflectUtil.newInstance(PLAYER_TEAM_CLASS,
                new Class<?>[]{SCOREBOARD_CLASS, String.class}, SHARED_SCOREBOARD, teamName);
        try {
            return TEAM_REMOVE_METHOD.invoke(null, playerTeam);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException(
                    "Failed to invoke ClientboundSetPlayerTeamPacket.createRemovePacket", e);
        }
    }

    // ---- 私有辅助方法 ----

    /**
     * 构造并配置 PlayerTeam 实例（设置 nameTagVisibility / collisionRule / color）。
     * 不添加 entries（由 {@link #createTeamCreatePacket} 单独处理）。
     */
    private static Object createConfiguredPlayerTeam(String teamName, boolean nameTagInvisible,
                                                    boolean collisionNever, boolean pushNever,
                                                    @Nullable NamedTextColor color) {
        Object playerTeam = ReflectUtil.newInstance(PLAYER_TEAM_CLASS,
                new Class<?>[]{SCOREBOARD_CLASS, String.class}, SHARED_SCOREBOARD, teamName);
        try {
            TEAM_SET_NAME_TAG_VISIBILITY_METHOD.invoke(playerTeam,
                    nameTagInvisible ? VISIBILITY_NEVER : VISIBILITY_ALWAYS);
            // NMS 仅 collisionRule 一项同时控制碰撞与推挤，故任一为 true 即 NEVER
            TEAM_SET_COLLISION_RULE_METHOD.invoke(playerTeam,
                    (collisionNever || pushNever) ? COLLISION_RULE_NEVER : COLLISION_RULE_ALWAYS);
            if (color != null) {
                applyTeamColor(playerTeam, color);
            }
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to configure PlayerTeam: " + teamName, e);
        }
        return playerTeam;
    }

    /**
     * 调用 {@code createAddOrModifyPacket(team, boolean)} 静态工厂方法。
     *
     * @param playerTeam 已配置的 PlayerTeam 实例
     * @param create     {@code true}=create(mode=0)，{@code false}=update(mode=2)
     */
    private static Object invokeTeamCreateOrModify(Object playerTeam, boolean create) {
        try {
            return TEAM_CREATE_OR_MODIFY_METHOD.invoke(null, playerTeam, create);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException(
                    "Failed to invoke ClientboundSetPlayerTeamPacket.createAddOrModifyPacket", e);
        }
    }

    /**
     * 根据 setColor 方法签名版本分支调用。
     *
     * <ul>
     *   <li>1.21.0-1.21.6：{@code setColor(ChatFormatting)} —— 取 ChatFormatting 静态字段</li>
     *   <li>1.21.7+：{@code setColor(Optional<TeamColor>)} —— 包装 Optional.of(TeamColor.byName)</li>
     * </ul>
     */
    private static void applyTeamColor(Object playerTeam, NamedTextColor color) {
        Class<?> paramType = TEAM_SET_COLOR_METHOD.getParameterTypes()[0];
        try {
            if (paramType == CHAT_FORMATTING_CLASS) {
                TEAM_SET_COLOR_METHOD.invoke(playerTeam, toChatFormatting(color));
            } else {
                // 1.21.7+: setColor(Optional<TeamColor>)
                TEAM_SET_COLOR_METHOD.invoke(playerTeam, Optional.of(toTeamColor(color)));
            }
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to set PlayerTeam color: " + color, e);
        }
    }

    /**
     * 将 {@link NamedTextColor} 转换为 NMS {@code ChatFormatting}（1.21.0-1.21.6）。
     * 通过 {@link #NAMED_TEXT_COLOR_TO_NAME} 取大写名，再读 {@code ChatFormatting.<NAME>} 静态字段。
     */
    private static Object toChatFormatting(NamedTextColor color) {
        String name = NAMED_TEXT_COLOR_TO_NAME.get(color);
        if (name == null) {
            throw new WooNPCsReflectException("No ChatFormatting mapping for NamedTextColor: " + color);
        }
        return ReflectUtil.getFieldValue(CHAT_FORMATTING_CLASS, name);
    }

    /**
     * 将 {@link NamedTextColor} 转换为 NMS {@code TeamColor}（1.21.7+）。
     * 通过 {@link #NAMED_TEXT_COLOR_TO_NAME} 取大写名，转小写后调 {@code TeamColor.byName(name)}。
     */
    private static Object toTeamColor(NamedTextColor color) {
        String name = NAMED_TEXT_COLOR_TO_NAME.get(color);
        if (name == null) {
            throw new WooNPCsReflectException("No TeamColor mapping for NamedTextColor: " + color);
        }
        Method byName = ReflectUtil.getMethod(TEAM_COLOR_CLASS, "byName", String.class);
        try {
            return byName.invoke(null, name.toLowerCase(Locale.ROOT));
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to invoke TeamColor.byName", e);
        }
    }

    /**
     * 反射扫描 {@code PlayerTeam.setColor} 方法，按版本自适应。
     *
     * <p>查找顺序：</p>
     * <ol>
     *   <li>{@code setColor(ChatFormatting)} —— 1.21.0-1.21.6</li>
     *   <li>{@code setColor(Optional<TeamColor>)} —— 1.21.7+（仅当 TEAM_COLOR_CLASS 存在时尝试）</li>
     * </ol>
     *
     * <p>两者均不存在时抛 {@link WooNPCsReflectException}（理论不会发生，
     * 因为至少存在其一）。</p>
     */
    private static Method resolveTeamSetColorMethod() {
        Method m = ReflectUtil.getMethodOrNull(PLAYER_TEAM_CLASS, "setColor", CHAT_FORMATTING_CLASS);
        if (m != null) {
            return m;
        }
        if (TEAM_COLOR_CLASS != null) {
            m = ReflectUtil.getMethodOrNull(PLAYER_TEAM_CLASS, "setColor", Optional.class);
            if (m != null) {
                return m;
            }
        }
        throw new WooNPCsReflectException(
                "Cannot find PlayerTeam.setColor method (expected setColor(ChatFormatting) "
                        + "or setColor(Optional<TeamColor>))");
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

    /**
     * 将 Adventure {@link Component} 转换为 NMS Component。
     *
     * <p>通过反射调用 Paper 内置的 {@code io.papermc.paper.adventure.PaperAdventure.asVanilla(Component)}
     * 静态方法完成转换。Paper 1.21+ 全版本均暴露此 API，但为避免编译期硬依赖 Paper 内部类，
     * 此处使用反射调用。</p>
     *
     * <p>支持 Adventure {@code Component} 携带的所有样式（颜色、装饰、ClickEvent 等），
     * 适用于已通过 {@code LegacyComponentSerializer} 或 {@code MiniMessage} 解析后的 Component。</p>
     *
     * @param adventureComponent Adventure Component，不可为 null
     * @return NMS Component 实例
     */
    public static Object createComponent(Component adventureComponent) {
        try {
            Class<?> paperAdventureClass =
                    Class.forName("io.papermc.paper.adventure.PaperAdventure");
            Method asVanilla =
                    ReflectUtil.getMethod(paperAdventureClass, "asVanilla", Component.class);
            return asVanilla.invoke(null, adventureComponent);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to invoke PaperAdventure.asVanilla", e);
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
     * 创建 PlayerInfoUpdatePacket.Entry 实例。
     *
     * <p>版本自适应：扫描构造器按参数数量分支。</p>
     * <ul>
     *   <li>1.21.2+（9 参）：{@code (UUID, GameProfile, listed, latency, GameType, displayName,
     *       showHat, listOrder, chatSession)} —— 多 {@code showHat} 与 {@code listOrder} 两项</li>
     *   <li>1.21.0-1.21.1（7 参）：{@code (UUID, GameProfile, listed, latency, GameType,
     *       displayName, chatSession)} —— 无 showHat / listOrder</li>
     * </ul>
     *
     * <p>chatSession 传 null 表示无聊天会话。{@code listOrder = -1} 与 fancynpcs-v2 对齐，
     * 表示不强制排序，避免与真实玩家在 tab 列表中的相对位置产生不一致行为。</p>
     */
    private static Object createPlayerInfoEntry(UUID uuid, Object gameProfile,
                                                boolean listed, int latency,
                                                @Nullable Object displayName) {
        for (Constructor<?> ctor : PLAYER_INFO_ENTRY_CLASS.getDeclaredConstructors()) {
            int pc = ctor.getParameterCount();
            if (pc != 9 && pc != 7) {
                continue;
            }
            ctor.setAccessible(true);
            try {
                if (pc == 9) {
                    // 1.21.2+: 多 showHat、listOrder 参数
                    return ctor.newInstance(uuid, gameProfile, listed, latency,
                            GAME_TYPE_SURVIVAL, displayName, true, -1, null);
                }
                // 1.21.0-1.21.1: 7 参构造器
                return ctor.newInstance(uuid, gameProfile, listed, latency,
                        GAME_TYPE_SURVIVAL, displayName, null);
            } catch (ReflectiveOperationException e) {
                throw new WooNPCsReflectException("Failed to construct PlayerInfoUpdatePacket.Entry", e);
            }
        }
        throw new WooNPCsReflectException(
                "No 7/9-param constructor found in PlayerInfoUpdatePacket.Entry");
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

    /**
     * 构造 NMS {@code AttributeSnapshot} 实例。
     *
     * <p>1.21+ 的 AttributeSnapshot 构造器签名为 {@code (Holder<Attribute>, double, List<AttributeModifier>)}，
     * 由于不同小版本间该类可能在 record 与普通类之间切换，此处扫描 declared constructors
     * 找到 3 参数者使用，避免硬编码形参类型导致版本不兼容。</p>
     *
     * @param attributeHolder 属性 Holder（来自 {@code Attributes.XXX} 静态字段）
     * @param baseValue       基础值
     * @return AttributeSnapshot 实例
     */
    private static Object createAttributeSnapshot(Object attributeHolder, double baseValue) {
        for (Constructor<?> ctor : ATTRIBUTE_SNAPSHOT_CLASS.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 3) {
                ctor.setAccessible(true);
                try {
                    return ctor.newInstance(attributeHolder, baseValue, List.of());
                } catch (ReflectiveOperationException e) {
                    throw new WooNPCsReflectException(
                            "Failed to construct AttributeSnapshot", e);
                }
            }
        }
        throw new WooNPCsReflectException(
                "No 3-param constructor found in AttributeSnapshot");
    }

    /**
     * 安全读取静态字段：找不到时返回 null（不抛异常）。
     * 用于可选字段（如 {@code Attributes.SCALE} 在某些版本可能不存在）。
     */
    private static @Nullable Object getStaticFieldOrNull(Class<?> clazz, String fieldName) {
        try {
            return ReflectUtil.getFieldValue(clazz, fieldName);
        } catch (WooNPCsReflectException e) {
            return null;
        }
    }
}
