package com.oolongho.woonpc.storage;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcData;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.UUID;

/**
 * NPC 持久化存储接口。
 *
 * <p>定义 NPC 数据的保存、删除、批量落盘与加载契约。实现类（如 {@link YamlNpcStorage}）
 * 负责具体的序列化格式与文件布局，{@code NpcManager} 在创建 / 修改 / 删除 NPC 时调用本接口。</p>
 *
 * <h2>线程约定</h2>
 * <ul>
 *   <li>{@link #save} 与 {@link #delete}：可由任意线程调用，实现需保证线程安全（基于 {@code ConcurrentHashMap}）</li>
 *   <li>{@link #saveAll} 与 {@link #loadAll}：必须在主线程调用（涉及文件 IO 与 Bukkit API，{@code World} 查询等）</li>
 * </ul>
 *
 * <h2>持久化语义</h2>
 * <ul>
 *   <li>{@link #save} 仅更新内存快照，不立即写文件；落盘推迟到 {@link #saveAll}</li>
 *   <li>{@link #loadAll} 返回的 {@link NpcData} 列表由 {@code NpcManager} 重建为 {@link Npc} 实例，
 *       加载失败的 NPC 会被跳过并记录警告，不中止整体加载流程</li>
 *   <li>异步皮肤重获取不在本层实现，由 {@code NpcManager} 在装配阶段判断 skin 有效性后调 {@code SkinManager}</li>
 * </ul>
 *
 * <p>本接口为内部 API，外部不应直接依赖；用户通过 {@code NpcManager} 间接持久化。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public interface NpcStorage {

    /**
     * 保存单个 NPC（增量更新内存 + 标记 dirty，不立即落盘）。
     *
     * <p>由 {@code NpcManager} 在 NPC 创建 / 字段修改后调用。实际写文件推迟到 {@link #saveAll}。</p>
     *
     * @param npc NPC 实例，不可为 null
     */
    void save(Npc npc);

    /**
     * 删除单个 NPC。
     *
     * <p>从内存移除，下次 {@link #saveAll} 时不会写入文件（全量重写）。</p>
     *
     * @param npcId NPC 的 UUID，不可为 null
     */
    void delete(UUID npcId);

    /**
     * 保存全部 NPC 到文件。
     *
     * <p>遍历内存中所有 NPC 数据，按当前布局（单文件或分目录）写入 YAML 文件。
     * 必须在主线程调用（{@code YamlConfiguration.save} 非线程安全）。</p>
     */
    void saveAll();

    /**
     * 加载全部 NPC 数据。
     *
     * <p>从文件读取所有持久化的 NPC 数据，校验并返回 {@link NpcData} 列表，
     * 由 {@code NpcManager} 重建为 {@link Npc} 实例。加载失败的 NPC 会被跳过并记录警告。
     * 必须在主线程调用。</p>
     *
     * @return 加载成功的 NPC 数据列表（可能为空，不会为 null）
     */
    List<NpcData> loadAll();
}
