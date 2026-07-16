package com.oolongho.woonpc.npc;

import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.hologram.DisplayNameRenderer;
import com.oolongho.woonpc.nms.NmsAdapter;
import com.oolongho.woonpc.nms.NmsAdapterFactory;
import com.oolongho.woonpc.nms.dto.NpcEquipmentData;
import com.oolongho.woonpc.nms.dto.NpcMetadataData;
import com.oolongho.woonpc.nms.dto.NpcSpawnData;
import com.oolongho.woonpc.nms.versions.EntityIdGenerator;
import com.oolongho.woonpc.nms.versions.EntityMetadataBuilder;
import com.oolongho.woonpc.util.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 包发送控制器（内部实现）。
 *
 * <p>持有 {@code entityId} 与可见玩家集合，将 {@link NpcData}（业务层数据快照）
 * 转换为协议层 DTO 并委托 {@link NmsAdapter} 发送数据包。</p>
 *
 * <h2>架构定位</h2>
 * <pre>
 *   Npc (public API) ──持有──&gt; NpcController (internal) ──委托──&gt; NmsAdapter (version-specific)
 *                                       │
 *                                       └──持有──&gt; DisplayNameRenderer (TextDisplay 头顶显示名)
 * </pre>
 *
 * <p>NpcController <b>不持有</b> NpcData，而是在每次方法调用时接收 NpcData 作为参数。
 * NpcData 由 {@link com.oolongho.woonpc.api.Npc} 持有，NpcController 是 Npc 的内部委托对象。</p>
 *
 * <h2>显示名渲染</h2>
 * <p>头顶 TextDisplay 由独立的 {@link DisplayNameRenderer} 管理，包括 spawn/despawn/文本更新/位置跟随。
 * NpcController 在 {@link #spawn}/{@link #despawn}/{@link #updateLocation}/{@link #updateDisplayName}
 * 节点委托 renderer 完成显示名同步，避免 TextDisplay 客户端实体泄漏与位置不同步。</p>
 *
 * <h2>线程安全</h2>
 * <ul>
 *   <li>所有方法必须在主线程调用（PlayerConnection 非线程安全）</li>
 *   <li>{@link #visiblePlayers} 使用 {@link ConcurrentHashMap#newKeySet()} 支持并发读写</li>
 *   <li>遍历可见玩家时通过 {@link Bukkit#getPlayer(UUID)} 解析，离线玩家自动跳过</li>
 * </ul>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcController {

    /** 数据包实体 ID（由 {@link EntityIdGenerator} 分配，生命周期内不变） */
    private final int entityId;

    /** NPC 的 UUID（用于 PlayerInfo 与实体绑定） */
    private final UUID uuid;

    /** GameProfile 玩家名（影响 tab 显示与默认皮肤，建议占位名） */
    private final String username;

    /** NMS 适配器（版本特定，构造时一次性获取） */
    private final NmsAdapter adapter;

    /** 可见玩家集合（UUID），并发安全 */
    private final Set<UUID> visiblePlayers;

    /** 头顶显示名渲染器（独立 TextDisplay 实体的全生命周期管理） */
    private final DisplayNameRenderer displayNameRenderer;

    /**
     * 构造控制器。
     *
     * <p>构造时从 {@link EntityIdGenerator} 分配 entityId，并从
     * {@link NmsAdapterFactory} 获取对应版本的 {@link NmsAdapter}。
     * {@link DisplayNameRenderer} 同步构造（其内部预分配 displayEntityId）。</p>
     *
     * @param uuid     NPC 的 UUID，不可为 null
     * @param username GameProfile 玩家名，不可为 null 或空白
     * @throws com.oolongho.woonpc.nms.util.WooNPCsException 当 NmsAdapter 实现缺失或不支持当前版本
     */
    public NpcController(UUID uuid, String username) {
        this.entityId = EntityIdGenerator.nextEntityId();
        this.uuid = Objects.requireNonNull(uuid, "uuid cannot be null");
        this.username = Objects.requireNonNull(username, "username cannot be null");
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        this.adapter = NmsAdapterFactory.createAdapter(VersionUtil.getServerVersion());
        this.visiblePlayers = ConcurrentHashMap.newKeySet();
        this.displayNameRenderer = new DisplayNameRenderer();
    }

    // ==================== 生命周期：生成 / 销毁 ====================

    /**
     * 向单个玩家发送 spawn 包，并将其加入可见集合。
     *
     * <p>将 {@link NpcData} 转换为 {@link NpcSpawnData}，委托
     * {@link NmsAdapter#spawnPlayer} 发包。皮肤通过 {@link com.oolongho.woonpc.skin.SkinData#toNpcTexture()}
     * 转换：默认皮肤传 null 触发服务端 Steve。</p>
     *
     * <p>spawn 完成后委托 {@link DisplayNameRenderer#showTo} 发送头顶 TextDisplay
     * （若 {@code data.displayName() != null}）。</p>
     *
     * @param player 目标玩家
     * @param data   NPC 数据快照
     * @throws NullPointerException 当 player 或 data 为 null
     */
    public void spawn(Player player, NpcData data) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        if (!player.isOnline()) {
            return;
        }
        if (!visiblePlayers.add(player.getUniqueId())) {
            return; // 已可见，幂等跳过
        }

        NpcSpawnData spawnData = new NpcSpawnData(
                entityId,
                uuid,
                username,
                data.location(),
                data.skin().toNpcTexture(),
                data.displayName(),
                data.showInTab()
        );
        adapter.spawnPlayer(player, spawnData);
        // 同步头顶 TextDisplay 显示名（独立实体，由 DisplayNameRenderer 管理）
        displayNameRenderer.showTo(player, data);
    }

    /**
     * 向单个玩家发送 despawn 包，并将其移出可见集合。
     *
     * <p>同时委托 {@link DisplayNameRenderer#hideFrom} 销毁该玩家客户端的 TextDisplay 实体。
     * 若玩家不在可见集合中，直接返回（幂等）。</p>
     *
     * @param player 目标玩家
     * @throws NullPointerException 当 player 为 null
     */
    public void despawn(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        if (!visiblePlayers.remove(player.getUniqueId())) {
            return;
        }
        adapter.despawn(player, entityId, uuid);
        displayNameRenderer.hideFrom(player);
    }

    /**
     * 向所有可见玩家发送 despawn 包，并清空可见集合。
     *
     * <p>同时委托 {@link DisplayNameRenderer#hideFromAll} 销毁所有玩家的 TextDisplay 实体。</p>
     */
    public void despawnAll() {
        for (UUID playerId : visiblePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                adapter.despawn(player, entityId, uuid);
                displayNameRenderer.hideFrom(player);
            }
        }
        visiblePlayers.clear();
    }

    // ==================== 增量更新 ====================

    /**
     * 更新元数据（状态位、自定义名、pose、scale 等）。
     *
     * <p>通过 {@link EntityMetadataBuilder} 从 {@link NpcData} 构建 {@code MetadataEntry} 列表，
     * 封装为 {@link NpcMetadataData} 后委托 {@link NmsAdapter#updateMetadata} 发包。</p>
     *
     * @param data NPC 数据快照
     * @throws NullPointerException 当 data 为 null
     */
    public void updateMetadata(NpcData data) {
        Objects.requireNonNull(data, "data cannot be null");
        var entries = EntityMetadataBuilder.build(data);
        var metadataData = new NpcMetadataData(entityId, entries);
        for (UUID playerId : visiblePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                adapter.updateMetadata(player, metadataData);
            }
        }
    }

    /**
     * 更新位置（瞬移）到所有可见玩家。
     *
     * <p>同时委托 {@link DisplayNameRenderer#updateLocation} 同步 TextDisplay 位置
     * （TextDisplay 是独立实体，不会跟随 NPC 的 teleport 包移动）。</p>
     *
     * @param location 新位置
     * @throws NullPointerException 当 location 为 null
     */
    public void updateLocation(Location location) {
        Objects.requireNonNull(location, "location cannot be null");
        for (UUID playerId : visiblePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                adapter.updateLocation(player, entityId, location, true);
                displayNameRenderer.updateLocation(player, location);
            }
        }
    }

    /**
     * 更新装备到所有可见玩家。
     *
     * @param equipment 槽位到物品的映射，空映射时直接返回
     * @throws NullPointerException 当 equipment 为 null
     */
    public void updateEquipment(Map<NpcEquipmentSlot, ItemStack> equipment) {
        Objects.requireNonNull(equipment, "equipment cannot be null");
        if (equipment.isEmpty()) {
            return;
        }
        Map<EquipmentSlot, ItemStack> bukkitEquipment = new EnumMap<>(EquipmentSlot.class);
        for (Map.Entry<NpcEquipmentSlot, ItemStack> entry : equipment.entrySet()) {
            bukkitEquipment.put(entry.getKey().toBukkitSlot(), entry.getValue());
        }
        NpcEquipmentData data = new NpcEquipmentData(entityId, bukkitEquipment);
        for (UUID playerId : visiblePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                adapter.updateEquipment(player, data);
            }
        }
    }

    /**
     * 更新头部朝向到所有可见玩家。
     *
     * @param yaw   偏航角（度）
     * @param pitch 俯仰角（度）
     */
    public void updateHeadRotation(float yaw, float pitch) {
        for (UUID playerId : visiblePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                adapter.updateHeadRotation(player, entityId, yaw, pitch);
            }
        }
    }

    /**
     * 更新头顶显示名文本。
     *
     * <p>委托 {@link DisplayNameRenderer#showTo} 完成显示名同步。{@code showTo} 自适应三种场景：</p>
     * <ul>
     *   <li>{@code displayName == null}：调 {@link DisplayNameRenderer#hideFrom} 卸载已激活的 TextDisplay</li>
     *   <li>已激活：转 {@link DisplayNameRenderer#updateText} 仅发送 SetEntityData 更新文本</li>
     *   <li>未激活且 {@code displayName != null}：发送 AddEntity + SetEntityData 创建 TextDisplay</li>
     * </ul>
     *
     * <p>使用 {@code showTo} 而非 {@code updateText} 是为了修复 displayName 从 null → 非 null 切换时，
     * 玩家已在 visiblePlayers 但未在 activeViewers 中（首次 spawn 时 displayName 为 null 走 hideFrom 早退分支），
     * 导致 updateText 因 activeViewers 不含该玩家而早退、TextDisplay 永不创建的问题。</p>
     *
     * @param data NPC 数据快照
     * @throws NullPointerException 当 data 为 null
     */
    public void updateDisplayName(NpcData data) {
        Objects.requireNonNull(data, "data cannot be null");
        for (UUID playerId : visiblePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                displayNameRenderer.showTo(player, data);
            }
        }
    }

    // ==================== 移动 ====================

    /**
     * 移动 NPC 到新位置。
     *
     * <p><b>Phase 1</b>：瞬移，等价于 {@link #updateLocation}。
     * <b>Phase 2</b>：将扩展为平滑路径跟随。</p>
     *
     * @param location 目标位置
     */
    public void moveTo(Location location) {
        updateLocation(location);
    }

    /**
     * 沿路径移动 NPC（Phase 2 预留接口）。
     *
     * @param path 路径点列表
     * @throws UnsupportedOperationException Phase 2 功能尚未实现
     */
    public void followPath(List<Location> path) {
        throw new UnsupportedOperationException("followPath: Phase 2 feature, not implemented yet");
    }

    // ==================== 状态查询 ====================

    /**
     * 获取数据包实体 ID。
     *
     * @return entityId
     */
    public int getEntityId() {
        return entityId;
    }

    /**
     * 获取 NPC 的 UUID。
     *
     * @return UUID
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * 获取 GameProfile 玩家名。
     *
     * @return username
     */
    public String getUsername() {
        return username;
    }

    /**
     * 获取可见玩家集合的不可修改视图。
     *
     * @return 可见玩家 UUID 集合
     */
    public Set<UUID> getVisiblePlayers() {
        return Collections.unmodifiableSet(visiblePlayers);
    }

    /**
     * 判断玩家是否在可见集合中。
     *
     * @param player 玩家
     * @return 可见返回 true
     */
    public boolean isVisible(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        return visiblePlayers.contains(player.getUniqueId());
    }
}
