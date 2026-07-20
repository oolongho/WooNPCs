package com.oolongho.woonpc.gui;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.util.Scheduler;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
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
 * <p>由 {@link WooNPCs} 主类在 onEnable 阶段注册为 Listener。</p>
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
 * 聊天事件在异步线程触发，callback 通过 {@link Scheduler#runSync} 切回主线程执行，
 * 确保 callback 中的 Bukkit API 调用线程安全。</p>
 *
 * <h2>校验</h2>
 * <p>每个 {@link InputType} 关联独立的 {@code Validator} 函数式接口，
 * 覆盖名称长度、坐标格式、权限节点格式等场景。校验在异步聊天事件中执行，
 * 不通过则提示重新输入并重置超时。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class ChatInputManager implements Listener {

    /** 输入超时时间（30 秒 = 600 ticks） */
    private static final long INPUT_TIMEOUT = 30 * 20L;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final WooNPCs plugin;
    private final Scheduler scheduler;
    private final Map<UUID, InputContext> pendingInputs = new ConcurrentHashMap<>();
    private final Map<UUID, Scheduler.TaskHandle> timeoutTasks = new ConcurrentHashMap<>();

    /**
     * 构造聊天输入管理器。
     *
     * @param plugin    插件实例，用于日志与生命周期标识
     * @param scheduler 调度器，用于超时任务与主线程回调
     */
    public ChatInputManager(@NotNull WooNPCs plugin, @NotNull Scheduler scheduler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
    }

    /**
     * 请求玩家聊天输入（不带取消回调）。
     *
     * <p>等价于 {@link #requestInput(Player, String, InputType, Consumer, Consumer)} 传入 null onCancel。</p>
     *
     * @param player   目标玩家
     * @param prompt   提示消息（MiniMessage 格式）
     * @param type     输入类型（关联 {@code Validator} 校验逻辑）
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

        // 按 InputType 调用对应校验器：返回 null=通过，非 null=错误提示
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>输入不能为空，请重新输入。"));
            scheduleTimeout(player);
            return;
        }
        String error = ctx.inputType().validate(trimmed);
        if (error != null) {
            player.sendMessage(MM.deserialize("<red>" + error + "，请重新输入。"));
            scheduleTimeout(player);
            return;
        }

        // 输入有效：清理 + 切回玩家所在 region 执行 callback
        // Folia 上 callback 内可能调用玩家 API（openGui、sendMessage、setSkin 等），需在玩家 region
        clearPending(id);
        final String input = trimmed;
        scheduler.runAtEntity(player, () -> ctx.callback().accept(input));
    }

    // ==================== 内部方法 ====================

    /**
     * 清理玩家的待处理输入：取消超时任务 + 移除 pendingInputs 条目。
     *
     * @param id 玩家 UUID
     */
    private void clearPending(@NotNull UUID id) {
        Scheduler.TaskHandle task = timeoutTasks.remove(id);
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
        Scheduler.TaskHandle oldTask = timeoutTasks.remove(id);
        if (oldTask != null) {
            oldTask.cancel();
        }
        Scheduler.TaskHandle task = scheduler.runAtEntityLater(player, () -> {
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
     * 输入类型枚举：标识当前输入的语义，{@link #validate} 按类型校验输入合法性。
     *
     * <p>校验返回 {@code null} 表示通过；返回非 null 字符串表示错误提示（发送给玩家）。</p>
     */
    public enum InputType {
        /** 通用文本输入（仅非空校验） */
        GENERIC(s -> null),
        /** NPC 名称：1-32 字符，字母/数字/下划线/中文 */
        NPC_NAME(s -> {
            if (s.length() < 1 || s.length() > 32) {
                return "名称长度必须在 1-32 之间";
            }
            if (!s.matches("[a-zA-Z0-9_\\u4e00-\\u9fa5]+")) {
                return "名称只能包含字母、数字、下划线、中文";
            }
            return null;
        }),
        /** NPC 显示名：1-64 字符（MiniMessage 标签占用字符，故放宽） */
        DISPLAY_NAME(s -> {
            if (s.isEmpty() || s.length() > 64) {
                return "显示名长度必须在 1-64 之间";
            }
            return null;
        }),
        /** 皮肤纹理值：base64，长度 1-512 */
        SKIN_TEXTURE(s -> {
            if (s.isEmpty() || s.length() > 512) {
                return "皮肤 texture 长度无效（1-512）";
            }
            if (!s.matches("[A-Za-z0-9+/=]+")) {
                return "皮肤 texture 必须为 base64 字符";
            }
            return null;
        }),
        /** 皮肤签名值：base64，长度 0-1024（可空表示无签名） */
        SKIN_SIGNATURE(s -> {
            if (s.length() > 1024) {
                return "皮肤 signature 长度超出限制（最长 1024）";
            }
            if (!s.isEmpty() && !s.matches("[A-Za-z0-9+/=]+")) {
                return "皮肤 signature 必须为 base64 字符";
            }
            return null;
        }),
        /** 玩家名：3-16 字符，字母/数字/下划线 */
        PLAYER_NAME(s -> {
            if (s.length() < 3 || s.length() > 16) {
                return "玩家名长度必须在 3-16 之间";
            }
            if (!s.matches("[a-zA-Z0-9_]+")) {
                return "玩家名只能包含字母、数字、下划线";
            }
            return null;
        }),
        /** 权限节点：1-64 字符，字母/数字/点/下划线 */
        PERMISSION(s -> {
            if (s.isEmpty() || s.length() > 64) {
                return "权限节点长度必须在 1-64 之间";
            }
            if (!s.matches("[a-zA-Z0-9_.]+")) {
                return "权限节点只能包含字母、数字、点、下划线";
            }
            return null;
        }),
        /** 动作参数值：1-256 字符 */
        ACTION_VALUE(s -> {
            if (s.isEmpty() || s.length() > 256) {
                return "动作参数长度必须在 1-256 之间";
            }
            return null;
        }),
        /** 动作命令内容：1-256 字符（不含前导 "/"） */
        ACTION_COMMAND(s -> {
            String cmd = s.startsWith("/") ? s.substring(1) : s;
            if (cmd.isEmpty() || cmd.length() > 256) {
                return "命令长度必须在 1-256 之间";
            }
            return null;
        }),
        /** 音效名称：1-64 字符，字母/数字/点/下划线 */
        SOUND_NAME(s -> {
            if (s.isEmpty() || s.length() > 64) {
                return "音效名称长度必须在 1-64 之间";
            }
            if (!s.matches("[a-zA-Z0-9_.]+")) {
                return "音效名称只能包含字母、数字、点、下划线";
            }
            return null;
        }),
        /** 坐标：支持 "x y z" / "x,y,z" / "x y z yaw pitch" 格式 */
        COORDINATES(s -> {
            String normalized = s.replace(",", " ").trim();
            String[] parts = normalized.split("\\s+");
            if (parts.length < 3 || parts.length > 5) {
                return "坐标格式无效，需为 x y z 或 x y z yaw pitch";
            }
            for (String p : parts) {
                try {
                    Double.parseDouble(p);
                } catch (NumberFormatException e) {
                    return "坐标值无效: " + p;
                }
            }
            return null;
        });

        private final Validator validator;

        InputType(Validator validator) {
            this.validator = validator;
        }

        /**
         * 校验输入文本。
         *
         * @param input 玩家输入（已 trim）
         * @return null 表示通过，非 null 字符串表示错误提示
         */
        public String validate(String input) {
            return validator.validate(input);
        }

        /** 校验器函数式接口：返回 null=通过，非 null=错误提示。 */
        @FunctionalInterface
        public interface Validator {
            String validate(String input);
        }
    }
}
