package com.oolongho.woonpc.command;

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
import com.oolongho.woonpc.api.NpcBuilder;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.api.SkinManager;
import com.oolongho.woonpc.api.actions.ActionManager;
import com.oolongho.woonpc.api.actions.ActionTrigger;
import com.oolongho.woonpc.api.actions.NpcAction;
import com.oolongho.woonpc.command.arguments.EnumValueArgument;
import com.oolongho.woonpc.command.arguments.NpcNameArgument;
import com.oolongho.woonpc.command.arguments.SkinSourceArgument;
import com.oolongho.woonpc.config.MessageManager;
import com.oolongho.woonpc.gui.ChatInputManager;
import com.oolongho.woonpc.gui.GuiManager;
import com.oolongho.woonpc.gui.NpcListGui;
import com.oolongho.woonpc.npc.GlowingColor;
import com.oolongho.woonpc.npc.NpcEffect;
import com.oolongho.woonpc.npc.NpcEquipmentSlot;
import com.oolongho.woonpc.npc.NpcPose;
import com.oolongho.woonpc.skin.SkinData;
import com.oolongho.woonpc.storage.NpcStorage;
import com.oolongho.woonpc.util.CommandSafety;
import com.oolongho.woonpc.util.Scheduler;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * /woonpc 命令入口与子命令分发。
 *
 * <p>实现 {@link CommandExecutor} + {@link TabCompleter}，持有插件核心依赖，
 * 将 14 个子命令注册到内部表，按 {@code args[0]} 分发。所有子命令共享
 * {@code woonpc.admin} 权限校验（入口统一校验，子命令不再重复）。</p>
 *
 * <h2>子命令清单</h2>
 * <ul>
 *   <li>{@code create <name>} — 在玩家位置创建 NPC（玩家专属）</li>
 *   <li>{@code remove <name>} — 删除 NPC</li>
 *   <li>{@code list [page]} — 分页列出所有 NPC</li>
 *   <li>{@code info <name>} — 显示 NPC 详情</li>
 *   <li>{@code movehere <name>} — NPC 移到玩家位置（玩家专属）</li>
 *   <li>{@code moveto <name> <x> <y> <z> [yaw] [pitch]} — 移到指定坐标</li>
 *   <li>{@code skin <name> <player|texture|default>} — 设置皮肤（玩家名异步获取）</li>
 *   <li>{@code equipment <name> <slot> <material> [amount]} — 设置装备</li>
 *   <li>{@code glowing <name> <color|none>} — 设置发光颜色</li>
 *   <li>{@code pose <name> <pose>} — 设置姿势</li>
 *   <li>{@code action <name> <add|remove|list> [trigger] [type] [args...]} — 管理动作</li>
 *   <li>{@code nearby <radius>} — 列出附近 NPC（玩家专属）</li>
 *   <li>{@code copy <src> <dest>} — 复制 NPC</li>
 *   <li>{@code reload} — 重载配置 + 保存 NPC 数据</li>
 *   <li>{@code gui} — 打开 GUI 列表（玩家专属）</li>
 * </ul>
 *
 * <h2>装配契约</h2>
 * <p>由主类 {@link com.oolongho.woonpc.WooNPCs} 在 onEnable 阶段装配，构造参数：</p>
 * <pre>{@code
 * MainCommand cmd = new MainCommand(plugin, npcManager, storage, actionManager, skinManager);
 * plugin.getCommand("woonpc").setExecutor(cmd);
 * plugin.getCommand("woonpc").setTabCompleter(cmd);
 * }</pre>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class MainCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PERM = "woonpc.admin";

    private final WooNPCs plugin;
    private final NpcManager npcManager;
    private final NpcStorage storage;
    private final ActionManager actionManager;
    private final SkinManager skinManager;
    private final GuiManager guiManager;
    private final ChatInputManager chatInputManager;
    private final Scheduler scheduler;
    private final MessageManager messageManager;

    /** 子命令注册表（保持注册顺序，便于 help/补全展示） */
    private final Map<String, SubCommand> subCommands = new LinkedHashMap<>();
    /** 子命令名列表（用于 tab 补全） */
    private final List<String> subCommandNames;

    /**
     * 构造主命令处理器。
     *
     * @param plugin        插件实例
     * @param npcManager    NPC 管理器
     * @param storage       NPC 持久化存储
     * @param actionManager 动作管理器（action 子命令使用）
     * @param skinManager   皮肤管理器（skin 子命令异步获取使用）
     * @param guiManager    GUI 管理器（gui 子命令使用）
     * @param chatInputManager 聊天输入管理器（GUI 文本输入使用）
     * @param scheduler     调度器（异步皮肤回调切回主线程使用）
     * @param messageManager 消息管理器（前缀与本地化使用）
     */
    public MainCommand(@NotNull WooNPCs plugin,
                       @NotNull NpcManager npcManager,
                       @NotNull NpcStorage storage,
                       @NotNull ActionManager actionManager,
                       @NotNull SkinManager skinManager,
                       @NotNull GuiManager guiManager,
                       @NotNull ChatInputManager chatInputManager,
                       @NotNull Scheduler scheduler,
                       @NotNull MessageManager messageManager) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager cannot be null");
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.actionManager = Objects.requireNonNull(actionManager, "actionManager cannot be null");
        this.skinManager = Objects.requireNonNull(skinManager, "skinManager cannot be null");
        this.guiManager = Objects.requireNonNull(guiManager, "guiManager cannot be null");
        this.chatInputManager = Objects.requireNonNull(chatInputManager, "chatInputManager cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.messageManager = Objects.requireNonNull(messageManager, "messageManager cannot be null");
        register("create", this::create);
        register("remove", this::remove);
        register("list", this::list);
        register("info", this::info);
        register("movehere", this::movehere);
        register("moveto", this::moveto);
        register("skin", this::skin);
        register("equipment", this::equipment);
        register("glowing", this::glowing);
        register("pose", this::pose);
        register("action", this::action);
        register("nearby", this::nearby);
        register("copy", this::copy);
        register("reload", this::reload);
        register("gui", this::gui);
        this.subCommandNames = new ArrayList<>(subCommands.keySet());
    }

    private void register(String name, SubCommand cmd) {
        subCommands.put(name.toLowerCase(Locale.ROOT), cmd);
    }

    /** 子命令函数式接口 */
    @FunctionalInterface
    interface SubCommand {
        void execute(CommandSender sender, String[] args);
    }

    // ==================== CommandExecutor ====================

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERM)) {
            send(sender, "<red>你没有权限执行此操作。");
            return true;
        }
        if (args.length == 0) {
            send(sender, "<gray>用法: /woonpc <create|remove|list|info|movehere|moveto|skin|equipment|glowing|pose|action|nearby|copy|reload|gui>");
            return true;
        }
        SubCommand sub = subCommands.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            send(sender, "<red>未知子命令: <yellow>" + args[0]);
            return true;
        }
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        try {
            sub.execute(sender, subArgs);
        } catch (Exception e) {
            send(sender, "<red>命令执行失败: " + e.getMessage());
            plugin.getLogger().log(Level.WARNING, "子命令 '" + args[0] + "' 执行异常", e);
        }
        return true;
    }

    // ==================== TabCompleter ====================

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERM)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filterPrefix(subCommandNames, args[0]);
        }
        String subName = args[0].toLowerCase(Locale.ROOT);
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        return tabComplete(subName, subArgs);
    }

    private List<String> tabComplete(String sub, String[] args) {
        switch (sub) {
            case "remove", "info", "movehere" -> {
                if (args.length == 1) return NpcNameArgument.complete(npcManager, args[0]);
                return Collections.emptyList();
            }
            case "copy" -> {
                if (args.length == 1) return NpcNameArgument.complete(npcManager, args[0]);
                if (args.length == 2) return NpcNameArgument.complete(npcManager, args[1]);
                return Collections.emptyList();
            }
            case "moveto" -> {
                // <name> <x> <y> <z> [yaw] [pitch]
                if (args.length == 1) return NpcNameArgument.complete(npcManager, args[0]);
                return Collections.emptyList();
            }
            case "skin" -> {
                // <name> <source>
                if (args.length == 1) return NpcNameArgument.complete(npcManager, args[0]);
                if (args.length == 2) return SkinSourceArgument.complete(args[1]);
                return Collections.emptyList();
            }
            case "equipment" -> {
                // <name> <slot> <material> [amount]
                if (args.length == 1) return NpcNameArgument.complete(npcManager, args[0]);
                if (args.length == 2) return EnumValueArgument.complete(NpcEquipmentSlot.class, args[1]);
                if (args.length == 3) return completeMaterial(args[2]);
                return Collections.emptyList();
            }
            case "glowing" -> {
                if (args.length == 1) return NpcNameArgument.complete(npcManager, args[0]);
                if (args.length == 2) return EnumValueArgument.complete(GlowingColor.class, args[1]);
                return Collections.emptyList();
            }
            case "pose" -> {
                if (args.length == 1) return NpcNameArgument.complete(npcManager, args[0]);
                if (args.length == 2) return EnumValueArgument.complete(NpcPose.class, args[1]);
                return Collections.emptyList();
            }
            case "action" -> {
                // <name> <add|remove|list> [trigger] [type] [args...]
                if (args.length == 1) return NpcNameArgument.complete(npcManager, args[0]);
                if (args.length == 2) return filterPrefix(Arrays.asList("add", "remove", "list"), args[1]);
                if (args.length == 3) return filterPrefix(Arrays.asList("left_click", "left", "right_click", "right", "any_click", "any", "custom"), args[2]);
                if (args.length == 4 && args[1].equalsIgnoreCase("add")) {
                    return filterPrefix(Arrays.asList(
                            "message", "console_command", "player_command",
                            "player_command_as_op", "play_sound", "wait",
                            "need_permission", "send_to_server"), args[3]);
                }
                return Collections.emptyList();
            }
            default -> {
                return Collections.emptyList();
            }
        }
    }

    // ==================== 子命令实现 ====================

    /** create <name> — 在玩家位置创建 NPC */
    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>该命令只能由玩家执行。");
            return;
        }
        if (args.length != 1) {
            send(sender, "<gray>用法: /woonpc create <name>");
            return;
        }
        String name = args[0];
        if (!CommandSafety.validateName(name)) {
            send(sender, "<red>名称不合法（1-32 字符，仅字母数字下划线）。");
            return;
        }
        if (npcManager.containsByName(name)) {
            send(sender, "<red>名为 <yellow>" + name + " <red>的 NPC 已存在。");
            return;
        }
        try {
            Npc npc = new NpcBuilder(npcManager)
                    .name(name)
                    .location(player.getLocation())
                    .build();
            storage.save(npc);
            send(sender, "<green>NPC <yellow>" + name + " <green>已创建。");
        } catch (IllegalStateException e) {
            send(sender, "<red>创建失败: " + e.getMessage());
        }
    }

    /** remove <name> — 删除 NPC */
    private void remove(CommandSender sender, String[] args) {
        if (args.length != 1) {
            send(sender, "<gray>用法: /woonpc remove <name>");
            return;
        }
        String name = args[0];
        if (!CommandSafety.validateName(name)) {
            send(sender, "<red>名称不合法（1-32 字符，仅字母数字下划线）。");
            return;
        }
        Optional<Npc> opt = NpcNameArgument.parse(npcManager, name);
        if (opt.isEmpty()) {
            send(sender, "<red>找不到名为 <yellow>" + args[0] + " <red>的 NPC。");
            return;
        }
        Npc npc = opt.get();
        UUID id = npc.getId();
        actionManager.clearNpc(id);
        npcManager.remove(id);
        storage.delete(id);
        send(sender, "<green>NPC <yellow>" + name + " <green>已移除。");
    }

    /** list [page] — 分页列出所有 NPC */
    private void list(CommandSender sender, String[] args) {
        int page = 1;
        if (args.length >= 1) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
                // 保持默认 page=1
            }
        }
        List<Npc> all = new ArrayList<>(npcManager.getAll());
        int pageSize = 8;
        int total = Math.max(1, (all.size() + pageSize - 1) / pageSize);
        if (page < 1) page = 1;
        if (page > total) page = total;
        send(sender, "<gray>NPC 列表 <dark_gray>(<yellow>" + all.size() + "<dark_gray>) <gray>第 <yellow>"
                + page + "<gray>/<yellow>" + total + " <gray>页");
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, all.size());
        for (int i = start; i < end; i++) {
            Npc npc = all.get(i);
            Location loc = npc.getLocation();
            String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "?";
            send(sender, "<yellow>" + npc.getName() + " <gray>| <aqua>" + worldName + " <gray>"
                    + String.format(Locale.ROOT, "%.1f", loc.getX()) + ", "
                    + String.format(Locale.ROOT, "%.1f", loc.getY()) + ", "
                    + String.format(Locale.ROOT, "%.1f", loc.getZ()));
        }
    }

    /** info <name> — 显示 NPC 详情 */
    private void info(CommandSender sender, String[] args) {
        if (args.length != 1) {
            send(sender, "<gray>用法: /woonpc info <name>");
            return;
        }
        Optional<Npc> opt = NpcNameArgument.parse(npcManager, args[0]);
        if (opt.isEmpty()) {
            send(sender, "<red>找不到名为 <yellow>" + args[0] + " <red>的 NPC。");
            return;
        }
        Npc npc = opt.get();
        NpcData d = npc.getData();
        Location loc = d.location();
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "?";
        send(sender, "<yellow>===== NPC: " + npc.getName() + " =====");
        send(sender, "<gray>UUID: <aqua>" + npc.getId());
        send(sender, "<gray>EntityId: <aqua>" + npc.getEntityId());
        send(sender, "<gray>位置: <aqua>" + worldName + " "
                + String.format(Locale.ROOT, "%.2f, %.2f, %.2f", loc.getX(), loc.getY(), loc.getZ())
                + " <gray>(yaw=" + String.format(Locale.ROOT, "%.1f", loc.getYaw()) + ")");
        send(sender, "<gray>显示名: <aqua>" + (d.displayName() == null ? "<无>" : d.displayName()));
        send(sender, "<gray>皮肤: <aqua>" + (d.skin().isDefault() ? "<默认>" : "<自定义>"));
        send(sender, "<gray>发光: <aqua>" + d.glowColor().name());
        send(sender, "<gray>姿势: <aqua>" + d.pose().name());
        send(sender, "<gray>缩放: <aqua>" + d.scale());
        send(sender, "<gray>效果: <aqua>" + d.effects());
        send(sender, "<gray>装备槽位数: <aqua>" + d.equipment().size());
        send(sender, "<gray>显示在 Tab: <aqua>" + d.showInTab());
        send(sender, "<gray>可碰撞: <aqua>" + d.collidable());
        send(sender, "<gray>转头跟随: <aqua>" + d.turnToPlayer() + " <gray>(距离=" + d.turnToPlayerDistance() + ")");
        send(sender, "<gray>可见距离: <aqua>" + d.visibilityDistance());
        send(sender, "<gray>可见权限: <aqua>"
                + (d.visibilityPermissions().isEmpty() ? "<无>" : d.visibilityPermissions()));
        send(sender, "<gray>交互冷却: <aqua>" + d.interactionCooldown() + " ms");
    }

    /** movehere <name> — 将 NPC 移动到玩家位置 */
    private void movehere(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>该命令只能由玩家执行。");
            return;
        }
        if (args.length != 1) {
            send(sender, "<gray>用法: /woonpc movehere <name>");
            return;
        }
        Optional<Npc> opt = NpcNameArgument.parse(npcManager, args[0]);
        if (opt.isEmpty()) {
            send(sender, "<red>找不到名为 <yellow>" + args[0] + " <red>的 NPC。");
            return;
        }
        Npc npc = opt.get();
        npc.setLocation(player.getLocation());
        storage.save(npc);
        send(sender, "<green>NPC <yellow>" + npc.getName() + " <green>已移动到你的位置。");
    }

    /** moveto <name> <x> <y> <z> [yaw] [pitch] — 移动到指定坐标（x/y/z 支持 ~ 相对坐标） */
    private void moveto(CommandSender sender, String[] args) {
        if (args.length < 4 || args.length > 6) {
            send(sender, "<gray>用法: /woonpc moveto <name> <x> <y> <z> [yaw] [pitch]");
            send(sender, "<gray>提示: x/y/z 支持 ~ 相对坐标（如 <aqua>~ ~2 ~<gray>）"
                    + (sender instanceof Player ? "" : "，相对坐标基于 NPC 当前位置"));
            return;
        }
        Optional<Npc> opt = NpcNameArgument.parse(npcManager, args[0]);
        if (opt.isEmpty()) {
            send(sender, "<red>找不到名为 <yellow>" + args[0] + " <red>的 NPC。");
            return;
        }
        if (!CommandSafety.validateCoordinate(args[1])
                || !CommandSafety.validateCoordinate(args[2])
                || !CommandSafety.validateCoordinate(args[3])) {
            send(sender, "<red>坐标格式错误: <yellow>" + args[1] + ", " + args[2] + ", " + args[3]);
            return;
        }
        Npc npc = opt.get();
        Location current = npc.getLocation();
        if (current.getWorld() == null) {
            send(sender, "<red>NPC 所在世界未加载。");
            return;
        }
        // 相对坐标基准：Player sender 用玩家位置，其余用 NPC 当前位置
        double baseX = current.getX();
        double baseY = current.getY();
        double baseZ = current.getZ();
        if (sender instanceof Player player) {
            Location pl = player.getLocation();
            baseX = pl.getX();
            baseY = pl.getY();
            baseZ = pl.getZ();
        }
        double x = CommandSafety.parseCoordinate(args[1], baseX);
        double y = CommandSafety.parseCoordinate(args[2], baseY);
        double z = CommandSafety.parseCoordinate(args[3], baseZ);
        float yaw = current.getYaw();
        float pitch = current.getPitch();
        if (args.length >= 5) {
            if (!CommandSafety.validateCoordinate(args[4])) {
                send(sender, "<red>yaw 格式错误: <yellow>" + args[4]);
                return;
            }
            yaw = (float) CommandSafety.parseCoordinate(args[4], current.getYaw());
        }
        if (args.length >= 6) {
            if (!CommandSafety.validateCoordinate(args[5])) {
                send(sender, "<red>pitch 格式错误: <yellow>" + args[5]);
                return;
            }
            pitch = (float) CommandSafety.parseCoordinate(args[5], current.getPitch());
        }
        Location loc = new Location(current.getWorld(), x, y, z, yaw, pitch);
        npc.setLocation(loc);
        storage.save(npc);
        send(sender, "<green>NPC <yellow>" + npc.getName() + " <green>已移动到 <aqua>"
                + String.format(Locale.ROOT, "%.2f, %.2f, %.2f", x, y, z));
    }

    /** skin <name> <player|texture|default> — 设置皮肤 */
    private void skin(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "<gray>用法: /woonpc skin <name> <player|texture|default>");
            return;
        }
        Optional<Npc> opt = NpcNameArgument.parse(npcManager, args[0]);
        if (opt.isEmpty()) {
            send(sender, "<red>找不到名为 <yellow>" + args[0] + " <red>的 NPC。");
            return;
        }
        Npc npc = opt.get();
        SkinSourceArgument.SkinSource source = SkinSourceArgument.parse(args[1]);
        if (source == null) {
            send(sender, "<red>皮肤来源无效。");
            return;
        }
        switch (source.type()) {
            case DEFAULT -> {
                npc.setSkin(SkinData.defaultSkin());
                storage.save(npc);
                send(sender, "<green>NPC <yellow>" + npc.getName() + " <green>皮肤已设为默认。");
            }
            case TEXTURE -> {
                // 直接使用纹理值（无签名）
                npc.setSkin(new SkinData(source.value(), ""));
                storage.save(npc);
                send(sender, "<green>NPC <yellow>" + npc.getName() + " <green>皮肤已设为指定纹理。");
            }
            case PLAYER -> {
                String playerName = source.value();
                send(sender, "<gray>正在异步获取玩家 <yellow>" + playerName + " <gray>的皮肤...");
                final UUID npcId = npc.getId();
                skinManager.getSkin(playerName, skin -> {
                    // 回调在异步线程，切回玩家所在 region 更新 NPC（Folia 上 setSkin 涉及发包）
                    // 控制台 sender 非 Entity，回退到 runSync（global region）
                    Runnable action = () -> {
                        Optional<Npc> recheck = npcManager.getById(npcId);
                        if (recheck.isEmpty()) {
                            return;
                        }
                        Npc target = recheck.get();
                        target.setSkin(skin);
                        storage.save(target);
                        send(sender, "<green>NPC <yellow>" + target.getName() + " <green>皮肤已更新。");
                    };
                    if (sender instanceof org.bukkit.entity.Player player) {
                        scheduler.runAtEntity(player, action);
                    } else {
                        scheduler.runSync(action);
                    }
                });
            }
        }
    }

    /** equipment <name> <slot> <material> [amount] — 设置装备 */
    private void equipment(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4) {
            send(sender, "<gray>用法: /woonpc equipment <name> <slot> <material> [amount]");
            return;
        }
        Optional<Npc> opt = NpcNameArgument.parse(npcManager, args[0]);
        if (opt.isEmpty()) {
            send(sender, "<red>找不到名为 <yellow>" + args[0] + " <red>的 NPC。");
            return;
        }
        Optional<NpcEquipmentSlot> slotOpt = EnumValueArgument.parse(NpcEquipmentSlot.class, args[1]);
        if (slotOpt.isEmpty()) {
            send(sender, "<red>装备槽位无效: <yellow>" + args[1] + "<red>，可选: <aqua>"
                    + EnumValueArgument.allowedValues(NpcEquipmentSlot.class));
            return;
        }
        final Material material;
        try {
            material = Material.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            send(sender, "<red>物品材质无效: <yellow>" + args[2]);
            return;
        }
        int amount = 1;
        if (args.length == 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                send(sender, "<red>数量格式错误。");
                return;
            }
            if (amount < 1 || amount > 64) {
                send(sender, "<red>数量必须在 1-64 之间。");
                return;
            }
        }
        Npc npc = opt.get();
        NpcEquipmentSlot slot = slotOpt.get();
        ItemStack item = new ItemStack(material, amount);
        // 复制当前装备 map，修改对应槽位（NpcData.equipment() 返回不可修改视图）
        Map<NpcEquipmentSlot, ItemStack> equipment = new LinkedHashMap<>(npc.getData().equipment());
        equipment.put(slot, item);
        npc.setEquipment(equipment);
        storage.save(npc);
        send(sender, "<green>NPC <yellow>" + npc.getName() + " <green>装备槽 <aqua>"
                + slot.name() + " <green>已设为 <aqua>" + material.name());
    }

    /** glowing <name> <color|none> — 设置发光颜色 */
    private void glowing(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "<gray>用法: /woonpc glowing <name> <color|none>");
            return;
        }
        Optional<Npc> opt = NpcNameArgument.parse(npcManager, args[0]);
        if (opt.isEmpty()) {
            send(sender, "<red>找不到名为 <yellow>" + args[0] + " <red>的 NPC。");
            return;
        }
        Optional<GlowingColor> colorOpt = EnumValueArgument.parse(GlowingColor.class, args[1]);
        if (colorOpt.isEmpty()) {
            send(sender, "<red>发光颜色无效: <yellow>" + args[1] + "<red>，可选: <aqua>"
                    + EnumValueArgument.allowedValues(GlowingColor.class));
            return;
        }
        Npc npc = opt.get();
        npc.setGlowColor(colorOpt.get());
        storage.save(npc);
        send(sender, "<green>NPC <yellow>" + npc.getName() + " <green>发光颜色已设为 <aqua>"
                + colorOpt.get().name());
    }

    /** pose <name> <pose> — 设置姿势 */
    private void pose(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "<gray>用法: /woonpc pose <name> <pose>");
            return;
        }
        Optional<Npc> opt = NpcNameArgument.parse(npcManager, args[0]);
        if (opt.isEmpty()) {
            send(sender, "<red>找不到名为 <yellow>" + args[0] + " <red>的 NPC。");
            return;
        }
        Optional<NpcPose> poseOpt = EnumValueArgument.parse(NpcPose.class, args[1]);
        if (poseOpt.isEmpty()) {
            send(sender, "<red>姿势无效: <yellow>" + args[1] + "<red>，可选: <aqua>"
                    + EnumValueArgument.allowedValues(NpcPose.class));
            return;
        }
        Npc npc = opt.get();
        npc.setPose(poseOpt.get());
        storage.save(npc);
        send(sender, "<green>NPC <yellow>" + npc.getName() + " <green>姿势已设为 <aqua>"
                + poseOpt.get().name());
    }

    /** action <name> <add|remove|list> [trigger] [type] [args...] — 管理动作 */
    private void action(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "<gray>用法: /woonpc action <name> <add|remove|list> [trigger] [type] [args...]");
            return;
        }
        Optional<Npc> opt = NpcNameArgument.parse(npcManager, args[0]);
        if (opt.isEmpty()) {
            send(sender, "<red>找不到名为 <yellow>" + args[0] + " <red>的 NPC。");
            return;
        }
        Npc npc = opt.get();
        String op = args[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "list" -> {
                send(sender, "<gray>NPC <yellow>" + npc.getName() + " <gray>的动作配置:");
                boolean any = false;
                for (ActionTrigger t : ActionTrigger.values()) {
                    List<NpcAction> actions = actionManager.getActions(npc.getId(), t);
                    if (actions.isEmpty()) continue;
                    any = true;
                    send(sender, "<aqua>" + t.name() + "<gray>:");
                    for (int i = 0; i < actions.size(); i++) {
                        send(sender, "  <gray>" + (i + 1) + ". <yellow>" + actions.get(i).typeId());
                    }
                }
                if (!any) send(sender, "<gray>无任何动作配置。");
            }
            case "remove" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法: /woonpc action <name> remove <trigger>");
                    return;
                }
                ActionTrigger trigger = parseTrigger(args[2]);
                if (trigger == null) {
                    send(sender, "<red>触发器无效: <yellow>" + args[2]
                            + " <gray>(left_click/right_click/any_click/custom)");
                    return;
                }
                actionManager.setActions(npc.getId(), trigger, List.of());
                send(sender, "<green>已清空 NPC <yellow>" + npc.getName()
                        + " <green>在 <aqua>" + trigger.name() + " <green>下的动作。");
            }
            case "add" -> {
                if (args.length < 4) {
                    send(sender, "<gray>用法: /woonpc action <name> add <trigger> <type> [args...]");
                    return;
                }
                ActionTrigger trigger = parseTrigger(args[2]);
                if (trigger == null) {
                    send(sender, "<red>触发器无效: <yellow>" + args[2]
                            + " <gray>(left_click/right_click/any_click/custom)");
                    return;
                }
                String type = args[3].toLowerCase(Locale.ROOT);
                String arg = args.length >= 5
                        ? String.join(" ", Arrays.copyOfRange(args, 4, args.length))
                        : "";
                NpcAction newAction = buildAction(type, arg);
                if (newAction == null) {
                    send(sender, "<red>不支持的动作类型或参数无效: <yellow>" + type);
                    send(sender, "<gray>支持: message, console_command, player_command, "
                            + "player_command_as_op, play_sound, wait, need_permission, send_to_server");
                    return;
                }
                List<NpcAction> existing = new ArrayList<>(actionManager.getActions(npc.getId(), trigger));
                existing.add(newAction);
                actionManager.setActions(npc.getId(), trigger, existing);
                send(sender, "<green>已为 NPC <yellow>" + npc.getName()
                        + " <green>添加动作 <aqua>" + type + " <green>到 <aqua>" + trigger.name());
            }
            default -> send(sender, "<red>未知操作: <yellow>" + op + " <gray>(add/remove/list)");
        }
    }

    /** nearby <radius> — 列出附近半径内的 NPC（玩家专属） */
    private void nearby(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>该命令只能由玩家执行。");
            return;
        }
        if (args.length != 1) {
            send(sender, "<gray>用法: /woonpc nearby <radius>");
            return;
        }
        double radius;
        try {
            radius = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            send(sender, "<red>半径格式错误。");
            return;
        }
        if (!CommandSafety.validateRadius(radius)) {
            send(sender, "<red>半径必须在 0-100 之间。");
            return;
        }
        Location origin = player.getLocation();
        if (origin.getWorld() == null) return;
        List<Npc> nearby = new ArrayList<>();
        double r2 = radius * radius;
        for (Npc npc : npcManager.getAll()) {
            Location loc = npc.getLocation();
            if (loc.getWorld() == null || !loc.getWorld().equals(origin.getWorld())) continue;
            if (origin.distanceSquared(loc) <= r2) {
                nearby.add(npc);
            }
        }
        if (nearby.isEmpty()) {
            send(sender, "<gray>半径 <yellow>" + radius + " <gray>内无 NPC。");
            return;
        }
        send(sender, "<gray>半径 <yellow>" + radius + " <gray>内找到 <aqua>"
                + nearby.size() + " <gray>个 NPC:");
        for (Npc npc : nearby) {
            Location loc = npc.getLocation();
            double dist = Math.sqrt(origin.distanceSquared(loc));
            send(sender, "<yellow>" + npc.getName() + " <gray>| 距离 <aqua>"
                    + String.format(Locale.ROOT, "%.2f", dist));
        }
    }

    /** copy <src> <dest> — 复制 NPC（新 UUID，新 name） */
    private void copy(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "<gray>用法: /woonpc copy <src> <dest>");
            return;
        }
        Optional<Npc> srcOpt = NpcNameArgument.parse(npcManager, args[0]);
        if (srcOpt.isEmpty()) {
            send(sender, "<red>找不到名为 <yellow>" + args[0] + " <red>的 NPC。");
            return;
        }
        String destName = args[1];
        if (!CommandSafety.validateName(destName)) {
            send(sender, "<red>目标名称不合法（1-32 字符，仅字母数字下划线）。");
            return;
        }
        if (npcManager.containsByName(destName)) {
            send(sender, "<red>名为 <yellow>" + destName + " <red>的 NPC 已存在。");
            return;
        }
        Npc src = srcOpt.get();
        NpcData s = src.getData();
        try {
            // 复制 effects：EnumSet.copyOf 不接受空集，需先判断
            java.util.Set<NpcEffect> effectsCopy = s.effects().isEmpty()
                    ? EnumSet.noneOf(NpcEffect.class)
                    : EnumSet.copyOf(s.effects());
            NpcData newData = NpcData.builder(UUID.randomUUID(), destName, s.location())
                    .displayName(s.displayName())
                    .skin(s.skin())
                    .equipment(new LinkedHashMap<>(s.equipment()))
                    .glowColor(s.glowColor())
                    .pose(s.pose())
                    .scale(s.scale())
                    .effects(effectsCopy)
                    .showInTab(s.showInTab())
                    .collidable(s.collidable())
                    .turnToPlayer(s.turnToPlayer())
                    .turnToPlayerDistance(s.turnToPlayerDistance())
                    .visibilityDistance(s.visibilityDistance())
                    .visibilityPermissions(new LinkedHashSet<>(s.visibilityPermissions()))
                    .interactionCooldown(s.interactionCooldown())
                    .build();
            Npc created = npcManager.create(newData);
            storage.save(created);
            send(sender, "<green>已复制 NPC <yellow>" + src.getName()
                    + " <green>为 <yellow>" + destName);
        } catch (IllegalStateException e) {
            send(sender, "<red>复制失败: " + e.getMessage());
        }
    }

    /** reload — 重载配置 + 重载 trackers / auto-save + 保存当前 NPC + actions 数据 */
    private void reload(CommandSender sender, String[] args) {
        // 统一调用 plugin.reloadAll()：保存数据 → reloadConfig → messageManager → DebugManager
        // → PlaceholderUtil → reloadTrackers → reloadAutoSave
        plugin.reloadAll();
        send(sender, "<green>配置已重载，NPC 与动作数据已保存。");
    }

    /** gui — 打开 GUI 列表（玩家专属） */
    private void gui(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>该命令只能由玩家执行。");
            return;
        }
        if (!player.hasPermission("woonpc.gui")) {
            send(sender, "<red>你没有权限打开 GUI。");
            return;
        }
        guiManager.openGui(player, new NpcListGui(plugin, npcManager, storage, actionManager,
                skinManager, guiManager, chatInputManager, scheduler, player));
    }

    // ==================== 辅助方法 ====================

    private void send(CommandSender sender, String message) {
        String prefix = messageManager.getRaw("prefix");
        sender.sendMessage(MM.deserialize(prefix + message));
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (String s : options) {
            if (s.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(s);
        }
        return result;
    }

    private List<String> completeMaterial(String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (Material m : Material.values()) {
            if (m.isItem()) {
                String name = m.name().toLowerCase(Locale.ROOT);
                if (name.startsWith(lower)) result.add(name);
            }
        }
        return result;
    }

    /** 解析 ActionTrigger（支持 left/right/any/click 简写） */
    private @Nullable ActionTrigger parseTrigger(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "left_click", "left" -> ActionTrigger.LEFT_CLICK;
            case "right_click", "right" -> ActionTrigger.RIGHT_CLICK;
            case "any_click", "any" -> ActionTrigger.ANY_CLICK;
            case "custom" -> ActionTrigger.CUSTOM;
            default -> null;
        };
    }

    /**
     * 根据类型与参数构造 NpcAction。
     *
     * @param type 动作类型 ID
     * @param arg  单字符串参数（多参数以空格分隔，由各 Action 自行解析）
     * @return 构造的 NpcAction，类型未知或参数无效返回 null
     */
    private @Nullable NpcAction buildAction(String type, String arg) {
        try {
            return switch (type) {
                case "message" -> arg.isEmpty() ? null : new MessageAction(arg);
                case "console_command" -> arg.isEmpty() ? null : new ConsoleCommandAction(arg);
                case "player_command" -> arg.isEmpty() ? null : new PlayerCommandAction(arg);
                case "player_command_as_op" -> arg.isEmpty() ? null : new PlayerCommandAsOpAction(arg);
                case "play_sound" -> {
                    // 格式: <sound> [volume] [pitch]
                    String[] parts = arg.split("\\s+");
                    if (parts.length == 0 || parts[0].isEmpty()) yield null;
                    String sound = parts[0];
                    float volume = parts.length >= 2 ? Float.parseFloat(parts[1]) : 1.0f;
                    float pitch = parts.length >= 3 ? Float.parseFloat(parts[2]) : 1.0f;
                    yield new PlaySoundAction(sound, volume, pitch);
                }
                case "wait" -> {
                    String trimmed = arg.trim();
                    if (trimmed.isEmpty()) yield null;
                    yield new WaitAction(Integer.parseInt(trimmed));
                }
                case "need_permission" -> arg.isEmpty() ? null : new NeedPermissionAction(arg);
                case "send_to_server" -> arg.isEmpty() ? null : new SendToServerAction(arg);
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
