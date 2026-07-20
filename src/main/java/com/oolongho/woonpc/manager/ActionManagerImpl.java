package com.oolongho.woonpc.manager;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.action.ConsoleCommandAction;
import com.oolongho.woonpc.action.MessageAction;
import com.oolongho.woonpc.action.NeedPermissionAction;
import com.oolongho.woonpc.action.PlaySoundAction;
import com.oolongho.woonpc.action.PlayerCommandAction;
import com.oolongho.woonpc.action.PlayerCommandAsOpAction;
import com.oolongho.woonpc.action.SendToServerAction;
import com.oolongho.woonpc.action.WaitAction;
import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.actions.ActionContext;
import com.oolongho.woonpc.api.actions.ActionManager;
import com.oolongho.woonpc.api.actions.ActionTrigger;
import com.oolongho.woonpc.api.actions.NpcAction;
import com.oolongho.woonpc.event.NpcDeleteEvent;
import com.oolongho.woonpc.npc.ClickType;
import com.oolongho.woonpc.util.Scheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * {@link ActionManager} 的具体实现。
 *
 * <p>负责动作的存储、查询、触发执行、冷却管理与反序列化工厂注册。</p>
 *
 * <h2>数据结构</h2>
 * <ul>
 *   <li>{@code actionsByNpc}：{@code Map<npcId, Map<trigger, List<action>>>}
 *       — 每个 NPC 在每个 trigger 下绑定的有序动作列表</li>
 *   <li>{@code cooldowns}：{@code Map<CoolDownKey, Long expiryMillis>}
 *       — per-trigger per-player 冷却，key = (npcId, playerId, trigger)，value = 到期时间戳</li>
 *   <li>{@code pendingTasks}：{@code Map<npcId, Set<TaskHandle>>}
 *       — 由 {@link NpcAction#delayTicks()} 调度的延迟任务集合，{@link #clearNpc} 时统一取消</li>
 *   <li>{@code factories}：{@code Map<typeId, factory>}
 *       — 从配置 Map 反序列化动作的工厂</li>
 * </ul>
 *
 * <h2>触发流程</h2>
 * <ol>
 *   <li>查找 NPC 在具体 trigger（LEFT_CLICK/RIGHT_CLICK）下的动作列表</li>
 *   <li>若空，回退到 ANY_CLICK</li>
 *   <li>检查冷却：若未过期则直接返回（无操作）；过期则记录新冷却时间（now + interactionCooldown）</li>
 *   <li>构造 {@link ActionContext} 并执行动作链</li>
 * </ol>
 *
 * <h2>动作链执行</h2>
 * <p>遍历动作列表，对每个动作：</p>
 * <ul>
 *   <li>检查 {@link NpcAction#delayTicks()}：若 {@code > 0}，通过
 *       {@code Bukkit.getScheduler().runTaskLater} 延迟剩余 tick 后继续</li>
 *   <li>调用 {@link NpcAction#execute}：返回 false 时中止整链</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <ul>
 *   <li>所有存储使用 {@link ConcurrentHashMap}，支持并发读写</li>
 *   <li>{@link #execute} 必须在主线程调用（Bukkit API 非线程安全）</li>
 *   <li>延迟动作通过 scheduler 在主线程回调 {@link #executeChain}</li>
 * </ul>
 *
 * <p>本类不实现 Listener；由 {@code NpcInteractListener} 监听
 * {@code ServerboundInteractPacket} 后调用 {@link #execute}。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class ActionManagerImpl implements ActionManager, Listener {

    private final WooNPCs plugin;
    private final Scheduler scheduler;

    /** NPC 动作存储：npcId -> (trigger -> actions) */
    private final Map<UUID, Map<ActionTrigger, List<NpcAction>>> actionsByNpc = new ConcurrentHashMap<>();

    /** 冷却存储：(npcId, playerId, trigger) -> 到期时间戳（毫秒） */
    private final Map<CoolDownKey, Long> cooldowns = new ConcurrentHashMap<>();

    /** 延迟任务存储：npcId -> 已调度的 TaskHandle 集合，clearNpc 时统一取消 */
    private final Map<UUID, Set<Scheduler.TaskHandle>> pendingTasks = new ConcurrentHashMap<>();

    /** 反序列化工厂：typeId -> factory */
    private final Map<String, Function<Map<String, String>, NpcAction>> factories = new ConcurrentHashMap<>();

    /** 冷却键（npcId + playerId + trigger 唯一确定一条冷却记录） */
    private record CoolDownKey(UUID npcId, UUID playerId, ActionTrigger trigger) {
    }

    /**
     * 构造 ActionManager 实现。
     *
     * <p>构造时注册 8 种内置动作的反序列化工厂。</p>
     *
     * @param plugin 插件实例，不可为 null
     */
    public ActionManagerImpl(WooNPCs plugin, Scheduler scheduler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        registerBuiltinFactories();
    }

    @Override
    public void setActions(UUID npcId, ActionTrigger trigger, List<NpcAction> actions) {
        Objects.requireNonNull(npcId, "npcId cannot be null");
        Objects.requireNonNull(trigger, "trigger cannot be null");
        Objects.requireNonNull(actions, "actions cannot be null");
        actionsByNpc.computeIfAbsent(npcId, k -> new ConcurrentHashMap<>()).put(trigger, List.copyOf(actions));
    }

    @Override
    public List<NpcAction> getActions(UUID npcId, ActionTrigger trigger) {
        Objects.requireNonNull(npcId, "npcId cannot be null");
        Objects.requireNonNull(trigger, "trigger cannot be null");
        Map<ActionTrigger, List<NpcAction>> triggerMap = actionsByNpc.get(npcId);
        if (triggerMap == null) {
            return List.of();
        }
        return triggerMap.getOrDefault(trigger, List.of());
    }

    @Override
    public void clearNpc(UUID npcId) {
        Objects.requireNonNull(npcId, "npcId cannot be null");
        actionsByNpc.remove(npcId);
        cooldowns.keySet().removeIf(k -> k.npcId.equals(npcId));
        // 取消所有由 WaitAction 调度的延迟任务，防止 NPC 删除后副作用 action 继续执行
        Set<Scheduler.TaskHandle> tasks = pendingTasks.remove(npcId);
        if (tasks != null) {
            tasks.forEach(Scheduler.TaskHandle::cancel);
        }
    }

    /**
     * NPC 删除事件监听：自动清理该 NPC 的所有动作、冷却与延迟任务。
     *
     * <p>采用 MONITOR 优先级（事件不可取消），与 VisibilityTracker/LookTracker 模式一致。
     * 防御外部插件直接调用 {@code NpcManager.remove} 跳过 {@link #clearNpc} 的边界场景。
     * {@link #clearNpc} 幂等，重复调用安全。</p>
     *
     * @param event NPC 删除事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onNpcDelete(NpcDeleteEvent event) {
        clearNpc(event.getNpc().getId());
    }

    @Override
    public void execute(Player player, Npc npc, ClickType clickType) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(npc, "npc cannot be null");
        Objects.requireNonNull(clickType, "clickType cannot be null");

        UUID npcId = npc.getId();
        Map<ActionTrigger, List<NpcAction>> triggerMap = actionsByNpc.get(npcId);
        if (triggerMap == null || triggerMap.isEmpty()) {
            return;
        }

        // 优先具体 trigger，回退 ANY_CLICK
        ActionTrigger trigger = resolveTrigger(clickType);
        List<NpcAction> actions = triggerMap.get(trigger);
        if (actions == null || actions.isEmpty()) {
            actions = triggerMap.get(ActionTrigger.ANY_CLICK);
            trigger = ActionTrigger.ANY_CLICK;
            if (actions == null || actions.isEmpty()) {
                return;
            }
        }

        // 冷却检查（per-trigger per-player，冷却时长取 NpcData.interactionCooldown）
        CoolDownKey key = new CoolDownKey(npcId, player.getUniqueId(), trigger);
        long now = System.currentTimeMillis();
        Long expiry = cooldowns.get(key);
        if (expiry != null && expiry > now) {
            return;
        }
        long cd = npc.getData().interactionCooldown();
        if (cd > 0) {
            cooldowns.put(key, now + cd);
        }

        // 执行动作链
        ActionContext context = new ActionContext(player, npc, clickType, plugin);
        executeChain(actions, 0, context);
    }

    /**
     * 递归执行动作链。
     *
     * <p>从 {@code index} 开始遍历，遇到 {@link NpcAction#delayTicks()} > 0 时，
     * 通过 {@link org.bukkit.scheduler.BukkitScheduler#runTaskLater} 延迟指定 tick 后
     * 调用本方法继续执行 {@code index + 1} 起的部分。</p>
     *
     * <h3>安全防护</h3>
     * <ul>
     *   <li>入口校验玩家在线：延迟期间玩家下线则中止整链（防止下线后副作用 action 执行）</li>
     *   <li>延迟任务登记到 {@link #pendingTasks}：NPC 删除时 {@link #clearNpc} 统一取消</li>
     *   <li>任务执行完从集合移除：避免长期积累已完成 task 引用</li>
     * </ul>
     *
     * @param actions 动作列表
     * @param index   当前执行起点
     * @param context 上下文（同一个交互内共享）
     */
    private void executeChain(List<NpcAction> actions, int index, ActionContext context) {
        // 玩家下线则中止整链（防止延迟期间玩家下线后继续执行副作用 action）
        if (!context.player().isOnline()) {
            return;
        }
        for (int i = index; i < actions.size(); i++) {
            NpcAction action = actions.get(i);
            int delay = action.delayTicks();
            if (delay > 0) {
                int next = i + 1;
                UUID npcId = context.npc().getId();
                // holder 数组解决 lambda 自引用：task 需在回调内从 pendingTasks 移除自己
                final Scheduler.TaskHandle[] holder = new Scheduler.TaskHandle[1];
                // 绑定玩家 region：Folia 上后续动作链中的 player.performCommand / Bukkit API 需在玩家 region
                holder[0] = scheduler.runAtEntityLater(context.player(), () -> {
                    try {
                        executeChain(actions, next, context);
                    } finally {
                        Set<Scheduler.TaskHandle> set = pendingTasks.get(npcId);
                        if (set != null) {
                            set.remove(holder[0]);
                        }
                    }
                }, delay);
                pendingTasks.computeIfAbsent(npcId, k -> ConcurrentHashMap.newKeySet()).add(holder[0]);
                return;
            }
            if (!action.execute(context)) {
                return;
            }
        }
    }

    /**
     * 将 {@link ClickType} 解析为具体的 {@link ActionTrigger}。
     *
     * <p>SHIFT_CLICK 解析为 {@link ActionTrigger#ANY_CLICK}（左/右皆触发）。
     * 注意：此处解析结果仅用于第一次查找具体 trigger，若该 trigger 为空，
     * 仍会回退到 {@link ActionTrigger#ANY_CLICK}（见 {@link #execute}）。</p>
     *
     * @param clickType 玩家点击类型
     * @return 对应的具体触发器
     */
    private ActionTrigger resolveTrigger(ClickType clickType) {
        return switch (clickType) {
            case LEFT_CLICK -> ActionTrigger.LEFT_CLICK;
            case RIGHT_CLICK -> ActionTrigger.RIGHT_CLICK;
            case SHIFT_CLICK -> ActionTrigger.ANY_CLICK;
        };
    }

    @Override
    public void clearPlayerCooldowns(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId cannot be null");
        cooldowns.keySet().removeIf(k -> k.playerId.equals(playerId));
    }

    @Override
    public void registerActionType(String typeId, Function<Map<String, String>, NpcAction> factory) {
        Objects.requireNonNull(typeId, "typeId cannot be null");
        Objects.requireNonNull(factory, "factory cannot be null");
        factories.put(typeId, factory);
    }

    @Override
    public Map<String, Object> serializeAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<ActionTrigger, List<NpcAction>>> npcEntry : actionsByNpc.entrySet()) {
            String npcIdStr = npcEntry.getKey().toString();
            Map<String, Object> triggerMap = new LinkedHashMap<>();
            for (Map.Entry<ActionTrigger, List<NpcAction>> triggerEntry : npcEntry.getValue().entrySet()) {
                List<Map<String, String>> actionList = new ArrayList<>(triggerEntry.getValue().size());
                for (NpcAction action : triggerEntry.getValue()) {
                    Map<String, String> actionMap = new LinkedHashMap<>(action.serialize());
                    actionMap.put("type", action.typeId());
                    actionList.add(actionMap);
                }
                triggerMap.put(triggerEntry.getKey().name(), actionList);
            }
            result.put(npcIdStr, triggerMap);
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void loadAll(Map<String, Object> data) {
        // 清空现有状态
        actionsByNpc.clear();
        cooldowns.clear();
        // 取消所有由 WaitAction 调度的延迟任务，避免 reload 后旧动作链继续执行 executeChain
        // （finally 块虽会跳过 pendingTasks.get(npcId)==null 的移除，但 executeChain 仍会跑完旧 actions 列表）
        for (Set<Scheduler.TaskHandle> tasks : pendingTasks.values()) {
            tasks.forEach(Scheduler.TaskHandle::cancel);
        }
        pendingTasks.clear();

        if (data == null || data.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> npcEntry : data.entrySet()) {
            UUID npcId;
            try {
                npcId = UUID.fromString(npcEntry.getKey());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("加载动作失败，无效 NPC UUID: " + npcEntry.getKey());
                continue;
            }
            Object triggerMapRaw = npcEntry.getValue();
            if (!(triggerMapRaw instanceof Map)) {
                continue;
            }
            Map<ActionTrigger, List<NpcAction>> triggerMap = new ConcurrentHashMap<>();
            for (Map.Entry<String, Object> triggerEntry : ((Map<String, Object>) triggerMapRaw).entrySet()) {
                ActionTrigger trigger;
                try {
                    trigger = ActionTrigger.valueOf(triggerEntry.getKey());
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("加载 NPC " + npcId + " 动作失败，未知 trigger: " + triggerEntry.getKey());
                    continue;
                }
                Object actionListRaw = triggerEntry.getValue();
                if (!(actionListRaw instanceof List)) {
                    continue;
                }
                List<NpcAction> actions = new ArrayList<>();
                for (Object actionRaw : (List<?>) actionListRaw) {
                    if (!(actionRaw instanceof Map)) {
                        continue;
                    }
                    Map<String, String> actionMap = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> am : ((Map<String, Object>) actionRaw).entrySet()) {
                        actionMap.put(am.getKey(), String.valueOf(am.getValue()));
                    }
                    String typeId = actionMap.remove("type");
                    if (typeId == null) {
                        plugin.getLogger().warning("加载 NPC " + npcId + " 动作失败，缺少 type 字段");
                        continue;
                    }
                    Function<Map<String, String>, NpcAction> factory = factories.get(typeId);
                    if (factory == null) {
                        plugin.getLogger().warning("加载 NPC " + npcId + " 动作失败，未知 type: " + typeId);
                        continue;
                    }
                    try {
                        actions.add(factory.apply(actionMap));
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("加载 NPC " + npcId + " 动作 " + typeId + " 失败: " + e.getMessage());
                    }
                }
                if (!actions.isEmpty()) {
                    triggerMap.put(trigger, List.copyOf(actions));
                }
            }
            if (!triggerMap.isEmpty()) {
                actionsByNpc.put(npcId, triggerMap);
            }
        }
    }

    /**
     * 注册 8 种内置动作的反序列化工厂。
     *
     * <p>注册后，外部可通过 {@link #registerActionType} 覆盖或新增类型。</p>
     */
    private void registerBuiltinFactories() {
        registerActionType("console_command", a -> new ConsoleCommandAction(a.getOrDefault("command", "")));
        registerActionType("message", a -> new MessageAction(a.getOrDefault("message", "")));
        registerActionType("play_sound", a -> new PlaySoundAction(
                a.getOrDefault("sound", "BLOCK_NOTE_BLOCK_HAT"),
                parseFloatSafe(a.getOrDefault("volume", "1.0"), 1.0f),
                parseFloatSafe(a.getOrDefault("pitch", "1.0"), 1.0f)));
        registerActionType("player_command", a -> new PlayerCommandAction(a.getOrDefault("command", "")));
        registerActionType("player_command_as_op", a -> new PlayerCommandAsOpAction(a.getOrDefault("command", "")));
        registerActionType("wait", a -> new WaitAction(parseIntSafe(a.getOrDefault("ticks", "20"), 20)));
        registerActionType("need_permission", a -> new NeedPermissionAction(a.getOrDefault("permission", "")));
        registerActionType("send_to_server", a -> new SendToServerAction(a.getOrDefault("server", "")));
    }

    /** 安全解析 float：格式错误时回退到默认值（防止配置错误导致整组 actions 加载失败） */
    private static float parseFloatSafe(String s, float def) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 安全解析 int：格式错误时回退到默认值 */
    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
