package com.oolongho.woonpc.listener;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.event.NpcInteractEvent;
import com.oolongho.woonpc.nms.util.ReflectUtil;
import com.oolongho.woonpc.nms.util.WooNPCsReflectException;
import com.oolongho.woonpc.npc.ClickType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 交互数据包监听器。
 *
 * <p>通过向玩家 Netty pipeline 注入 {@link ChannelDuplexHandler}，在 Netty I/O 线程
 * 拦截 {@code ServerboundInteractPacket}（客户端 UseEntity），反射提取 entityId 与
 * action 类型，调度到主线程触发 {@link NpcInteractEvent}。</p>
 *
 * <h2>反射链路（无 paperweight，全部反射）</h2>
 * <pre>
 *   Player (CraftPlayer)
 *     └─ getHandle() → ServerPlayer
 *          └─ connection : ServerGamePacketListenerImpl
 *               └─ (parent ServerCommonPacketListenerImpl) connection : Connection
 *                    └─ channel : Channel (Netty)
 * </pre>
 *
 * <h2>线程模型</h2>
 * <ul>
 *   <li>Netty I/O 线程：仅反射读字段 + 调度主线程任务，不调用 Bukkit API</li>
 *   <li>主线程：调用 {@link NpcManager#getByEntityId}、{@link Npc#interact}、触发事件</li>
 *   <li>pipeline 操作（addBefore/remove）必须在 EventLoop 中执行，避免与 I/O 竞态</li>
 * </ul>
 *
 * <h2>幂等性</h2>
 * <p>{@link #register} 可重复调用（在线玩家重复注入由 {@link #playerChannels} 跟踪防御）。
 * {@link #unregister} 可重复调用（map 为空时直接返回）。</p>
 *
 * <h2>风险声明</h2>
 * <p>反射字段名 {@code entityId} / {@code action} / {@code connection} / {@code channel}
 * 基于 Mojang mapping 推测。若服务端版本映射变化导致字段名不一致，
 * {@link ReflectUtil} 会抛 {@link WooNPCsReflectException}，
 * 本监听器 catch 后记录 warning 并降级（不处理该次交互，不中断后续包处理）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcInteractListener implements Listener {

    /** 注入到 pipeline 的 handler 名称，全局唯一 */
    private static final String HANDLER_NAME = "woonpc_interact";

    /** 目标 pipeline 中已有的 handler 名称，注入点位于其前 */
    private static final String INJECT_BEFORE = "packet_handler";

    /**
     * 交互包类。静态加载：若服务端无此类（理论不会发生）则为 null，
     * 此时 {@link #handlePacket} 直接跳过，不影响其他包处理。
     */
    private static final Class<?> INTERACT_PACKET_CLASS;

    static {
        Class<?> clazz;
        try {
            clazz = Class.forName("net.minecraft.network.protocol.game.ServerboundInteractPacket");
        } catch (ClassNotFoundException e) {
            clazz = null;
        }
        INTERACT_PACKET_CLASS = clazz;
    }

    private final WooNPCs plugin;
    private final NpcManager npcManager;

    /** 已注入玩家的 Channel 跟踪表，避免重复注入 + 支持 unregister 时批量清理 */
    private final Map<Player, Channel> playerChannels = new ConcurrentHashMap<>();

    /**
     * 构造交互监听器。
     *
     * @param plugin     插件实例
     * @param npcManager NPC 管理器（用于按 entityId 查询 NPC）
     */
    public NpcInteractListener(WooNPCs plugin, NpcManager npcManager) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager cannot be null");
    }

    // ==================== 生命周期 ====================

    /**
     * 注册监听器：注入所有在线玩家 + 注册 Listener 监听 PlayerJoin/Quit。
     *
     * <p>幂等：重复调用不会重复注入已注入的玩家。</p>
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            inject(player);
        }
    }

    /**
     * 注销监听器：移除所有玩家的 handler + 注销 Listener。
     *
     * <p>幂等：未注册时调用安全。</p>
     */
    public void unregister() {
        for (Player player : new ArrayList<>(playerChannels.keySet())) {
            uninject(player);
        }
        playerChannels.clear();
        HandlerList.unregisterAll(this);
    }

    // ==================== Bukkit 事件：玩家进出 ====================

    /**
     * 玩家加入时注入 handler（在线玩家已在 {@link #register} 中注入）。
     *
     * @param event 玩家加入事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        inject(event.getPlayer());
    }

    /**
     * 玩家退出时移除 handler（与 VisibilityTracker 的 PlayerQuit 监听独立，不冲突）。
     *
     * @param event 玩家退出事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        uninject(event.getPlayer());
    }

    // ==================== 注入 / 移除 ====================

    /**
     * 为玩家注入 ChannelDuplexHandler。
     *
     * <p>已注入玩家（存在于 {@link #playerChannels}）直接返回，保证幂等。</p>
     *
     * @param player 目标玩家
     */
    private void inject(Player player) {
        if (playerChannels.containsKey(player)) {
            return;
        }
        Channel channel = getChannel(player);
        if (channel == null) {
            return;
        }

        ChannelDuplexHandler handler = new ChannelDuplexHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (INTERACT_PACKET_CLASS != null && INTERACT_PACKET_CLASS.isInstance(msg)) {
                    handlePacket(player, msg);
                    // 不取消包：Bukkit 后续的 PlayerInteractEvent 等仍需正常触发
                }
                super.channelRead(ctx, msg);
            }
        };

        // pipeline 操作必须在 EventLoop 中执行，避免与 Netty I/O 线程竞态
        if (channel.eventLoop().inEventLoop()) {
            injectHandler(player, channel, handler);
        } else {
            channel.eventLoop().execute(() -> injectHandler(player, channel, handler));
        }
    }

    /**
     * 实际执行 pipeline 注入，成功后记录到 {@link #playerChannels}。
     *
     * @param player  玩家
     * @param channel Netty Channel
     * @param handler 待注入的 handler
     */
    private void injectHandler(Player player, Channel channel, ChannelDuplexHandler handler) {
        try {
            // 若已存在同名 handler（极端情况：重复注入），先移除
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
            channel.pipeline().addBefore(INJECT_BEFORE, HANDLER_NAME, handler);
            playerChannels.put(player, channel);
        } catch (Exception e) {
            plugin.getLogger().warning(() -> "注入交互监听 handler 失败: " + e.getMessage());
        }
    }

    /**
     * 移除玩家的 handler。在 EventLoop 中执行 map 移除 + pipeline 移除。
     *
     * @param player 目标玩家
     */
    private void uninject(Player player) {
        Channel channel = playerChannels.get(player);
        if (channel == null) {
            return;
        }
        if (channel.eventLoop().inEventLoop()) {
            removeHandlerAndTrack(player, channel);
        } else {
            channel.eventLoop().execute(() -> removeHandlerAndTrack(player, channel));
        }
    }

    /**
     * 实际执行 pipeline 移除 + map 清理。
     */
    private void removeHandlerAndTrack(Player player, Channel channel) {
        playerChannels.remove(player);
        try {
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning(() -> "移除交互监听 handler 失败: " + e.getMessage());
        }
    }

    // ==================== 反射链路 ====================

    /**
     * 反射获取玩家的 Netty Channel。
     *
     * <p>链路：CraftPlayer.getHandle() → ServerPlayer.connection →
     * ServerCommonPacketListenerImpl.connection → Connection.channel</p>
     *
     * @param player 玩家
     * @return Channel，失败返回 null
     */
    private Channel getChannel(Player player) {
        try {
            Object serverPlayer = ReflectUtil.invokeMethod(player, "getHandle");
            Object connection = ReflectUtil.getFieldValue(serverPlayer, "connection");
            if (connection == null) {
                return null;
            }
            // ServerCommonPacketListenerImpl.connection 字段（Connection 类型）
            // ReflectUtil.findFieldInHierarchy 会遍历父类找到该字段
            Object nmConnection = ReflectUtil.getFieldValue(connection, "connection");
            if (nmConnection == null) {
                return null;
            }
            return ReflectUtil.getFieldValue(nmConnection, "channel");
        } catch (WooNPCsReflectException e) {
            plugin.getLogger().warning(() -> "获取玩家 Channel 失败: " + e.getMessage());
            return null;
        }
    }

    // ==================== 包处理 ====================

    /**
     * 在 Netty I/O 线程解析交互包，提取 entityId 与 action 类型，调度到主线程。
     *
     * <p>仅做反射读字段 + 调度，不调用任何 Bukkit API（线程不安全）。</p>
     *
     * @param player 玩家
     * @param packet ServerboundInteractPacket 实例
     */
    private void handlePacket(Player player, Object packet) {
        try {
            int entityId = ReflectUtil.getFieldValue(packet, "entityId");
            Object action = ReflectUtil.getFieldValue(packet, "action");
            if (action == null) {
                return;
            }
            // action 实际类型为 ServerboundInteractPacket.InteractAction / InteractAtAction / AttackAction
            // 用 simple name + startsWith 兼容 "Attack" / "AttackAction" 等命名
            String actionTypeName = action.getClass().getSimpleName();
            boolean isAttack = actionTypeName.startsWith("Attack");

            // 调度到主线程：Bukkit API（player.isSneaking、事件触发）必须主线程调用
            Bukkit.getScheduler().runTask(plugin, () -> handleInteract(player, entityId, isAttack));
        } catch (WooNPCsReflectException e) {
            // 字段名变化等反射失败：记录 warning，不处理该次交互，不中断后续包
            plugin.getLogger().warning(() -> "解析交互包失败（字段名可能变化）: " + e.getMessage());
        } catch (RuntimeException e) {
            // 防御性捕获：NPE 等运行时异常不应传播到 Netty I/O 线程导致玩家断开
            plugin.getLogger().warning(() -> "解析交互包时发生意外异常: " + e.getMessage());
        }
    }

    /**
     * 主线程处理交互：查找 NPC → 触发事件 → 调用 {@link Npc#interact}。
     *
     * @param player   玩家
     * @param entityId 客户端实体 ID
     * @param isAttack 是否为攻击 action（AttackAction）
     */
    private void handleInteract(Player player, int entityId, boolean isAttack) {
        if (!player.isOnline()) {
            return;
        }
        Optional<Npc> opt = npcManager.getByEntityId(entityId);
        if (opt.isEmpty()) {
            return;
        }
        // NpcInteractEvent 由 NpcImpl.interact 内部统一触发一次（避免双发）
        // ActionManager 监听 NpcInteractEvent 时自行检查 isCancelled
        ClickType clickType = resolveClickType(player, isAttack);
        opt.get().interact(player, clickType);
    }

    /**
     * 根据玩家潜行状态与 action 类型解析点击类型。
     *
     * @param player   玩家
     * @param isAttack 是否为攻击 action
     * @return 点击类型
     */
    private ClickType resolveClickType(Player player, boolean isAttack) {
        if (player.isSneaking()) {
            return ClickType.SHIFT_CLICK;
        }
        return isAttack ? ClickType.LEFT_CLICK : ClickType.RIGHT_CLICK;
    }
}
