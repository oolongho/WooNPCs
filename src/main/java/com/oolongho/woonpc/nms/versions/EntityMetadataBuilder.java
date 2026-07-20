package com.oolongho.woonpc.nms.versions;

import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.hologram.DisplayNameRenderer;
import com.oolongho.woonpc.nms.dto.MetadataEntry;
import com.oolongho.woonpc.nms.util.PacketFactory;
import com.oolongho.woonpc.nms.util.ReflectUtil;
import com.oolongho.woonpc.npc.NpcEffect;
import com.oolongho.woonpc.npc.NpcPose;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;

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
 * 不通过 metadata。{@link NpcData#scale()} 由 {@code NmsAdapter.updateScale}
 * 通过 {@code ClientboundUpdateAttributesPacket} 单独发送，本类不处理 scale。</p>
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

    /** NMS Pose 类（反射加载） */
    private static final Class<?> POSE_CLASS =
            ReflectUtil.getClass("net.minecraft.world.entity.Pose");

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
     *   <li>索引 2：自定义名 Optional&lt;Component&gt;（始终非空，displayName 为 null 时回落到 name）</li>
     *   <li>索引 3：自定义名是否可见 boolean（始终 true，GameProfile 名牌由 Team 包隐藏）</li>
     *   <li>索引 5：无重力 boolean（始终 true）</li>
     *   <li>索引 6：Pose 枚举（由 NpcPose 转换）</li>
     *   <li>索引 16：玩家皮肤层 byte（启用所有层）</li>
     * </ol>
     *
     * <p><b>CustomName 渲染</b>：实体 metadata 的 CustomName 与 TextDisplay 头顶显示名共用
     * {@link DisplayNameRenderer#parseDisplayName(String)} 解析逻辑（支持 {@code &} 颜色代码与
     * MiniMessage 标签）。虽然 GameProfile 玩家名牌已被 Team 包（nameTagVisibility=NEVER）隐藏，
     * CustomName 不会在头顶显示，但保留与 TextDisplay 一致的颜色解析以维持数据一致性。</p>
     *
     * @param data NPC 数据快照，不可为 null
     * @return 元数据项列表（非空，至少包含状态位与皮肤层）
     */
    public static List<MetadataEntry> build(NpcData data) {
        List<MetadataEntry> entries = new ArrayList<>(6);

        // 状态位：NpcEffect 合并为 byte（FIRE/SNEAKING/SPRINTING/SWIMMING/INVISIBLE/GLOWING）
        byte flags = NpcEffect.merge(data.effects());
        entries.add(new MetadataEntry(IDX_SHARED_FLAGS, SER_BYTE, flags));

        // 自定义名 + 可见性：displayName 为 null 或空时回落到 name()，始终非空
        String displayName = data.displayName();
        String customNameText = (displayName != null && !displayName.isEmpty()) ? displayName : data.name();
        Object component = createComponent(DisplayNameRenderer.parseDisplayName(customNameText));
        entries.add(new MetadataEntry(IDX_CUSTOM_NAME, SER_OPTIONAL_COMPONENT, Optional.of(component)));
        entries.add(new MetadataEntry(IDX_CUSTOM_NAME_VISIBLE, SER_BOOLEAN, Boolean.TRUE));

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
     * 将 Adventure {@link Component} 转换为 NMS Component。
     *
     * <p>委托 {@link PacketFactory#createComponent(Component)} 通过 Paper 的
     * {@code PaperAdventure.asVanilla(Component)} 反射调用完成转换，
     * 支持 Adventure Component 携带的所有样式（颜色、装饰等）。</p>
     *
     * @param adventureComponent Adventure Component
     * @return NMS Component 实例
     */
    private static Object createComponent(Component adventureComponent) {
        return PacketFactory.createComponent(adventureComponent);
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
