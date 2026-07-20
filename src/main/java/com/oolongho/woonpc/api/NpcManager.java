package com.oolongho.woonpc.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * NPC 管理器（公共 API）。
 *
 * <p>定义 NPC 的注册、查询、创建、删除契约。实现类（{@code NpcManagerImpl}）使用
 * {@link java.util.concurrent.ConcurrentHashMap} 存储，保证并发查询安全。
 * 所有写操作（create / remove）应在主线程执行，避免与事件触发、包发送产生竞态。</p>
 *
 * <h2>唯一性约束</h2>
 * <ul>
 *   <li>每个 NPC 拥有唯一的 {@link UUID}（由 {@link NpcData#id()} 确定）</li>
 *   <li>每个 NPC 拥有唯一的 {@code name}（由 {@link NpcData#name()} 确定）</li>
 *   <li>{@link #create} 时校验 name 唯一性，重复则抛 {@link IllegalStateException}</li>
 * </ul>
 *
 * <h2>生命周期与事件</h2>
 * <ul>
 *   <li>{@link #create}：构造 NpcImpl → 触发 {@code NpcCreateEvent}（不可取消）→ 注册 → spawn</li>
 *   <li>{@link #remove}：despawn → 触发 {@code NpcDeleteEvent}（不可取消）→ 注销</li>
 * </ul>
 *
 * <p>本接口为公共 API，外部插件通过 {@code WooNPCsAPI.getNpcManager()} 获取实例。
 * 不加 {@code @ApiStatus.Internal} 注解。</p>
 *
 * @author oolongho
 */
public interface NpcManager {

    /**
     * 获取所有已注册的 NPC。
     *
     * @return 不可修改的 NPC 集合视图
     */
    Collection<Npc> getAll();

    /**
     * 按 UUID 查询 NPC。
     *
     * @param id NPC 的 UUID，不可为 null
     * @return 包含对应 NPC 的 Optional，不存在返回 {@link Optional#empty()}
     */
    Optional<Npc> getById(UUID id);

    /**
     * 按名称查询 NPC。
     *
     * <p>名称查找区分大小写。由于 name 唯一，最多返回一个 NPC。</p>
     *
     * @param name NPC 名称，不可为 null
     * @return 包含对应 NPC 的 Optional，不存在返回 {@link Optional#empty()}
     */
    Optional<Npc> getByName(String name);

    /**
     * 按客户端实体 ID 查询 NPC（用于交互数据包匹配）。
     *
     * <p>{@code NpcInteractListener} 在收到 {@code ServerboundInteractPacket} 时，
     * 通过包内的 {@code entityId} 调用本方法定位对应 NPC。entityId 在 NPC 创建时
     * 由 {@code NpcController} 分配，全局唯一。</p>
     *
     * @param entityId 客户端实体 ID
     * @return 包含对应 NPC 的 Optional，不存在返回 {@link Optional#empty()}
     */
    Optional<Npc> getByEntityId(int entityId);

    /**
     * 创建并注册一个新 NPC。
     *
     * <p>流程：校验 name 唯一性 → 构造 NpcImpl（分配 EntityId + NpcController）→
     * 触发 {@code NpcCreateEvent} → 注册到内部映射 → 调用 {@link Npc#spawn()}。</p>
     *
     * @param data NPC 初始数据快照，不可为 null
     * @return 新创建的 NPC
     * @throws NullPointerException     当 data 为 null
     * @throws IllegalStateException    当 name 已存在
     * @throws com.oolongho.woonpc.nms.util.WooNPCsException 当 NmsAdapter 未就绪
     */
    Npc create(NpcData data);

    /**
     * 按 UUID 移除 NPC。
     *
     * <p>流程：despawn → 触发 {@code NpcDeleteEvent} → 从内部映射移除。</p>
     *
     * @param id NPC 的 UUID，不可为 null
     * @return 移除成功返回 true，NPC 不存在返回 false
     */
    boolean remove(UUID id);

    /**
     * 判断指定 UUID 的 NPC 是否存在。
     *
     * @param id NPC 的 UUID，不可为 null
     * @return 存在返回 true
     */
    boolean contains(UUID id);

    /**
     * 判断指定名称的 NPC 是否存在。
     *
     * @param name NPC 名称，不可为 null
     * @return 存在返回 true
     */
    boolean containsByName(String name);

    /**
     * 获取已注册的 NPC 数量。
     *
     * @return NPC 数量
     */
    int size();
}
