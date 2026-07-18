package com.oolongho.woonpc.gui;

import com.oolongho.woonpc.WooNPCs;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 聊天输入管理器：通过聊天框接收玩家文本输入，支持超时取消与校验。
 *
 * <p>由 Task 10 在 {@link WooNPCs} 主类装配时注册为 Listener。</p>
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li>{@link #requestInput} 发送提示并挂起 30 秒超时任务</li>
 *   <li>玩家在聊天框输入 → {@link AsyncPlayerChatEvent}（LOWEST）取消事件并处理</li>
 *   <li>输入 "cancel"/"取消" → 执行 onCancel 并清理</li>
 *   <li>输入为空 → 提示重新输入 + 重置超时</li>
 *   <li>输入有效 → 清理 + 切回主线程执行 callback</li>
 *   <li>30 秒超时 → 发送超时消息 + 执行 onCancel</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * <p>{@link #pendingInputs} 与 {@link #timeoutTasks} 使用 {@link ConcurrentHashMap}。
 * 聊天事件在异步线程触发，callback 通过 {@link Bukkit#getScheduler()} 切回主线程执行，
 * 确保 callback 中的 Bukkit API 调用线程安全。</p>
 *
 * <h2>校验</h2>
 * <p>Task 1 仅实现非空校验。具体 InputType 的校验逻辑（如名称长度、坐标格式、
 * 权限节点格式等）将在 Task 3+ 各 GUI 实现时细化。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class ChatInputManager implements Listener {

    /** 输入超时时间（30 秒 = 600 ticks） */
    private static final long INPUT_TIMEOUT = 30 * 20L;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final WooNPCs plugin;
    private final Map<UUID, InputContext> pendingInputs = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();

    /**
     * 构造聊天输入管理器。
     *
     * @param plugin 插件实例，用于调度超时任务与主线程回调
     */
    public ChatInputManager(@NotNull WooNPCs plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
    }

    /**
     * 请求玩家聊天输入（不带取消回调）。
     *
     * <p>等价于 {@link #requestInput(Player, String, InputType, Consumer, Consumer)} 传入 null onCancel。</p>
     *
     * @param player   目标玩家
     * @param prompt   提示消息（MiniMessage 格式）
     * @param type     输入类型（用于后续校验，Task 3+ 细化）
     * @param callback 输入完成回调（在主线程执行，接收玩家输入文本）
     */
    public void requestInput(@NotNull Player player, @NotNull String prompt,
                             @NotNull InputType type, @NotNull Consumer<String> callback) {
        requestInput(player, prompt, type, callback, null);
    }

    /**
     * 请求玩家聊天输入（带取消回调）。
     *
     * @param player   目标玩家
     * @param prompt   提示消息（MiniMessage 格式）
     * @param type     输入类型
     * @param callback 输入完成回调（在主线程执行，接收玩家输入文本）
     * @param onCancel 取消回调（超时或玩家主动取消时执行，可为 null）
     */
    public void requestInput(@NotNull Player player, @NotNull String prompt,
                             @NotNull InputType type, @NotNull Consumer<String> callback,
                             @Nullable Consumer<Void> onCancel) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(prompt, "prompt cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");

        UUID id = player.getUniqueId();
        clearPending(id);

        pendingInputs.put(id, new InputContext(type, callback, onCancel));
        player.sendMessage(MM.deserialize(prompt));
        player.sendMessage(MM.deserialize("<gray>请在聊天框输入内容，输入 <white>\"cancel\" <gray>或 <white>\"取消\" <gray>可取消输入。"));
        scheduleTimeout(player);
    }

    /**
     * 主动取消玩家的待处理输入。
     *
     * @param player 目标玩家
     */
    public void cancelInput(@NotNull Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        clearPending(player.getUniqueId());
    }

    /**
     * 判断玩家是否有待处理的输入。
     *
     * @param player 目标玩家
     * @return true 表示有待处理输入
     */
    public boolean hasPendingInput(@NotNull Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        return pendingInputs.containsKey(player.getUniqueId());
    }

    // ==================== 事件监听 ====================

    /**
     * 聊天事件：拦截待处理输入的玩家消息。
     *
     * <p>使用 LOWEST 优先级确保在其他聊天处理器之前拦截。取消事件后：
     * 取消关键字 → 执行 onCancel；空输入 → 提示重试 + 重置超时；有效输入 → 主线程执行 callback。</p>
     *
     * @param event AsyncPlayerChatEvent
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPlayerChat(@NotNull AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        InputContext ctx = pendingInputs.get(id);
        if (ctx == null) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage();

        // 取消输入
        if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("取消")) {
            clearPending(id);
            player.sendMessage(MM.deserialize("<yellow>已取消输入。"));
            if (ctx.onCancel() != null) {
                ctx.onCancel().accept(null);
            }
            return;
        }

        // TODO Task 3+: 根据 InputType 实现具体校验逻辑（名称长度/坐标格式/权限格式等）
        // 目前所有 InputType 仅做非空校验
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>输入不能为空，请重新输入。"));
            // 校验失败：重置超时，保持 pendingInputs 等待重新输入
            scheduleTimeout(player);
            return;
        }

        // 输入有效：清理 + 切回主线程执行 callback
        clearPending(id);
        final String input = trimmed;
        Bukkit.getScheduler().runTask(plugin, () -> ctx.callback().accept(input));
    }

    // ==================== 内部方法 ====================

    /**
     * 清理玩家的待处理输入：取消超时任务 + 移除 pendingInputs 条目。
     *
     * @param id 玩家 UUID
     */
    private void clearPending(@NotNull UUID id) {
        BukkitTask task = timeoutTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
        pendingInputs.remove(id);
    }

    /**
     * 调度（或重置）超时任务：30 秒后若仍未完成则发送超时消息 + 执行 onCancel。
     *
     * @param player 目标玩家
     */
    private void scheduleTimeout(@NotNull Player player) {
        UUID id = player.getUniqueId();
        // 取消旧的超时任务
        BukkitTask oldTask = timeoutTasks.remove(id);
        if (oldTask != null) {
            oldTask.cancel();
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            InputContext ctx = pendingInputs.remove(id);
            timeoutTasks.remove(id);
            if (ctx != null) {
                player.sendMessage(MM.deserialize("<red>输入超时（30 秒），已取消。"));
                if (ctx.onCancel() != null) {
                    ctx.onCancel().accept(null);
                }
            }
        }, INPUT_TIMEOUT);
        timeoutTasks.put(id, task);
    }

    // ==================== 内部类型 ====================

    /**
     * 输入上下文记录类。
     *
     * @param inputType 输入类型
     * @param callback  完成回调
     * @param onCancel  取消回调（可为 null）
     */
    public record InputContext(@NotNull InputType inputType,
                               @NotNull Consumer<String> callback,
                               @Nullable Consumer<Void> onCancel) {
    }

    /**
     * 输入类型枚举：标识当前输入的语义，用于 Task 3+ 实现类型相关的校验逻辑。
     */
    public enum InputType {
        /** 通用文本输入 */
        GENERIC,
        /** NPC 名称（创建时） */
        NPC_NAME,
        /** NPC 显示名 */
        DISPLAY_NAME,
        /** 皮肤纹理值 */
        SKIN_TEXTURE,
        /** 皮肤签名值 */
        SKIN_SIGNATURE,
        /** 玩家名（用于获取皮肤） */
        PLAYER_NAME,
        /** 权限节点 */
        PERMISSION,
        /** 动作参数值 */
        ACTION_VALUE,
        /** 动作命令内容 */
        ACTION_COMMAND,
        /** 音效名称 */
        SOUND_NAME,
        /** 坐标输入 */
        COORDINATES
    }
}
