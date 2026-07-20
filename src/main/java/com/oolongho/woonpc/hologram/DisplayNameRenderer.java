package com.oolongho.woonpc.hologram;

import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.nms.dto.MetadataEntry;
import com.oolongho.woonpc.nms.util.PacketFactory;
import com.oolongho.woonpc.nms.versions.EntityIdGenerator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 头顶显示名渲染器（内置单行 TextDisplay 实现）。
 *
 * <p>每个 {@link com.oolongho.woonpc.npc.NpcController} 持有一个本类实例，
 * 接管 NPC 头顶 TextDisplay 实体的完整生命周期：
 * 生成（spawn）、销毁（despawn）、文本更新、位置跟随。</p>
 *
 * <h2>设计动机</h2>
 * <p>TextDisplay 是独立的客户端实体（独立 entityId），不会跟随 NPC 实体的 teleport 包移动，
 * 也不会被 NPC 的 RemoveEntities 包销毁。若由 {@code NpcController} 直接在 spawn 中发送
 * TextDisplay spawn 包，会缺失 despawn / 位置跟随 / 文本更新三个环节，导致：</p>
 * <ul>
 *   <li>NPC despawn 后 TextDisplay 客户端实体残留（资源泄漏）</li>
 *   <li>NPC 移动后 TextDisplay 仍停留在原位置（显示名不跟随）</li>
 *   <li>NPC 修改 displayName 后客户端文本不更新（仅在首次 spawn 时同步）</li>
 * </ul>
 *
 * <p>本类封装上述完整生命周期，由 {@code NpcController} 在 {@code spawn/despawn/updateLocation/
 * updateDisplayName} 节点委托调用。所有方法必须在主线程调用（包发送非线程安全）。</p>
 *
 * <h2>渲染策略</h2>
 * <ul>
 *   <li>{@code NpcData.displayName() == null} 时，回落到 {@code NpcData.name()} 渲染（始终显示文本）</li>
 *   <li>文本经 {@link #parseDisplayName(String)} 解析，支持 {@code &} 颜色代码与 MiniMessage 标签</li>
 *   <li>首次激活（{@link #showTo}）发送 AddEntity(TextDisplay) + SetEntityData(text)</li>
 *   <li>后续文本变化（{@link #updateText}）仅发送 SetEntityData 更新 DATA_TEXT_ID（index 23）</li>
 *   <li>位置变化（{@link #updateLocation}）发送 TeleportEntity 更新 TextDisplay 位置</li>
 *   <li>卸载（{@link #hideFrom}）发送 RemoveEntities(displayEntityId)</li>
 * </ul>
 *
 * <p>{@code activeViewers} 记录已激活的玩家 UUID，确保 hideFrom / updateText / updateLocation
 * 的幂等性，避免对未激活玩家发送无效包。</p>
 *
 * <h2>版本兼容</h2>
 * <p>本类直接调用 {@link PacketFactory} 的低级包构建方法，绕过 {@code NmsAdapter} 接口。
 * 因为 TextDisplay 的协议格式在 1.21.2+ 全版本一致（metadata index 23 不变），
 * 且 {@link PacketFactory#toDataValue} 已自适应 1.21.4- / 1.21.5+ 的 DataValue 构造差异。
 * 若未来版本协议变动，应在 {@code NmsAdapter} 接口中新增方法并改为委托调用。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class DisplayNameRenderer {

    /** TextDisplay 实体 DataWatcher 中 TEXT 字段的索引（1.21+ 固定为 23） */
    private static final int TEXT_DISPLAY_TEXT_INDEX = 23;

    /** Component 序列化器 ID（DataSerializer.REGISTRY_COMPONENT = 4） */
    private static final int SER_COMPONENT = 4;

    /** TextDisplay 实体相对 NPC 实体脚下的 Y 轴偏移（NPC 头顶上方 2.0 方块） */
    private static final double DISPLAY_NAME_OFFSET_Y = 2.0;

    /** {@code &} 颜色代码解析器（如 {@code &a} → green） */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacy('&');

    /** MiniMessage 标签解析器（如 {@code <green>} → green） */
    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** TextDisplay 实体 ID（构造时预分配，生命周期内不变） */
    private final int displayEntityId;

    /** TextDisplay 实体的 UUID（独立于 NPC 的 UUID） */
    private final UUID displayUuid;

    /** 已激活的玩家集合（已收到 TextDisplay spawn 包的玩家 UUID） */
    private final Set<UUID> activeViewers;

    /** 构造渲染器，分配 displayEntityId。 */
    public DisplayNameRenderer() {
        this.displayEntityId = EntityIdGenerator.nextEntityId();
        this.displayUuid = UUID.randomUUID();
        this.activeViewers = ConcurrentHashMap.newKeySet();
    }

    // ==================== 生命周期 ====================

    /**
     * 让指定玩家看到 NPC 头顶显示名。
     *
     * <p>渲染文本规则：{@code data.displayName() != null} 时使用 displayName，
     * 否则回落到 {@code data.name()}（始终显示文本，不主动隐藏）。</p>
     *
     * <p>首次激活时发送：</p>
     * <ol>
     *   <li>{@code ClientboundAddEntityPacket}（生成 TextDisplay 实体，位置 = NPC 位置 + Y+2.0）</li>
     *   <li>{@code ClientboundSetEntityDataPacket}（设置 DATA_TEXT_ID = Component）</li>
     * </ol>
     *
     * @param player 目标玩家
     * @param data   NPC 数据快照
     */
    public void showTo(Player player, NpcData data) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        if (!activeViewers.add(player.getUniqueId())) {
            // 已激活，转 updateText 保持幂等
            updateText(player, data);
            return;
        }
        String displayText = data.displayName() != null ? data.displayName() : data.name();
        Location displayLoc = data.location().clone().add(0, DISPLAY_NAME_OFFSET_Y, 0);
        Object spawnPacket = PacketFactory.createAddTextDisplayPacket(displayEntityId, displayUuid, displayLoc);
        Object textComponent = PacketFactory.createComponent(parseDisplayName(displayText));
        List<MetadataEntry> entries = List.of(
                new MetadataEntry(TEXT_DISPLAY_TEXT_INDEX, SER_COMPONENT, textComponent));
        Object metadataPacket = PacketFactory.createMetadataPacket(displayEntityId, entries);
        PacketFactory.sendPackets(player, List.of(spawnPacket, metadataPacket));
    }

    /**
     * 让指定玩家不再看到 NPC 头顶显示名。
     *
     * <p>幂等：若玩家未激活，直接返回。激活时发送
     * {@code ClientboundRemoveEntitiesPacket(displayEntityId)} 销毁客户端 TextDisplay 实体。</p>
     *
     * @param player 目标玩家
     */
    public void hideFrom(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        if (!activeViewers.remove(player.getUniqueId())) {
            return;
        }
        Object removePacket = PacketFactory.createRemoveEntitiesPacket(displayEntityId);
        PacketFactory.sendPacket(player, removePacket);
    }

    /**
     * 更新 TextDisplay 的文本内容。
     *
     * <p>仅对已激活的玩家发送 {@code SetEntityData} 更新 DATA_TEXT_ID。
     * 渲染文本规则同 {@link #showTo}：displayName 非 null 时用 displayName，否则回落到 name()。</p>
     *
     * @param player 目标玩家
     * @param data   NPC 数据快照
     */
    public void updateText(Player player, NpcData data) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        if (!activeViewers.contains(player.getUniqueId())) {
            return;
        }
        String displayText = data.displayName() != null ? data.displayName() : data.name();
        Object textComponent = PacketFactory.createComponent(parseDisplayName(displayText));
        List<MetadataEntry> entries = List.of(
                new MetadataEntry(TEXT_DISPLAY_TEXT_INDEX, SER_COMPONENT, textComponent));
        Object metadataPacket = PacketFactory.createMetadataPacket(displayEntityId, entries);
        PacketFactory.sendPacket(player, metadataPacket);
    }

    /**
     * 同步更新 TextDisplay 位置（NPC 移动后调用）。
     *
     * <p>对已激活的玩家发送 {@code TeleportEntityPacket}，将 TextDisplay 传送至
     * NPC 新位置 + Y+2.0 处。TextDisplay 是独立实体，不会跟随 NPC 的 teleport 包移动，
     * 必须显式发送。</p>
     *
     * @param player      目标玩家
     * @param npcLocation NPC 当前位置
     */
    public void updateLocation(Player player, Location npcLocation) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(npcLocation, "npcLocation cannot be null");
        if (!activeViewers.contains(player.getUniqueId())) {
            return;
        }
        Location displayLoc = npcLocation.clone().add(0, DISPLAY_NAME_OFFSET_Y, 0);
        Object teleportPacket = PacketFactory.createTeleportPacket(displayEntityId, displayLoc, true);
        PacketFactory.sendPacket(player, teleportPacket);
    }

    /**
     * 卸载所有玩家的 TextDisplay（NPC 销毁时调用）。
     *
     * <p>遍历 {@link #activeViewers}，对每个在线玩家发送 RemoveEntities 包，
     * 然后清空集合。离线玩家自动跳过（其连接已断开，无需发包）。</p>
     */
    public void hideFromAll() {
        for (UUID playerId : Set.copyOf(activeViewers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                Object removePacket = PacketFactory.createRemoveEntitiesPacket(displayEntityId);
                PacketFactory.sendPacket(player, removePacket);
            }
        }
        activeViewers.clear();
    }

    // ==================== 文本解析 ====================

    /**
     * 解析显示名文本为 Adventure {@link Component}，支持 {@code &} 颜色代码与 MiniMessage 标签。
     *
     * <p>解析顺序：先用 {@link LegacyComponentSerializer#legacy(char)} 解析 {@code &} 颜色代码
     * （如 {@code &a} → green），再将结果序列化为 MiniMessage 字符串后用 {@link MiniMessage#miniMessage()}
     * 反序列化，使 MiniMessage 标签（如 {@code <green>}）也生效。</p>
     *
     * <p>典型用例：</p>
     * <ul>
     *   <li>{@code "&atest"} → 绿色文本 "test"</li>
     *   <li>{@code "<green>test"} → 绿色文本 "test"</li>
     *   <li>{@code "&a<green>test"} → 绿色 + 绿色（合并）的 "test"</li>
     *   <li>{@code "&atest<bold>bold"} → 绿色 "test" + 绿色加粗 "bold"</li>
     * </ul>
     *
     * @param text 原始文本（含 {@code &} 颜色代码或 MiniMessage 标签），不可为 null
     * @return 解析后的 Adventure Component
     */
    public static Component parseDisplayName(@NotNull String text) {
        // 1. LegacyComponentSerializer 解析 & 颜色代码，< > 等字符作为字面量保留在 Component 中
        Component legacyComponent = LEGACY.deserialize(text);
        // 2. 序列化为 MiniMessage 字符串（颜色变为 <green> 等标签，字面 < > 转义保留）
        String miniStr = MM.serialize(legacyComponent);
        // 3. MiniMessage 反序列化，使原 <tag> 与步骤 1 转换后的颜色标签同时生效
        return MM.deserialize(miniStr);
    }

    // ==================== 状态查询 ====================

    /**
     * 获取 TextDisplay 实体 ID。
     *
     * @return displayEntityId
     */
    public int getDisplayEntityId() {
        return displayEntityId;
    }

    /**
     * 判断指定玩家是否已激活（已收到 TextDisplay spawn 包）。
     *
     * @param player 玩家
     * @return 已激活返回 true
     */
    public boolean isActive(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        return activeViewers.contains(player.getUniqueId());
    }
}
