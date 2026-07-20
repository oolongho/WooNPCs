package com.oolongho.woonpc.api.actions;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.npc.ClickType;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * NPC 动作管理器接口。
 *
 * <p>负责动作的存储、查询、触发执行与冷却管理。每个 NPC 在每个 {@link ActionTrigger}
 * 下可绑定一组有序 {@link NpcAction}（动作链）。</p>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li><b>存储</b>：{@code Map<npcId, Map<trigger, List<action>>>} 存储动作配置</li>
 *   <li><b>触发</b>：{@link #execute} 根据 {@link ClickType} 解析 trigger、应用冷却、执行动作链</li>
 *   <li><b>冷却</b>：per-trigger per-player 冷却（key = npcId + playerId + trigger）</li>
 *   <li><b>清理</b>：NPC 删除时 {@link #clearNpc}、玩家下线时 {@link #clearPlayerCooldowns}</li>
 *   <li><b>反序列化</b>：{@link #registerActionType} 注册从配置构造动作的工厂</li>
 * </ul>
 *
 * <h2>触发流程</h2>
 * <ol>
 *   <li>优先查找具体 trigger（LEFT_CLICK / RIGHT_CLICK）</li>
 *   <li>若空，回退到 ANY_CLICK</li>
 *   <li>应用 per-trigger per-player 冷却（时长取 {@code NpcData.interactionCooldown}）</li>
 *   <li>遍历动作链执行，遇到 {@code delayTicks > 0} 用 scheduler 延迟剩余</li>
 * </ol>
 *
 * <p>本接口由 {@code ActionManagerImpl} 实现，由 {@code NpcInteractListener} 调用。
 * 外部插件可通过 {@code WooNPCsAPI.getActionManager()} 获取实例并动态注册/查询动作。</p>
 *
 * @author oolongho
 */
public interface ActionManager {

    /**
     * 设置 NPC 在某触发器下的动作列表（替换式覆盖）。
     *
     * @param npcId   NPC 唯一标识
     * @param trigger 触发器
     * @param actions 动作列表（不可变副本会被存储）
     */
    void setActions(UUID npcId, ActionTrigger trigger, List<NpcAction> actions);

    /**
     * 获取 NPC 在某触发器下的动作列表。
     *
     * @param npcId   NPC 唯一标识
     * @param trigger 触发器
     * @return 动作列表，未配置时返回 {@link List#of()}
     */
    List<NpcAction> getActions(UUID npcId, ActionTrigger trigger);

    /**
     * 清理 NPC 所有动作与冷却。
     *
     * <p>NPC 删除时调用，防止内存泄漏。</p>
     *
     * @param npcId NPC 唯一标识
     */
    void clearNpc(UUID npcId);

    /**
     * 触发 NPC 交互：查找匹配的动作并执行，应用冷却。
     *
     * <p>必须在主线程调用（Bukkit API 非线程安全）。</p>
     *
     * @param player    交互的玩家
     * @param npc       被交互的 NPC
     * @param clickType 玩家点击类型
     */
    void execute(Player player, Npc npc, ClickType clickType);

    /**
     * 清理玩家对所有 NPC 的冷却。
     *
     * <p>玩家下线时调用，防止冷却数据累积。</p>
     *
     * @param playerId 玩家唯一标识
     */
    void clearPlayerCooldowns(UUID playerId);

    /**
     * 注册内置动作类型工厂（用于从配置反序列化）。
     *
     * @param typeId  动作类型 ID（如 {@code "console_command"}）
     * @param factory 工厂函数：{@code Map<参数名, 参数值> -> NpcAction}
     */
    void registerActionType(String typeId, Function<Map<String, String>, NpcAction> factory);

    /**
     * 序列化所有 NPC 的动作为可持久化结构。
     *
     * <p>结构：{@code Map<npcIdStr, Map<triggerName, List<Map<参数名, 参数值>>>>}
     * 每个 NpcAction 序列化为 {@link NpcAction#serialize()} 输出 + 一个 {@code "type"} 字段。</p>
     *
     * @return 可序列化的全量动作状态，空时返回空 Map
     */
    Map<String, Object> serializeAll();

    /**
     * 从 {@link #serializeAll} 的输出结构加载全部动作。
     *
     * <p>加载时清空现有内存状态后全量重建。未注册的 typeId 对应的动作会被跳过并记录 warning。</p>
     *
     * @param data 序列化数据，可为 null 或空（清空状态）
     */
    void loadAll(Map<String, Object> data);
}
