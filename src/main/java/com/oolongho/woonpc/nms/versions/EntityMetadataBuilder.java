package com.oolongho.woonpc.nms.versions;

import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.nms.dto.MetadataEntry;
import com.oolongho.woonpc.nms.util.ReflectUtil;
import com.oolongho.woonpc.nms.util.WooNPCsReflectException;
import com.oolongho.woonpc.npc.NpcEffect;
import com.oolongho.woonpc.npc.NpcPose;
import org.jetbrains.annotations.ApiStatus;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 实体元数据构建器。
 *
 * <p>将 {@link NpcData}（业务层数据快照）转换为版本无关的 {@link MetadataEntry} 列表，
 * 供 {@code NmsAdapter} 实现构造 {@code ClientboundSetEntityDataPacket} 发包。</p>
 *
 * <h2>DataWatcher 索引映射（1.21+ 协议）</h2>
 * <ul>
 *   <li>0: byte 共享标志位（FIRE/SNEAKING/SPRINTING/SWIMMING/INVISIBLE/GLOWING）</li>
 *   <li>2: Optional&lt;Component&gt; 自定义名</li>
 *   <li>3: boolean 自定义名是否可见</li>
 *   <li>5: boolean 无重力（NPC 不应下落）</li>
 *   <li>6: Pose 姿势枚举</li>
 *   <li>16: byte 玩家皮肤层（Player 专属，DATA_PLAYER_MODE_CUSTOMISATION）</li>
 * </ul>
 *
 * <h2>序列化器 ID（1.21.4 协议）</h2>
 * <p>1.21.5+ 不再使用 serializerId（DataValue 内部仅含 id 与 value），
 * 但 MetadataEntry 保留该字段供 1.21.4- 构造 DataValue 时使用。</p>
 * <ul>
 *   <li>0: byte</li>
 *   <li>1: int</li>
 *   <li>5: Optional&lt;Component&gt;</li>
 *   <li>7: boolean</li>
 *   <li>18: Pose</li>
 * </ul>
 *
 * <h2>scale 处理</h2>
 * <p>1.21+ 玩家实体的缩放通过 attribute（{@code minecraft:scale}）包发送，
 * 不通过 metadata。{@link NpcData#scale()} 由 {@code NmsAdapter} 实现单独处理
 * （Task 4 暂不实现 attribute 包，留待后续）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class EntityMetadataBuilder {

    // ==================== 序列化器 ID（1.21.4 协议） ====================
    /** byte 序列化器 ID */
    private static final int SER_BYTE = 0;
    /** int 序列化器 ID */
    private static final int SER_INT = 1;
    /** Optional<Component> 序列化器 ID */
    private static final int SER_OPTIONAL_COMPONENT = 5;
    /** boolean 序列化器 ID */
    private static final int SER_BOOLEAN = 7;
    /** Pose 序列化器 ID */
    private static final int SER_POSE = 18;

    // ==================== DataWatcher 索引 ====================
    /** 共享标志位（FIRE/SNEAKING/GLOWING 等） */
    private static final int IDX_SHARED_FLAGS = 0;
    /** 自定义名 Optional<Component> */
    private static final int IDX_CUSTOM_NAME = 2;
    /** 自定义名是否可见 */
    private static final int IDX_CUSTOM_NAME_VISIBLE = 3;
    /** 无重力 */
    private static final int IDX_NO_GRAVITY = 5;
    /** Pose 姿势 */
    private static final int IDX_POSE = 6;
    /** 玩家皮肤层（Player 专属） */
    private static final int IDX_PLAYER_SKIN_PARTS = 16;

    /** 启用所有皮肤层：披风+上衣+左右袖+左右裤腿+帽子 */
    private static final byte SKIN_PARTS_ALL = 0x01 | 0x02 | 0x04 | 0x08 | 0x10 | 0x20 | 0x40;

    /** NMS Component 类（反射加载，1.21+ 类名稳定） */
    private static final Class<?> COMPONENT_CLASS =
            ReflectUtil.getClass("net.minecraft.network.chat.Component");

    /** NMS Pose 类（反射加载） */
    private static final Class<?> POSE_CLASS =
            ReflectUtil.getClass("net.minecraft.world.entity.Pose");

    /** Component.literal(String) 静态方法（缓存） */
    private static final Method COMPONENT_LITERAL =
            ReflectUtil.getMethod(COMPONENT_CLASS, "literal", String.class);

    private EntityMetadataBuilder() {
        throw new IllegalAccessError("Utility class");
    }

    /**
     * 从 {@link NpcData} 构建元数据项列表。
     *
     * <p>构建顺序遵循 DataWatcher 索引升序，便于客户端按序应用。
     * 包含以下条目：</p>
     * <ol>
     *   <li>索引 0：状态位 byte（NpcEffect 合并）</li>
     *   <li>索引 2：自定义名 Optional&lt;Component&gt;（null/空时为 Optional.empty()）</li>
     *   <li>索引 3：自定义名是否可见 boolean</li>
     *   <li>索引 5：无重力 boolean（始终 true）</li>
     *   <li>索引 6：Pose 枚举（由 NpcPose 转换）</li>
     *   <li>索引 16：玩家皮肤层 byte（启用所有层）</li>
     * </ol>
     *
     * @param data NPC 数据快照，不可为 null
     * @return 元数据项列表（非空，至少包含状态位与皮肤层）
     */
    public static List<MetadataEntry> build(NpcData data) {
        List<MetadataEntry> entries = new ArrayList<>(6);

        // 状态位：NpcEffect 合并为 byte（FIRE/SNEAKING/SPRINTING/SWIMMING/INVISIBLE/GLOWING）
        byte flags = NpcEffect.merge(data.effects());
        entries.add(new MetadataEntry(IDX_SHARED_FLAGS, SER_BYTE, flags));

        // 自定义名 + 可见性
        String displayName = data.displayName();
        if (displayName != null && !displayName.isEmpty()) {
            Object component = createComponent(displayName);
            entries.add(new MetadataEntry(IDX_CUSTOM_NAME, SER_OPTIONAL_COMPONENT, Optional.of(component)));
            entries.add(new MetadataEntry(IDX_CUSTOM_NAME_VISIBLE, SER_BOOLEAN, Boolean.TRUE));
        } else {
            entries.add(new MetadataEntry(IDX_CUSTOM_NAME, SER_OPTIONAL_COMPONENT, Optional.empty()));
            entries.add(new MetadataEntry(IDX_CUSTOM_NAME_VISIBLE, SER_BOOLEAN, Boolean.FALSE));
        }

        // 无重力：NPC 不应受重力影响下落
        entries.add(new MetadataEntry(IDX_NO_GRAVITY, SER_BOOLEAN, Boolean.TRUE));

        // 姿势
        Object pose = createPose(data.pose());
        entries.add(new MetadataEntry(IDX_POSE, SER_POSE, pose));

        // 玩家皮肤层：启用所有层，否则皮肤第二层（披风/帽子等）不显示
        entries.add(new MetadataEntry(IDX_PLAYER_SKIN_PARTS, SER_BYTE, SKIN_PARTS_ALL));

        return entries;
    }

    /**
     * 通过反射调用 {@code Component.literal(String)} 创建 NMS Component。
     *
     * <p>1.21+ NMS Component 类名与方法签名稳定，可直接反射调用。
     * 失败时抛 {@link WooNPCsReflectException} 表明 NMS 反射异常。</p>
     *
     * @param text 文本内容
     * @return NMS Component 实例
     */
    private static Object createComponent(String text) {
        try {
            return COMPONENT_LITERAL.invoke(null, text);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to invoke Component.literal(String)", e);
        }
    }

    /**
     * 将 {@link NpcPose} 转换为 NMS {@code Pose} 枚举值。
     *
     * <p>{@link NpcPose} 的枚举名与 NMS {@code Pose} 的枚举名一致
     * （均为大写带下划线，如 {@code STANDING}、{@code FALL_FLYING}），
     * 可直接通过 {@code Enum.valueOf} 转换。</p>
     *
     * @param pose NPC 姿势枚举
     * @return NMS Pose 枚举值
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object createPose(NpcPose pose) {
        return Enum.valueOf((Class<Enum>) POSE_CLASS, pose.name());
    }
}
