package com.oolongho.woonpc.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcBuilder;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.api.SkinManager;
import com.oolongho.woonpc.api.actions.ActionManager;
import com.oolongho.woonpc.api.actions.ActionTrigger;
import com.oolongho.woonpc.skin.SkinData;
import com.oolongho.woonpc.storage.NpcStorage;
import com.oolongho.woonpc.util.CommandSafety;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * NPC 列表 GUI（根级界面，54 格）。
 *
 * <p>展示当前已注册的所有 NPC，支持分页、排序与工具栏操作。
 * 作为 GUI 体系的入口，由 {@code /woonpc gui} 命令打开（需 {@code woonpc.gui} 权限）。</p>
 *
 * <h2>布局</h2>
 * <pre>
 * Row 1 (0-8):   [重载][背景][性能][背景][创建][背景][帮助][背景][附近]
 * Row 2-5 (9-44): NPC 列表（每页 45 项，PLAYER_HEAD 显示头像）
 * Row 6 (45-53): [上一页][背景][排序][背景][页码][背景][背景][背景][下一页]
 * </pre>
 *
 * <h2>排序</h2>
 * <ul>
 *   <li>{@link SortType#NAME}：按名称字母序（不区分大小写，默认）</li>
 *   <li>{@link SortType#DISTANCE}：按到查看者的距离升序（同世界优先）</li>
 *   <li>{@link SortType#ENABLED}：按可见状态（暂以 hashCode 稳定排序，待 Task 19 完善）</li>
 *   <li>{@link SortType#SKIN}：自定义皮肤在前，默认皮肤在后</li>
 * </ul>
 *
 * <p>切换排序会重置到第一页；分页与排序状态在 {@link #refresh()} 之间保留。</p>
 *
 * <h2>无闪烁刷新</h2>
 * <p>继承 {@link GuiScreen#refresh()}：清空按钮与 Inventory 后重新调用 {@link #render()}，
 * 由 {@link GuiManager} 在同一 tick 批量同步，客户端不产生闪烁。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcListGui extends GuiScreen {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Inventory 大小（6 行） */
    private static final int SIZE = 54;
    /** 每页可显示的 NPC 数量（Row 2-5，共 45 格） */
    private static final int ITEMS_PER_PAGE = 45;
    /** NPC 列表起始槽位（跳过 Row 1 工具栏） */
    private static final int START_SLOT = 9;
    /** 重载权限节点 */
    private static final String RELOAD_PERM = "woonpc.command.reload";

    private final WooNPCs plugin;
    private final NpcManager npcManager;
    private final NpcStorage storage;
    private final ActionManager actionManager;
    /** 预留给后续 GUI 间共享皮肤操作（如批量重获取） */
    @SuppressWarnings("unused")
    private final SkinManager skinManager;
    private final GuiManager guiManager;
    private final ChatInputManager chatInputManager;
    /** 查看此 GUI 的玩家，用于 DISTANCE 排序 */
    private final Player viewer;

    /** 当前排序方式 */
    private SortType sortType = SortType.NAME;
    /** 当前页码（0-based） */
    private int page = 0;

    /**
     * 构造 NPC 列表 GUI（根级，parent=null）。
     *
     * @param plugin          插件实例
     * @param npcManager      NPC 管理器
     * @param storage         NPC 持久化存储
     * @param actionManager   动作管理器（用于统计 NPC 动作数）
     * @param skinManager     皮肤管理器（预留给后续皮肤相关操作）
     * @param guiManager      GUI 管理器（用于打开/关闭 GUI）
     * @param chatInputManager 聊天输入管理器（用于创建 NPC 时输入名称）
     * @param viewer          查看此 GUI 的玩家（用于 DISTANCE 排序）
     */
    public NpcListGui(@NotNull WooNPCs plugin,
                      @NotNull NpcManager npcManager,
                      @NotNull NpcStorage storage,
                      @NotNull ActionManager actionManager,
                      @NotNull SkinManager skinManager,
                      @NotNull GuiManager guiManager,
                      @NotNull ChatInputManager chatInputManager,
                      @NotNull Player viewer) {
        super("<dark_aqua>NPC 列表", SIZE, null);
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager cannot be null");
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.actionManager = Objects.requireNonNull(actionManager, "actionManager cannot be null");
        this.skinManager = Objects.requireNonNull(skinManager, "skinManager cannot be null");
        this.guiManager = Objects.requireNonNull(guiManager, "guiManager cannot be null");
        this.chatInputManager = Objects.requireNonNull(chatInputManager, "chatInputManager cannot be null");
        this.viewer = Objects.requireNonNull(viewer, "viewer cannot be null");
    }

    // ==================== 渲染入口 ====================

    @Override
    public void render() {
        // 获取排序后的 NPC 列表（render 内只构造一次，供列表与分页共用）
        List<Npc> npcs = getSortedNpcs();
        int total = npcs.size();
        int totalPages = Math.max(1, (total + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        // 分页边界保护：page 越界时回退（NPC 被删除/切换排序后可能发生）
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        fillFirstRow();
        renderToolbar();
        renderNpcList(npcs);
        renderPagination(total, totalPages);
    }

    // ==================== Row 1 背景填充 ====================

    /**
     * 填充 Row 1 中未被工具栏占用的槽位（1/3/5/7）为灰色玻璃背景。
     */
    private void fillFirstRow() {
        for (int slot = 1; slot <= 7; slot += 2) {
            setButton(slot, backgroundButton());
        }
    }

    // ==================== Row 1 工具栏（slot 0/2/4/6/8） ====================

    private void renderToolbar() {
        renderReloadButton();
        renderPerformanceButton();
        renderCreateButton();
        renderHelpButton();
        renderNearbyButton();
    }

    /** [0] 重载配置：重载 plugin 配置 + 保存全部 NPC 数据（需 woonpc.command.reload 权限） */
    private void renderReloadButton() {
        boolean hasPerm = viewer.hasPermission(RELOAD_PERM);
        GuiButton.Builder builder = GuiButton.builder(Material.CLOCK)
                .name("<aqua>重载配置")
                .lore(List.of(
                        "<gray>重载 plugin/NpcManager",
                        "<gray>/NpcStorage/ActionManager",
                        hasPerm ? "<green>点击执行重载" : "<red>需要 woonpc.command.reload 权限"
                ));
        if (hasPerm) {
            builder.onClick(ctx -> {
                plugin.reloadConfig();
                storage.saveAll();
                ctx.player().sendMessage(MM.deserialize("<green>配置已重载，NPC 数据已保存"));
                refresh();
            });
        } else {
            builder.onClick(ctx ->
                    ctx.player().sendMessage(MM.deserialize("<red>无权限: woonpc.command.reload")));
        }
        setButton(0, builder.build());
    }

    /** [2] 性能分析：预留入口（Task 19 性能测试后实现） */
    private void renderPerformanceButton() {
        setButton(2, GuiButton.builder(Material.COMPARATOR)
                .name("<aqua>性能分析")
                .lore(List.of("<gray>预留（Task 19 性能测试后实现）"))
                .onClick(ctx ->
                        ctx.player().sendMessage(MM.deserialize("<yellow>功能开发中")))
                .build());
    }

    /** [4] 创建 NPC：关闭 GUI → 聊天输入名称 → 校验 → 创建 → 重新打开列表 */
    private void renderCreateButton() {
        setButton(4, GuiButton.builder(Material.EMERALD)
                .name("<green>创建 NPC")
                .lore(List.of("<gray>在玩家位置创建 NPC"))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    guiManager.closeGui(p);
                    chatInputManager.requestInput(p,
                            "<aqua>请输入 NPC 名称（1-32 字符，仅字母数字下划线）",
                            ChatInputManager.InputType.NPC_NAME,
                            name -> {
                                // 校验名称合法性
                                if (!CommandSafety.validateName(name)) {
                                    p.sendMessage(MM.deserialize("<red>名称不合法（1-32 字符，仅字母数字下划线）。"));
                                    guiManager.openGui(p, this);
                                    return;
                                }
                                // 校验名称唯一性
                                if (npcManager.containsByName(name)) {
                                    p.sendMessage(MM.deserialize("<red>名为 <yellow>" + name + " <red>的 NPC 已存在。"));
                                    guiManager.openGui(p, this);
                                    return;
                                }
                                try {
                                    Npc created = new NpcBuilder(npcManager)
                                            .name(name)
                                            .location(p.getLocation())
                                            .build();
                                    storage.save(created);
                                    // Task 3 NpcDetailGui 未实现：发送提示并重新打开列表
                                    p.sendMessage(MM.deserialize("<green>NPC <yellow>" + name
                                            + " <green>已创建，详情页开发中"));
                                    guiManager.openGui(p, this);
                                } catch (IllegalStateException e) {
                                    p.sendMessage(MM.deserialize("<red>创建失败: " + e.getMessage()));
                                    guiManager.openGui(p, this);
                                }
                            });
                })
                .build());
    }

    /** [6] 帮助手册：发送命令列表与权限概要到聊天框 */
    private void renderHelpButton() {
        setButton(6, GuiButton.builder(Material.KNOWLEDGE_BOOK)
                .name("<aqua>帮助手册")
                .lore(List.of("<gray>查看命令列表与权限"))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    p.sendMessage(MM.deserialize("<dark_aqua>===== WooNPCs 命令手册 ====="));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc create <name> <gray>- 创建 NPC"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc remove <name> <gray>- 删除 NPC"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc list [page] <gray>- 列出 NPC"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc info <name> <gray>- NPC 详情"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc movehere <name> <gray>- NPC 移到此处"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc moveto <name> <x> <y> <z> [yaw] [pitch]"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc skin <name> <player|texture|default>"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc equipment <name> <slot> <material>"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc glowing <name> <color|none>"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc pose <name> <pose>"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc action <name> <add|remove|list> ..."));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc nearby <radius>"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc copy <src> <dest>"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc gui <gray>- 打开 GUI"));
                    p.sendMessage(MM.deserialize("<yellow>/woonpc reload <gray>- 重载配置"));
                    p.sendMessage(MM.deserialize("<gray>权限: woonpc.admin / woonpc.gui / woonpc.command.reload"));
                })
                .build());
    }

    /** [8] 附近 NPC：切换为 DISTANCE 排序并回到第一页 */
    private void renderNearbyButton() {
        boolean active = sortType == SortType.DISTANCE;
        setButton(8, GuiButton.builder(Material.OAK_SIGN)
                .name("<aqua>附近 NPC")
                .lore(List.of(
                        "<gray>切换为距离排序",
                        active ? "<green>当前: 距离排序" : "<gray>点击切换"
                ))
                .onClick(ctx -> {
                    sortType = SortType.DISTANCE;
                    page = 0;
                    refresh();
                })
                .build());
    }

    // ==================== Row 2-5 NPC 列表（slot 9-44） ====================

    /**
     * 渲染当前页的 NPC 按钮到 slot 9-44。
     *
     * @param npcs 已排序的 NPC 列表
     */
    private void renderNpcList(List<Npc> npcs) {
        int total = npcs.size();
        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, total);
        // 预构造 UUID 列表供 NPC 按钮 onClick 使用（避免每次点击重新排序）
        List<UUID> npcIds = npcs.stream().map(Npc::getId).toList();
        for (int i = start; i < end; i++) {
            Npc npc = npcs.get(i);
            int slot = START_SLOT + (i - start);
            setButton(slot, buildNpcButton(npc, npcIds, i));
        }
        // 剩余槽位保持空（Inventory 默认空，不放置按钮）
    }

    /**
     * 构建单个 NPC 的列表按钮（PLAYER_HEAD 显示头像）。
     *
     * <p>左键点击进入 {@link NpcDetailGui}，传入 npcIds + index 供详情页 Row 6 同级切换。</p>
     *
     * @param npc    目标 NPC
     * @param npcIds 当前排序列表的 UUID 快照
     * @param index  当前 NPC 在 npcIds 中的索引
     */
    private GuiButton buildNpcButton(Npc npc, List<UUID> npcIds, int index) {
        NpcData d = npc.getData();
        Location loc = d.location();
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "?";
        String skinStatus = d.skin().isDefault() ? "默认" : "自定义";
        int actionCount = countActions(npc.getId());
        String uuidShort = npc.getId().toString().substring(0, 8);

        GuiButton.Builder builder = GuiButton.builder(Material.PLAYER_HEAD)
                .name("<yellow>" + npc.getName())
                .lore(List.of(
                        "<gray>位置: <aqua>" + worldName + " " + formatLocation(loc),
                        "<gray>皮肤: <aqua>" + skinStatus,
                        "<gray>动作数: <aqua>" + actionCount,
                        "<gray>UUID: <aqua>" + uuidShort,
                        "<dark_gray>左键: 编辑 NPC"
                ))
                .onClick(ctx -> {
                    ctx.player().closeInventory();
                    guiManager.openGui(ctx.player(),
                            new NpcDetailGui(plugin, npcManager, storage, actionManager, skinManager,
                                    guiManager, chatInputManager, npc.getId(), npcIds, index, this));
                });

        // 自定义皮肤时设置 PlayerProfile 显示真实头像（Paper API）
        if (!d.skin().isDefault()) {
            builder.metaModifier(meta -> applyHeadProfile(meta, npc, d.skin()));
        }
        return builder.build();
    }

    /**
     * 将 NPC 的皮肤数据应用到 SkullMeta 的 PlayerProfile。
     *
     * @param meta ItemStack 的 ItemMeta
     * @param npc  对应的 NPC（提供 id 与 name 作为 profile 标识）
     * @param skin 皮肤数据（texture 非空）
     */
    private void applyHeadProfile(ItemMeta meta, Npc npc, SkinData skin) {
        if (!(meta instanceof SkullMeta skullMeta)) {
            return;
        }
        PlayerProfile profile = Bukkit.createProfile(npc.getId(), npc.getName());
        String signature = skin.signature().isEmpty() ? null : skin.signature();
        profile.setProperty(new ProfileProperty("textures", skin.texture(), signature));
        skullMeta.setPlayerProfile(profile);
    }

    // ==================== Row 6 分页（slot 45-53） ====================

    /**
     * 渲染分页栏：上一页/排序/页码/下一页 + 背景填充。
     *
     * @param total      NPC 总数
     * @param totalPages 总页数（至少 1）
     */
    private void renderPagination(int total, int totalPages) {
        // 背景填充：slot 46, 48, 50, 51, 52
        setButton(46, backgroundButton());
        setButton(48, backgroundButton());
        setButton(50, backgroundButton());
        setButton(51, backgroundButton());
        setButton(52, backgroundButton());

        boolean hasPrev = page > 0;
        boolean hasNext = page < totalPages - 1;

        // [45] 上一页
        GuiButton.Builder prevBuilder = GuiButton.builder(Material.ARROW)
                .name("<aqua>上一页")
                .lore(List.of(hasPrev ? "<gray>点击翻到上一页" : "<dark_gray>已是第一页"));
        if (hasPrev) {
            prevBuilder.onClick(ctx -> {
                page--;
                refresh();
            });
        }
        setButton(45, prevBuilder.build());

        // [47] 排序：显示当前排序方式，点击 cycle 切换
        setButton(47, GuiButton.builder(Material.PAPER)
                .name("<aqua>排序: <yellow>" + sortType.name())
                .lore(List.of(
                        "<gray>点击切换排序方式",
                        "<gray>当前: <aqua>" + sortType.name()
                ))
                .onClick(ctx -> {
                    sortType = sortType.next();
                    page = 0;
                    refresh();
                })
                .build());

        // [49] 页码：显示当前页/总页数 + NPC 总数
        setButton(49, GuiButton.builder(Material.PAPER)
                .name("<aqua>第 <yellow>" + (page + 1) + "<aqua>/<yellow>" + totalPages + " <aqua>页")
                .lore(List.of("<gray>NPC 总数: <aqua>" + total))
                .build());

        // [53] 下一页
        GuiButton.Builder nextBuilder = GuiButton.builder(Material.ARROW)
                .name("<aqua>下一页")
                .lore(List.of(hasNext ? "<gray>点击翻到下一页" : "<dark_gray>已是最后一页"));
        if (hasNext) {
            nextBuilder.onClick(ctx -> {
                page++;
                refresh();
            });
        }
        setButton(53, nextBuilder.build());
    }

    // ==================== 辅助方法 ====================

    /** 构造灰色玻璃背景按钮（空白名称） */
    private GuiButton backgroundButton() {
        return GuiButton.builder(Material.GRAY_STAINED_GLASS_PANE)
                .name(" ")
                .build();
    }

    /**
     * 获取按当前 {@link #sortType} 排序后的 NPC 列表（新列表，可安全修改）。
     */
    private List<Npc> getSortedNpcs() {
        List<Npc> npcs = new ArrayList<>(npcManager.getAll());
        npcs.sort(switch (sortType) {
            // 名称字母序（不区分大小写）
            case NAME -> Comparator.comparing(Npc::getName, String.CASE_INSENSITIVE_ORDER);
            // 到查看者距离升序（同世界优先，跨世界排末尾）
            case DISTANCE -> distanceComparator();
            // TODO Task 19: 完善按可见状态排序（暂以 hashCode 稳定排序）
            case ENABLED -> Comparator.comparingInt(Object::hashCode);
            // 自定义皮肤在前，默认皮肤在后
            case SKIN -> Comparator.comparing((Npc npc) -> npc.getData().skin().isDefault());
        });
        return npcs;
    }

    /**
     * 构造距离比较器：同世界按距离平方升序，跨世界排末尾（Double.MAX_VALUE）。
     */
    private Comparator<Npc> distanceComparator() {
        Location viewerLoc = viewer.getLocation();
        return Comparator.comparingDouble((Npc npc) -> {
            Location npcLoc = npc.getLocation();
            if (npcLoc.getWorld() == null || viewerLoc.getWorld() == null
                    || !npcLoc.getWorld().equals(viewerLoc.getWorld())) {
                return Double.MAX_VALUE;
            }
            return npcLoc.distanceSquared(viewerLoc);
        });
    }

    /**
     * 统计 NPC 在所有 trigger 下的动作总数。
     */
    private int countActions(UUID npcId) {
        int count = 0;
        for (ActionTrigger trigger : ActionTrigger.values()) {
            count += actionManager.getActions(npcId, trigger).size();
        }
        return count;
    }

    /**
     * 格式化位置为可读字符串：{@code x, y, z (yaw)}，保留 1 位小数。
     */
    private String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "%.1f, %.1f, %.1f (%.1f)",
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw());
    }

    // ==================== SortType 枚举 ====================

    /**
     * NPC 列表排序方式。
     *
     * <ul>
     *   <li>{@link #NAME}：名称字母序</li>
     *   <li>{@link #DISTANCE}：到查看者距离升序</li>
     *   <li>{@link #ENABLED}：可见状态（待完善）</li>
     *   <li>{@link #SKIN}：自定义皮肤优先</li>
     * </ul>
     */
    private enum SortType {
        NAME,
        DISTANCE,
        ENABLED,
        SKIN;

        /** 循环切换到下一个排序方式（NAME → DISTANCE → ENABLED → SKIN → NAME） */
        public SortType next() {
            SortType[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }
}
