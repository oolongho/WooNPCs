package com.oolongho.woonpc.manager;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.event.NpcCreateEvent;
import com.oolongho.woonpc.event.NpcDeleteEvent;
import com.oolongho.woonpc.npc.NpcImpl;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link NpcManager} 的默认实现。
 *
 * <p>使用 {@link ConcurrentHashMap} 存储 NPC，主键为 {@link UUID}。
 * 另维护一个 name → UUID 的索引用于 O(1) 名称查询与唯一性校验。</p>
 *
 * <h2>线程安全</h2>
 * <ul>
 *   <li>查询方法（get/contains/size/getAll）无锁，依赖 ConcurrentHashMap 的线程安全语义</li>
 *   <li>写操作（create/remove）应在主线程执行，避免与事件触发、包发送产生竞态</li>
 *   <li>create 的唯一性校验 + 注册通过 {@link ConcurrentHashMap#putIfAbsent} 原子完成</li>
 * </ul>
 *
 * <h2>生命周期流程</h2>
 * <ul>
 *   <li>create：构造 NpcImpl → 原子注册（name 预占 + id 注册）→ 触发 NpcCreateEvent → spawn</li>
 *   <li>remove：原子取回 NPC → 强制销毁客户端实体 → 触发 NpcDeleteEvent → 清理索引</li>
 * </ul>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcManagerImpl implements NpcManager {

    /** NPC 主存储：UUID → Npc */
    private final Map<UUID, Npc> npcs = new ConcurrentHashMap<>();

    /** 名称索引：name → UUID，用于唯一性校验与 O(1) 名称查询 */
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();

    /**
     * 客户端实体 ID 索引：entityId → UUID，用于交互包匹配 O(1) 查询。
     *
     * <p>由 {@code NpcInteractListener} 在收到 {@code ServerboundInteractPacket} 时使用，
     * 将包内的 entityId 转换为对应 NPC。create 时注册，remove 时注销。</p>
     */
    private final Map<Integer, UUID> entityIdIndex = new ConcurrentHashMap<>();

    /**
     * 创建 NpcManager 实例。
     */
    public NpcManagerImpl() {
    }

    @Override
    public Collection<Npc> getAll() {
        return Collections.unmodifiableCollection(npcs.values());
    }

    @Override
    public Optional<Npc> getById(UUID id) {
        Objects.requireNonNull(id, "id cannot be null");
        return Optional.ofNullable(npcs.get(id));
    }

    @Override
    public Optional<Npc> getByName(String name) {
        Objects.requireNonNull(name, "name cannot be null");
        UUID id = nameIndex.get(name);
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(npcs.get(id));
    }

    @Override
    public Optional<Npc> getByEntityId(int entityId) {
        UUID id = entityIdIndex.get(entityId);
        if (id == null) {
            return Optional.empty();
        }
        // 可能已 remove 但索引清理边缘情况，使用 getById 二次校验
        return getById(id);
    }

    @Override
    public Npc create(NpcData data) {
        Objects.requireNonNull(data, "data cannot be null");
        // 构造 NpcImpl（内部分配 EntityId + NpcController）
        NpcImpl npc = new NpcImpl(data, this);
        // 原子注册：先预占 name，再注册 id（失败回滚 name）
        if (nameIndex.putIfAbsent(npc.getName(), npc.getId()) != null) {
            throw new IllegalStateException("NPC with name '" + npc.getName() + "' already exists");
        }
        if (npcs.putIfAbsent(npc.getId(), npc) != null) {
            nameIndex.remove(npc.getName(), npc.getId());
            throw new IllegalStateException("NPC with id " + npc.getId() + " already exists (concurrent create)");
        }
        // 注册 entityId 索引（用于 NpcInteractListener 反向查询）
        entityIdIndex.put(npc.getEntityId(), npc.getId());
        // 注册成功，触发 NpcCreateEvent（不可取消，监听器可通过 manager 查询到 NPC）
        Bukkit.getPluginManager().callEvent(new NpcCreateEvent(npc));
        // spawn：对 targetViewers 中所有玩家发送 spawn 包
        // （VisibilityTracker 在玩家进入可见距离时调用 showTo 维护 targetViewers）
        npc.spawn();
        return npc;
    }

    @Override
    public boolean remove(UUID id) {
        Objects.requireNonNull(id, "id cannot be null");
        // 原子取回（解决并发 remove 事件重复触发）
        Npc npc = npcs.remove(id);
        if (npc == null) {
            return false;
        }
        // 强制销毁客户端实体（remove 不可取消，不触发 NpcDespawnEvent）
        ((NpcImpl) npc).despawnSilent();
        // 清理名称索引（2-arg remove 防御同名误删）
        nameIndex.remove(npc.getName(), npc.getId());
        // 清理 entityId 索引（O(1)，npc 引用已持有）
        entityIdIndex.remove(npc.getEntityId());
        // 触发 NpcDeleteEvent（不可取消）
        Bukkit.getPluginManager().callEvent(new NpcDeleteEvent(npc));
        return true;
    }

    /**
     * 重命名 NPC（内部 API，由 NpcImpl.modify 在 field=NAME 时调用）。
     *
     * <p>原子操作：先 putIfAbsent(newName, id) 抢占新名，再 remove(oldName, id) 清理旧名。
     * 若新名已被其他 NPC 占用，抛 IllegalStateException 并回滚（putIfAbsent 返回非 null）。</p>
     *
     * @param id      NPC 的 UUID
     * @param newName 新名称
     * @throws IllegalStateException 当新名已被占用
     */
    @ApiStatus.Internal
    public void rename(UUID id, String newName) {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(newName, "newName cannot be null");
        // 先查找当前 NPC 的旧名
        Npc npc = npcs.get(id);
        if (npc == null) {
            throw new IllegalStateException("NPC " + id + " not found");
        }
        String oldName = npc.getName();
        if (oldName.equals(newName)) {
            return; // 未变更
        }
        // 抢占新名
        UUID prevOwner = nameIndex.putIfAbsent(newName, id);
        if (prevOwner != null && !prevOwner.equals(id)) {
            throw new IllegalStateException("Name '" + newName + "' already used by another NPC");
        }
        // 清理旧名（2-arg remove 防御同名误删）
        nameIndex.remove(oldName, id);
    }

    @Override
    public boolean contains(UUID id) {
        Objects.requireNonNull(id, "id cannot be null");
        return npcs.containsKey(id);
    }

    @Override
    public boolean containsByName(String name) {
        Objects.requireNonNull(name, "name cannot be null");
        return nameIndex.containsKey(name);
    }

    @Override
    public int size() {
        return npcs.size();
    }
}
