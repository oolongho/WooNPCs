package com.oolongho.woonpc.manager;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.event.NpcCreateEvent;
import com.oolongho.woonpc.event.NpcDeleteEvent;
import com.oolongho.woonpc.nms.NmsAdapter;
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
     * NMS 适配器引用。
     *
     * <p>构造时注入，用于在插件启动阶段提前验证 NMS 就绪（若当前服务端版本不支持，
     * {@code NmsAdapterFactory.createAdapter} 会抛异常，导致 NpcManagerImpl 构造失败，
     * 插件 onEnable 中止）。NpcController 内部也会自行获取 adapter 实例（无状态，多实例安全）。
     * 本字段保留供未来批量操作或健康检查使用。</p>
     */
    private final NmsAdapter adapter;

    /**
     * 创建 NpcManager 实例。
     *
     * @param adapter NMS 适配器，不可为 null（由 {@code NmsAdapterFactory.createAdapter} 获取）
     */
    public NpcManagerImpl(NmsAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter cannot be null");
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
        // 注册成功，触发 NpcCreateEvent（不可取消，监听器可通过 manager 查询到 NPC）
        Bukkit.getPluginManager().callEvent(new NpcCreateEvent(npc));
        // spawn（Task 5 阶段 targetViewers 为空，spawn 为空操作；Task 7 Tracker 接入后生效）
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
        // 触发 NpcDeleteEvent（不可取消）
        Bukkit.getPluginManager().callEvent(new NpcDeleteEvent(npc));
        return true;
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
