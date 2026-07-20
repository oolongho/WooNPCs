package com.oolongho.woonpc.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.api.SkinManager;
import com.oolongho.woonpc.api.actions.ActionManager;
import com.oolongho.woonpc.api.actions.ActionTrigger;
import com.oolongho.woonpc.api.actions.NpcAction;
import com.oolongho.woonpc.npc.GlowingColor;
import com.oolongho.woonpc.npc.NpcEffect;
import com.oolongho.woonpc.npc.NpcEquipmentSlot;
import com.oolongho.woonpc.npc.NpcPose;
import com.oolongho.woonpc.skin.SkinData;
import com.oolongho.woonpc.storage.NpcStorage;
import com.oolongho.woonpc.util.CommandSafety;
import com.oolongho.woonpc.util.Scheduler;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * NPC 详情 GUI（54 格，6 行布局）。
 *
 * <p>展示单个 NPC 的全部可配置状态，支持简单字段直接编辑（cycle 切换、数字调整、聊天输入）
 * 与子 GUI 入口（皮肤/装备/动作/效果/权限）。</p>
 *
 * <h2>布局</h2>
 * <pre>
 * Row 1 (0-8):   [返回][名称][位置][显示名][头像][状态][背景][背景][删除]
 * Row 2 (9-17):  [发光色][姿势][缩放][效果][背景][背景][背景][背景][背景]
 * Row 3 (18-26): [Tab显示][碰撞][转头][转头距离][交互冷却][背景][背景][背景][背景]
 * Row 4 (27-35): [可见距离][权限][皮肤][装备][动作][背景][背景][背景][背景]
 * Row 5 (36-44): [传送至此][移动到玩家][复制][重置][背景][背景][背景][背景][背景]
 * Row 6 (45-53): [上一NPC][背景][背景][背景][序号][背景][背景][背景][下一NPC]
 * </pre>
 *
 * <h2>简单字段编辑模式</h2>
 * <ul>
 *   <li><b>cycle 切换</b>（glowColor/pose）：左键下一项，右键上一项，Shift+左键重置默认</li>
 *   <li><b>数字调整</b>（scale/distance/cooldown）：左/右键 ±小步长，Shift+左/右键 ±大步长</li>
 *   <li><b>布尔切换</b>（showInTab/collidable/turnToPlayer）：左键 toggle</li>
 *   <li><b>聊天输入</b>（displayName/复制名称）：关闭 GUI → 聊天输入 → 重新打开</li>
 * </ul>
 *
 * <h2>NPC 切换</h2>
 * <p>Row 6 通过 npcList + currentIndex 实现同级 NPC 切换。npcList 在构造时复制为不可变列表，
 * 避免每次切换都重新查询 NpcManager（性能优化）。切换时构造新的 NpcDetailGui 并 openGui。</p>
 *
 * <h2>无闪烁刷新</h2>
 * <p>继承 {@link GuiScreen#refresh()}：清空按钮与 Inventory 后重新调用 {@link #render()}，
 * 同 tick 批量同步，客户端不产生闪烁。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcDetailGui extends GuiScreen {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int SIZE = 54;

    // 缩放边界
    private static final float SCALE_MIN = 0.1f;
    private static final float SCALE_MAX = 10.0f;
    // 转头距离边界
    private static final double TURN_DIST_MIN = 1.0;
    private static final double TURN_DIST_MAX = 64.0;
    // 交互冷却边界（毫秒）
    private static final long COOLDOWN_MIN = 0L;
    private static final long COOLDOWN_MAX = 3_600_000L;
    // 可见距离边界（0 = 服务端默认）
    private static final double VIS_DIST_MIN = 0.0;
    private static final double VIS_DIST_MAX = 128.0;

    private final WooNPCs plugin;
    private final NpcManager npcManager;
    private final NpcStorage storage;
    private final ActionManager actionManager;
    private final SkinManager skinManager;
    private final GuiManager guiManager;
    private final ChatInputManager chatInputManager;
    private final Scheduler scheduler;
    private final UUID npcId;
    /** NPC 列表快照（不可变），用于 Row 6 同级切换 */
    private final List<UUID> npcList;
    private final int currentIndex;

    /**
     * 构造 NPC 详情 GUI。
     *
     * @param plugin           插件实例
     * @param npcManager       NPC 管理器
     * @param storage          NPC 持久化存储
     * @param actionManager    动作管理器（统计动作数 + 复制时迁移 actions）
     * @param skinManager      皮肤管理器（供皮肤子 GUI 使用）
     * @param guiManager       GUI 管理器（打开/返回导航）
     * @param chatInputManager 聊天输入管理器（displayName/复制名称输入）
     * @param scheduler        调度器（传给子 NpcSkinEditGui / NpcListGui）
     * @param npcId            目标 NPC 的 UUID
     * @param npcList          NPC UUID 列表快照（用于同级切换）
     * @param currentIndex     当前 NPC 在 npcList 中的索引
     * @param parent           父级 GUI（通常为 NpcListGui）
     */
    public NpcDetailGui(@NotNull WooNPCs plugin,
                        @NotNull NpcManager npcManager,
                        @NotNull NpcStorage storage,
                        @NotNull ActionManager actionManager,
                        @NotNull SkinManager skinManager,
                        @NotNull GuiManager guiManager,
                        @NotNull ChatInputManager chatInputManager,
                        @NotNull Scheduler scheduler,
                        @NotNull UUID npcId,
                        @NotNull List<UUID> npcList,
                        int currentIndex,
                        @Nullable GuiScreen parent) {
        super(buildTitle(npcManager, npcId), SIZE, parent);
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager cannot be null");
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.actionManager = Objects.requireNonNull(actionManager, "actionManager cannot be null");
        this.skinManager = Objects.requireNonNull(skinManager, "skinManager cannot be null");
        this.guiManager = Objects.requireNonNull(guiManager, "guiManager cannot be null");
        this.chatInputManager = Objects.requireNonNull(chatInputManager, "chatInputManager cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.npcId = Objects.requireNonNull(npcId, "npcId cannot be null");
        this.npcList = List.copyOf(Objects.requireNonNull(npcList, "npcList cannot be null"));
        this.currentIndex = currentIndex;
    }

    /**
     * 构造 Inventory 标题：从 npcManager 查询 NPC 名称，查不到时用 "?" 占位。
     */
    private static String buildTitle(@NotNull NpcManager manager, @NotNull UUID id) {
        String name = manager.getById(id).map(Npc::getName).orElse("?");
        return "<dark_aqua>NPC 详情: <yellow>" + name;
    }

    // ==================== 渲染入口 ====================

    @Override
    public void render() {
        Optional<Npc> opt = npcManager.getById(npcId);
        if (opt.isEmpty()) {
            renderNpcNotFound();
            return;
        }
        Npc npc = opt.get();
        renderRow1(npc);
        renderRow2(npc);
        renderRow3(npc);
        renderRow4(npc);
        renderRow5(npc);
        renderRow6();
    }

    // ==================== Row 1: 导航 + 基础信息（slot 0-8） ====================

    private void renderRow1(Npc npc) {
        NpcData d = npc.getData();

        // [0] 返回列表
        setButton(0, GuiButton.builder(Material.BOOK)
                .name("<aqua>返回列表")
                .lore(List.of("<gray>点击返回 NPC 列表"))
                .onClick(ctx -> guiManager.goBack(ctx.player()))
                .build());

        // [1] NPC 名称（不可变）
        setButton(1, GuiButton.builder(Material.NAME_TAG)
                .name("<yellow>" + npc.getName())
                .lore(List.of(
                        "<gray>名称（不可变）",
                        "<dark_gray>删除重建可改名"
                ))
                .build());

        // [2] 位置（点击：NPC 移动到玩家位置）
        Location loc = d.location();
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "?";
        setButton(2, GuiButton.builder(Material.MAP)
                .name("<aqua>位置")
                .lore(List.of(
                        "<gray>世界: <yellow>" + worldName,
                        "<gray>坐标: <aqua>" + formatLocation(loc),
                        "<dark_gray>点击: NPC 移动到此玩家位置"
                ))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    npc.setLocation(p.getLocation());
                    storage.save(npc);
                    p.sendMessage(MM.deserialize("<green>NPC 已移动到你身边。"));
                    refresh();
                })
                .build());

        // [3] 显示名（点击：聊天输入）
        // 注：spec 描述"输入空字符串清除 displayName"，但 ChatInputManager 当前不接受空输入（重试）。
        // 此处约定关键字 "none" / "无" 清除显示名。
        String displayName = d.displayName();
        String dnDisplay = displayName == null ? "<gray><无>" : displayName;
        setButton(3, GuiButton.builder(Material.OAK_SIGN)
                .name("<aqua>显示名: <reset>" + dnDisplay)
                .lore(List.of(
                        "<gray>点击输入新显示名",
                        "<gray>支持 MiniMessage 格式",
                        "<dark_gray>输入 none 或 无 清除显示名"
                ))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    guiManager.closeGui(p);
                    chatInputManager.requestInput(p,
                            "<aqua>请输入显示名（MiniMessage 格式，输入 cancel 取消，输入 none 清除）",
                            ChatInputManager.InputType.DISPLAY_NAME,
                            input -> {
                                if (input.equalsIgnoreCase("none") || input.equals("无")) {
                                    npc.setDisplayName(null);
                                } else {
                                    npc.setDisplayName(input);
                                }
                                storage.save(npc);
                                p.sendMessage(MM.deserialize("<green>显示名已更新。"));
                                guiManager.openGui(p, this);
                            });
                })
                .build());

        // [4] 头像预览（PLAYER_HEAD，自定义皮肤时设置 PlayerProfile）
        GuiButton.Builder headBuilder = GuiButton.builder(Material.PLAYER_HEAD)
                .name("<aqua>头像预览")
                .lore(List.of(
                        "<gray>皮肤: <aqua>" + (d.skin().isDefault() ? "默认" : "自定义"),
                        "<dark_gray>texture: " + skinPreview(d.skin())
                ));
        if (!d.skin().isDefault()) {
            headBuilder.metaModifier(meta -> applyHeadProfile(meta, npc, d.skin()));
        }
        setButton(4, headBuilder.build());

        // [5] 状态信息
        int dirtyCount = d.dirtyFields().size();
        int visibleCount = npc.getVisiblePlayerCount();
        setButton(5, GuiButton.builder(Material.CLOCK)
                .name("<aqua>状态信息")
                .lore(List.of(
                        "<gray>实体ID: <yellow>" + npc.getEntityId(),
                        "<gray>dirty 字段数: <yellow>" + dirtyCount,
                        "<gray>可见玩家数: <yellow>" + visibleCount
                ))
                .build());

        // [6, 7] 背景
        setButton(6, backgroundButton());
        setButton(7, backgroundButton());

        // [8] 删除 NPC（弹出 NpcConfirmGui 确认对话框）
        setButton(8, GuiButton.builder(Material.BARRIER)
                .name("<red>删除 NPC")
                .lore(List.of(
                        "<gray>此操作不可逆",
                        "<dark_gray>点击弹出确认对话框"
                ))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    p.closeInventory();
                    guiManager.openGui(p, NpcConfirmGui.create(
                            "确认删除 NPC <yellow>" + npc.getName() + "<gray>？\n<red>此操作不可逆",
                            confirmed -> {
                                if (confirmed) {
                                    UUID id = npc.getId();
                                    actionManager.clearNpc(id);
                                    npcManager.remove(id);
                                    storage.delete(id);
                                    p.sendMessage(MM.deserialize("<dark_gray>[<aqua>WooNPCs<dark_gray>] <red>NPC <yellow>" + npc.getName() + " <red>已删除"));
                                    // 删除后构造新的 NpcListGui（不复用 parent，避免持有已删除 NPC 引用）
                                    guiManager.openGui(p, new NpcListGui(plugin, npcManager, storage, actionManager, skinManager, guiManager, chatInputManager, scheduler, p));
                                } else {
                                    // 取消时重新打开 NpcDetailGui 让用户继续操作
                                    guiManager.openGui(p, new NpcDetailGui(plugin, npcManager, storage, actionManager, skinManager, guiManager, chatInputManager, scheduler, npc.getId(), npcList, currentIndex, parent));
                                }
                            },
                            this
                    ));
                })
                .build());
    }

    // ==================== Row 2: 外观设置（slot 9-17） ====================

    private void renderRow2(Npc npc) {
        NpcData d = npc.getData();

        // [9] glowColor cycle
        GlowingColor glow = d.glowColor();
        setButton(9, GuiButton.builder(Material.GLOWSTONE)
                .name("<aqua>发光色: <yellow>" + glow.name())
                .lore(List.of(
                        "<gray>左键: 下一色",
                        "<gray>右键: 上一色",
                        "<gray>Shift+左键: 重置 NONE"
                ))
                .onClick(ctx -> {
                    GlowingColor newColor;
                    ClickType ct = ctx.clickType();
                    if (ct == ClickType.LEFT) {
                        newColor = cycleEnum(GlowingColor.values(), glow, true);
                    } else if (ct == ClickType.RIGHT) {
                        newColor = cycleEnum(GlowingColor.values(), glow, false);
                    } else if (ct == ClickType.SHIFT_LEFT) {
                        newColor = GlowingColor.NONE;
                    } else {
                        return;
                    }
                    npc.setGlowColor(newColor);
                    storage.save(npc);
                    refresh();
                })
                .build());

        // [10] pose cycle
        NpcPose pose = d.pose();
        setButton(10, GuiButton.builder(Material.ARMOR_STAND)
                .name("<aqua>姿势: <yellow>" + pose.name())
                .lore(List.of(
                        "<gray>左键: 下一姿势",
                        "<gray>右键: 上一姿势",
                        "<gray>Shift+左键: 重置 STANDING"
                ))
                .onClick(ctx -> {
                    NpcPose newPose;
                    ClickType ct = ctx.clickType();
                    if (ct == ClickType.LEFT) {
                        newPose = cycleEnum(NpcPose.values(), pose, true);
                    } else if (ct == ClickType.RIGHT) {
                        newPose = cycleEnum(NpcPose.values(), pose, false);
                    } else if (ct == ClickType.SHIFT_LEFT) {
                        newPose = NpcPose.STANDING;
                    } else {
                        return;
                    }
                    npc.setPose(newPose);
                    storage.save(npc);
                    refresh();
                })
                .build());

        // [11] scale 数字调整
        float scale = d.scale();
        setButton(11, GuiButton.builder(Material.FEATHER)
                .name("<aqua>缩放: <yellow>" + String.format(Locale.ROOT, "%.1f", scale))
                .lore(List.of(
                        "<gray>左键: +0.1",
                        "<gray>右键: -0.1",
                        "<gray>Shift+左键: +1.0",
                        "<gray>Shift+右键: -1.0",
                        "<dark_gray>范围: 0.1 ~ 10.0"
                ))
                .onClick(ctx -> {
                    float delta;
                    ClickType ct = ctx.clickType();
                    if (ct == ClickType.LEFT) delta = 0.1f;
                    else if (ct == ClickType.RIGHT) delta = -0.1f;
                    else if (ct == ClickType.SHIFT_LEFT) delta = 1.0f;
                    else if (ct == ClickType.SHIFT_RIGHT) delta = -1.0f;
                    else return;
                    float newScale = applyScaleChange(scale, delta);
                    npc.setScale(newScale);
                    storage.save(npc);
                    refresh();
                })
                .build());

        // [12] effects 入口 → 打开 NpcEffectsEditGui
        int effectCount = d.effects().size();
        setButton(12, GuiButton.builder(Material.ENDER_EYE)
                .name("<aqua>效果 (<yellow>" + effectCount + "<aqua>)")
                .lore(buildEffectLore(d.effects()))
                .onClick(ctx -> {
                    ctx.player().closeInventory();
                    guiManager.openGui(ctx.player(), new NpcEffectsEditGui(npcManager, storage, guiManager, npc.getId(), this));
                })
                .build());

        // [13-17] 背景
        for (int s = 13; s <= 17; s++) {
            setButton(s, backgroundButton());
        }
    }

    // ==================== Row 3: 行为设置（slot 18-26） ====================

    private void renderRow3(Npc npc) {
        NpcData d = npc.getData();

        // [18] showInTab 切换
        boolean tab = d.showInTab();
        setButton(18, GuiButton.builder(Material.PAPER)
                .name("<aqua>Tab 显示: <yellow>" + (tab ? "启用" : "禁用"))
                .lore(List.of("<gray>左键: 切换"))
                .onClick(ctx -> {
                    npc.setShowInTab(!tab);
                    storage.save(npc);
                    refresh();
                })
                .build());

        // [19] collidable 切换
        boolean col = d.collidable();
        setButton(19, GuiButton.builder(Material.SLIME_BALL)
                .name("<aqua>碰撞: <yellow>" + (col ? "启用" : "禁用"))
                .lore(List.of("<gray>左键: 切换"))
                .onClick(ctx -> {
                    npc.setCollidable(!col);
                    storage.save(npc);
                    refresh();
                })
                .build());

        // [20] turnToPlayer 切换
        boolean turn = d.turnToPlayer();
        setButton(20, GuiButton.builder(Material.COMPASS)
                .name("<aqua>转头跟随: <yellow>" + (turn ? "启用" : "禁用"))
                .lore(List.of("<gray>左键: 切换"))
                .onClick(ctx -> {
                    npc.setTurnToPlayer(!turn);
                    storage.save(npc);
                    refresh();
                })
                .build());

        // [21] turnToPlayerDistance 数字调整
        double dist = d.turnToPlayerDistance();
        setButton(21, GuiButton.builder(Material.RECOVERY_COMPASS)
                .name("<aqua>转头距离: <yellow>" + String.format(Locale.ROOT, "%.1f", dist))
                .lore(List.of(
                        "<gray>左键: +1",
                        "<gray>右键: -1",
                        "<gray>Shift+左键: +5",
                        "<gray>Shift+右键: -5",
                        "<dark_gray>范围: 1 ~ 64"
                ))
                .onClick(ctx -> {
                    double delta;
                    ClickType ct = ctx.clickType();
                    if (ct == ClickType.LEFT) delta = 1;
                    else if (ct == ClickType.RIGHT) delta = -1;
                    else if (ct == ClickType.SHIFT_LEFT) delta = 5;
                    else if (ct == ClickType.SHIFT_RIGHT) delta = -5;
                    else return;
                    double newDist = applyNumberChange(dist, delta, TURN_DIST_MIN, TURN_DIST_MAX);
                    npc.setTurnToPlayerDistance(newDist);
                    storage.save(npc);
                    refresh();
                })
                .build());

        // [22] interactionCooldown 数字调整
        long cd = d.interactionCooldown();
        setButton(22, GuiButton.builder(Material.CLOCK)
                .name("<aqua>冷却: <yellow>" + cd + " ms")
                .lore(List.of(
                        "<gray>左键: +1000 ms",
                        "<gray>右键: -1000 ms",
                        "<gray>Shift+左键: +10000 ms",
                        "<gray>Shift+右键: -10000 ms",
                        "<dark_gray>范围: 0 ~ 3600000 ms"
                ))
                .onClick(ctx -> {
                    long delta;
                    ClickType ct = ctx.clickType();
                    if (ct == ClickType.LEFT) delta = 1000L;
                    else if (ct == ClickType.RIGHT) delta = -1000L;
                    else if (ct == ClickType.SHIFT_LEFT) delta = 10000L;
                    else if (ct == ClickType.SHIFT_RIGHT) delta = -10000L;
                    else return;
                    long newCd = (long) applyNumberChange(cd, delta, COOLDOWN_MIN, COOLDOWN_MAX);
                    npc.setInteractionCooldown(newCd);
                    storage.save(npc);
                    refresh();
                })
                .build());

        // [23-26] 背景
        for (int s = 23; s <= 26; s++) {
            setButton(s, backgroundButton());
        }
    }

    // ==================== Row 4: 可见性 + 子 GUI 入口（slot 27-35） ====================

    private void renderRow4(Npc npc) {
        NpcData d = npc.getData();

        // [27] visibilityDistance 数字调整
        double vis = d.visibilityDistance();
        String visDisplay = vis <= 0 ? "服务端默认" : String.format(Locale.ROOT, "%.0f", vis);
        setButton(27, GuiButton.builder(Material.ENDER_PEARL)
                .name("<aqua>可见距离: <yellow>" + visDisplay)
                .lore(List.of(
                        "<gray>左键: +8",
                        "<gray>右键: -8",
                        "<gray>Shift+左键: +32",
                        "<gray>Shift+右键: -32",
                        "<dark_gray>范围: 0 (服务端默认) ~ 128"
                ))
                .onClick(ctx -> {
                    double delta;
                    ClickType ct = ctx.clickType();
                    if (ct == ClickType.LEFT) delta = 8;
                    else if (ct == ClickType.RIGHT) delta = -8;
                    else if (ct == ClickType.SHIFT_LEFT) delta = 32;
                    else if (ct == ClickType.SHIFT_RIGHT) delta = -32;
                    else return;
                    double newVis = applyNumberChange(vis, delta, VIS_DIST_MIN, VIS_DIST_MAX);
                    npc.setVisibilityDistance(newVis);
                    storage.save(npc);
                    refresh();
                })
                .build());

        // [28] permissions 入口 → 打开 NpcPermissionEditGui
        int permCount = d.visibilityPermissions().size();
        setButton(28, GuiButton.builder(Material.OAK_FENCE_GATE)
                .name("<aqua>权限 (<yellow>" + permCount + "<aqua>)")
                .lore(buildPermissionLore(d.visibilityPermissions()))
                .onClick(ctx -> {
                    ctx.player().closeInventory();
                    guiManager.openGui(ctx.player(), new NpcPermissionEditGui(npcManager, storage, guiManager, chatInputManager, npc.getId(), this));
                })
                .build());

        // [29] skin 入口 → 打开 NpcSkinEditGui
        setButton(29, GuiButton.builder(Material.GOLDEN_HELMET)
                .name("<aqua>皮肤: <yellow>" + (d.skin().isDefault() ? "默认" : "自定义"))
                .lore(List.of(
                        "<gray>texture: " + skinPreview(d.skin()),
                        "<dark_gray>点击编辑皮肤"
                ))
                .onClick(ctx -> {
                    ctx.player().closeInventory();
                    guiManager.openGui(ctx.player(), new NpcSkinEditGui(plugin, npcManager, storage, skinManager, guiManager, chatInputManager, scheduler, npc.getId(), this));
                })
                .build());

        // [30] equipment 入口 → 打开 NpcEquipmentEditGui
        int eqCount = d.equipment().size();
        setButton(30, GuiButton.builder(Material.IRON_CHESTPLATE)
                .name("<aqua>装备 (<yellow>" + eqCount + "<aqua>/6)")
                .lore(buildEquipmentLore(d.equipment()))
                .onClick(ctx -> {
                    ctx.player().closeInventory();
                    guiManager.openGui(ctx.player(), new NpcEquipmentEditGui(npcManager, storage, guiManager, npc.getId(), this));
                })
                .build());

        // [31] actions 入口 → 打开 NpcActionManageGui
        int actionCount = countActions(npc.getId());
        setButton(31, GuiButton.builder(Material.COMMAND_BLOCK)
                .name("<aqua>动作 (<yellow>" + actionCount + "<aqua>)")
                .lore(buildActionLore(npc.getId()))
                .onClick(ctx -> {
                    ctx.player().closeInventory();
                    guiManager.openGui(ctx.player(), new NpcActionManageGui(npcManager, actionManager, guiManager, chatInputManager, npc.getId(), this));
                })
                .build());

        // [32-35] 背景
        for (int s = 32; s <= 35; s++) {
            setButton(s, backgroundButton());
        }
    }

    // ==================== Row 5: 快捷操作（slot 36-44） ====================

    private void renderRow5(Npc npc) {
        // [36] 传送至此
        setButton(36, GuiButton.builder(Material.ENDER_PEARL)
                .name("<aqua>传送到 NPC")
                .lore(List.of("<gray>将你传送到 NPC 位置"))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    p.teleport(npc.getLocation());
                    p.sendMessage(MM.deserialize("<green>已传送到 NPC 位置。"));
                })
                .build());

        // [37] 移动到玩家位置
        setButton(37, GuiButton.builder(Material.COMPASS)
                .name("<aqua>NPC 移动到我")
                .lore(List.of("<gray>将 NPC 移动到你的位置"))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    npc.setLocation(p.getLocation());
                    storage.save(npc);
                    p.sendMessage(MM.deserialize("<green>NPC 已移动到你身边。"));
                    refresh();
                })
                .build());

        // [38] 复制 NPC（聊天输入新名称 → 复制 → 打开新 NPC 的 detail GUI）
        setButton(38, GuiButton.builder(Material.WRITABLE_BOOK)
                .name("<aqua>复制 NPC")
                .lore(List.of(
                        "<gray>复制此 NPC 创建新 NPC",
                        "<dark_gray>点击输入新名称"
                ))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    guiManager.closeGui(p);
                    chatInputManager.requestInput(p,
                            "<aqua>请输入新 NPC 名称（1-32 字符，仅字母数字下划线，输入 cancel 取消）",
                            ChatInputManager.InputType.NPC_NAME,
                            newName -> {
                                if (!CommandSafety.validateName(newName)) {
                                    p.sendMessage(MM.deserialize("<red>名称不合法（1-32 字符，仅字母数字下划线）。"));
                                    guiManager.openGui(p, this);
                                    return;
                                }
                                if (npcManager.containsByName(newName)) {
                                    p.sendMessage(MM.deserialize("<red>名为 <yellow>" + newName + " <red>的 NPC 已存在。"));
                                    guiManager.openGui(p, this);
                                    return;
                                }
                                Npc src = npcManager.getById(npcId).orElse(null);
                                if (src == null) {
                                    p.sendMessage(MM.deserialize("<red>原 NPC 已不存在。"));
                                    guiManager.openGui(p, this);
                                    return;
                                }
                                try {
                                    Npc created = copyNpc(src, newName);
                                    p.sendMessage(MM.deserialize("<green>已复制为 <yellow>" + newName + "<green>。"));
                                    // 切换到新 NPC 的 detail GUI；npcList 不变（保持原列表顺序）
                                    guiManager.openGui(p, new NpcDetailGui(plugin, npcManager, storage,
                                            actionManager, skinManager, guiManager, chatInputManager, scheduler,
                                            created.getId(), npcList, currentIndex, parent));
                                } catch (IllegalStateException e) {
                                    p.sendMessage(MM.deserialize("<red>复制失败: " + e.getMessage()));
                                    guiManager.openGui(p, this);
                                }
                            });
                })
                .build());

        // [39] 重置为默认（弹出 NpcConfirmGui 确认对话框）
        setButton(39, GuiButton.builder(Material.STRUCTURE_VOID)
                .name("<red>重置为默认")
                .lore(List.of(
                        "<gray>保留 id/name/location",
                        "<gray>其余字段重置为默认值",
                        "<dark_gray>点击弹出确认对话框"
                ))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    p.closeInventory();
                    guiManager.openGui(p, NpcConfirmGui.create(
                            "确认重置 NPC <yellow>" + npc.getName() + "<gray> 为默认？\n<red>将丢失外观/行为配置",
                            confirmed -> {
                                if (confirmed) {
                                    resetNpcToDefault(npc);
                                    storage.save(npc);
                                    p.sendMessage(MM.deserialize("<dark_gray>[<aqua>WooNPCs<dark_gray>] <green>NPC <yellow>" + npc.getName() + " <green>已重置为默认"));
                                    // 重新打开 NpcDetailGui 显示重置后的状态
                                    guiManager.openGui(p, new NpcDetailGui(plugin, npcManager, storage, actionManager, skinManager, guiManager, chatInputManager, scheduler, npc.getId(), npcList, currentIndex, parent));
                                } else {
                                    // 取消时重新打开 NpcDetailGui 让用户继续操作
                                    guiManager.openGui(p, new NpcDetailGui(plugin, npcManager, storage, actionManager, skinManager, guiManager, chatInputManager, scheduler, npc.getId(), npcList, currentIndex, parent));
                                }
                            },
                            this
                    ));
                })
                .build());

        // [40-44] 背景
        for (int s = 40; s <= 44; s++) {
            setButton(s, backgroundButton());
        }
    }

    // ==================== Row 6: NPC 切换（slot 45-53） ====================

    private void renderRow6() {
        // [45] 上一项
        boolean hasPrev = currentIndex > 0;
        GuiButton.Builder prev = GuiButton.builder(Material.ARROW)
                .name("<aqua>上一个 NPC")
                .lore(List.of(hasPrev ? "<gray>切换到上一个 NPC" : "<dark_gray>已是第一个 NPC"));
        if (hasPrev) {
            prev.onClick(ctx -> {
                UUID prevId = npcList.get(currentIndex - 1);
                guiManager.openGui(ctx.player(), new NpcDetailGui(plugin, npcManager, storage,
                        actionManager, skinManager, guiManager, chatInputManager, scheduler,
                        prevId, npcList, currentIndex - 1, parent));
            });
        }
        setButton(45, prev.build());

        // [46, 47, 48, 50, 51, 52] 背景
        setButton(46, backgroundButton());
        setButton(47, backgroundButton());
        setButton(48, backgroundButton());
        setButton(50, backgroundButton());
        setButton(51, backgroundButton());
        setButton(52, backgroundButton());

        // [49] 序号
        setButton(49, GuiButton.builder(Material.PAPER)
                .name("<aqua>序号: <yellow>" + (currentIndex + 1) + " <aqua>/ <yellow>" + npcList.size())
                .build());

        // [53] 下一项
        boolean hasNext = currentIndex < npcList.size() - 1;
        GuiButton.Builder next = GuiButton.builder(Material.ARROW)
                .name("<aqua>下一个 NPC")
                .lore(List.of(hasNext ? "<gray>切换到下一个 NPC" : "<dark_gray>已是最后一个 NPC"));
        if (hasNext) {
            next.onClick(ctx -> {
                UUID nextId = npcList.get(currentIndex + 1);
                guiManager.openGui(ctx.player(), new NpcDetailGui(plugin, npcManager, storage,
                        actionManager, skinManager, guiManager, chatInputManager, scheduler,
                        nextId, npcList, currentIndex + 1, parent));
            });
        }
        setButton(53, next.build());
    }

    // ==================== NPC 不存在时的错误显示 ====================

    /**
     * 渲染 NPC 不存在的错误界面：slot 13 提示，slot 22 返回列表按钮。
     */
    private void renderNpcNotFound() {
        setButton(13, GuiButton.builder(Material.BARRIER)
                .name("<red>NPC 不存在")
                .lore(List.of(
                        "<gray>该 NPC 可能已被删除",
                        "<gray>请返回列表刷新"
                ))
                .build());
        setButton(22, GuiButton.builder(Material.BOOK)
                .name("<aqua>返回列表")
                .lore(List.of("<gray>点击返回 NPC 列表"))
                .onClick(ctx -> guiManager.openGui(ctx.player(), parent))
                .build());
    }

    // ==================== 辅助方法 ====================

    /** 构造灰色玻璃背景按钮（空白名称） */
    private GuiButton backgroundButton() {
        return GuiButton.builder(Material.GRAY_STAINED_GLASS_PANE)
                .name(" ")
                .build();
    }

    /**
     * 通用枚举循环切换。
     *
     * @param values  枚举值数组（{@code E.values()}）
     * @param current 当前值
     * @param forward true=下一项，false=上一项
     * @return 循环后的新值
     */
    private static <T extends Enum<T>> T cycleEnum(@NotNull T[] values, @NotNull T current, boolean forward) {
        int idx = current.ordinal();
        int n = values.length;
        int newIdx = forward ? (idx + 1) % n : (idx - 1 + n) % n;
        return values[newIdx];
    }

    /**
     * 通用数值调整：在 [min, max] 范围内对 current 加 delta，越界截断。
     *
     * @param current 当前值
     * @param delta   增量（可为负）
     * @param min     最小值
     * @param max     最大值
     * @return 调整后的值
     */
    private static double applyNumberChange(double current, double delta, double min, double max) {
        double v = current + delta;
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    /**
     * scale 专用调整：用 {@code Math.round(v * 10f) / 10f} 避免浮点累积误差。
     */
    private static float applyScaleChange(float current, float delta) {
        float v = (float) applyNumberChange(current, delta, SCALE_MIN, SCALE_MAX);
        return Math.round(v * 10f) / 10f;
    }

    /**
     * 将 NPC 的皮肤数据应用到 SkullMeta 的 PlayerProfile（Paper API）。
     * 复用自 NpcListGui 的实现。
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

    /** 皮肤 texture 前 16 字符预览（默认皮肤显示 "<默认>"） */
    private String skinPreview(SkinData skin) {
        if (skin.isDefault()) return "<默认>";
        String t = skin.texture();
        return t.length() > 16 ? t.substring(0, 16) + "..." : t;
    }

    /** 构造效果列表 lore */
    private List<String> buildEffectLore(Set<NpcEffect> effects) {
        List<String> lore = new ArrayList<>();
        if (effects.isEmpty()) {
            lore.add("<gray>无效果");
        } else {
            for (NpcEffect e : effects) {
                lore.add("<gray>- <aqua>" + e.name());
            }
        }
        lore.add("<dark_gray>点击: 编辑效果");
        return lore;
    }

    /** 构造权限列表 lore */
    private List<String> buildPermissionLore(Set<String> perms) {
        List<String> lore = new ArrayList<>();
        if (perms.isEmpty()) {
            lore.add("<gray>无限制（所有人可见）");
        } else {
            for (String p : perms) {
                lore.add("<gray>- <aqua>" + p);
            }
        }
        lore.add("<dark_gray>点击: 编辑权限");
        return lore;
    }

    /** 构造装备槽位状态 lore */
    private List<String> buildEquipmentLore(Map<NpcEquipmentSlot, ItemStack> eq) {
        List<String> lore = new ArrayList<>();
        for (NpcEquipmentSlot slot : NpcEquipmentSlot.values()) {
            ItemStack item = eq.get(slot);
            String status = item == null ? "<red>空" : "<green>" + item.getType().name();
            lore.add("<gray>" + slot.name() + ": " + status);
        }
        lore.add("<dark_gray>点击: 编辑装备");
        return lore;
    }

    /** 构造动作配置 lore（按 trigger 分组） */
    private List<String> buildActionLore(UUID id) {
        List<String> lore = new ArrayList<>();
        boolean any = false;
        for (ActionTrigger t : ActionTrigger.values()) {
            List<NpcAction> actions = actionManager.getActions(id, t);
            if (actions.isEmpty()) continue;
            any = true;
            lore.add("<gray>" + t.name() + ": <aqua>" + actions.size());
        }
        if (!any) {
            lore.add("<gray>无动作");
        }
        lore.add("<dark_gray>点击: 管理动作");
        return lore;
    }

    /** 统计 NPC 在所有 trigger 下的动作总数 */
    private int countActions(UUID id) {
        int count = 0;
        for (ActionTrigger t : ActionTrigger.values()) {
            count += actionManager.getActions(id, t).size();
        }
        return count;
    }

    /** 格式化位置为 {@code x, y, z (yaw, pitch)}，保留 1 位小数 */
    private String formatLocation(Location loc) {
        return String.format(Locale.ROOT, "%.1f, %.1f, %.1f (yaw=%.1f, pitch=%.1f)",
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    /**
     * 复制 NPC：保留原 NPC 所有字段（参考 MainCommand.copy），并复制 actions
     * （遍历所有 ActionTrigger 的 getActions → setActions 到新 NPC）。
     */
    private Npc copyNpc(Npc src, String newName) {
        NpcData s = src.getData();
        Set<NpcEffect> effectsCopy = s.effects().isEmpty()
                ? EnumSet.noneOf(NpcEffect.class)
                : EnumSet.copyOf(s.effects());
        NpcData newData = NpcData.builder(UUID.randomUUID(), newName, s.location())
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
        // 复制 actions：遍历所有 trigger 的 getActions + setActions
        for (ActionTrigger trigger : ActionTrigger.values()) {
            List<NpcAction> actions = actionManager.getActions(src.getId(), trigger);
            if (!actions.isEmpty()) {
                actionManager.setActions(created.getId(), trigger, actions);
            }
        }
        return created;
    }

    /**
     * 重置 NPC 为默认值：保留 id/name/location，其余字段重置为 NpcData.Builder 默认值。
     * 通过逐字段 setter 调用，触发 NpcModifyEvent 与增量同步。
     */
    private void resetNpcToDefault(Npc npc) {
        NpcData cur = npc.getData();
        NpcData def = NpcData.builder(cur.id(), cur.name(), cur.location()).build();
        npc.setDisplayName(null);
        npc.setSkin(SkinData.defaultSkin());
        npc.setEquipment(null);
        npc.setGlowColor(def.glowColor());
        npc.setPose(def.pose());
        npc.setScale(def.scale());
        npc.setEffects(null);
        npc.setShowInTab(def.showInTab());
        npc.setCollidable(def.collidable());
        npc.setTurnToPlayer(def.turnToPlayer());
        npc.setTurnToPlayerDistance(def.turnToPlayerDistance());
        npc.setVisibilityDistance(def.visibilityDistance());
        npc.setVisibilityPermissions(null);
        npc.setInteractionCooldown(def.interactionCooldown());
    }
}
