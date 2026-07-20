package com.oolongho.woonpc.gui;

import com.oolongho.woonpc.action.ConsoleCommandAction;
import com.oolongho.woonpc.action.MessageAction;
import com.oolongho.woonpc.action.NeedPermissionAction;
import com.oolongho.woonpc.action.PlaySoundAction;
import com.oolongho.woonpc.action.PlayerCommandAction;
import com.oolongho.woonpc.action.PlayerCommandAsOpAction;
import com.oolongho.woonpc.action.SendToServerAction;
import com.oolongho.woonpc.action.WaitAction;
import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.api.actions.ActionManager;
import com.oolongho.woonpc.api.actions.ActionTrigger;
import com.oolongho.woonpc.api.actions.NpcAction;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * NPC 动作管理 GUI（54 格，6 行布局）。
 *
 * <p>展示与管理单个 NPC 在不同 {@link ActionTrigger} 下的动作链。
 * 因 {@link ActionManager} 仅提供 setActions（替换式覆盖）+ getActions，
 * 本 GUI 采用「getActions → 修改列表 → setActions」模式实现增删改查。</p>
 *
 * <h2>布局</h2>
 * <pre>
 * Row 1 (0-8):   [返回][LEFT_CLICK][RIGHT_CLICK][ANY_CLICK][CUSTOM][背景][背景][背景][动作数]
 * Row 2-4 (9-35): 动作列表（每页 27 项）
 * Row 5 (36-44): [添加动作][上移][下移][编辑][删除][背景][背景][背景][背景]
 * Row 6 (45-53): [上一页][背景][背景][背景][页码][背景][背景][背景][下一页]
 * </pre>
 *
 * <h2>类型选择视图</h2>
 * <p>点击「添加动作」后，{@link #typeSelectMode} 切换为 true，在同 GUI 内切换到类型选择视图：
 * slot 9-16 显示 8 种动作类型按钮，点击后关闭 GUI → 聊天输入参数 → 构造动作 →
 * setActions → 重新打开动作管理界面。此为同 GUI 内视图切换，不增加 GUI 层级。</p>
 *
 * <h2>状态</h2>
 * <ul>
 *   <li>{@link #currentTrigger}：当前编辑的 trigger（默认 LEFT_CLICK）</li>
 *   <li>{@link #page}：动作列表分页（0-based）</li>
 *   <li>{@link #selectedIndex}：选中的动作全局索引（-1 未选中）</li>
 *   <li>{@link #typeSelectMode}：是否在类型选择视图</li>
 * </ul>
 *
 * <h2>持久化</h2>
 * <p>所有修改动作的操作（添加/删除/编辑/上移/下移）会同步到 {@link ActionManager} 内存状态，
 * 由 AutoSaveTask 周期保存到 {@code actions.yml}。</p>
 *
 * <h2>无闪烁刷新</h2>
 * <p>继承 {@link GuiScreen#refresh()}：清空按钮与 Inventory 后重新调用 {@link #render()}，
 * 同 tick 批量同步，客户端不产生闪烁。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcActionManageGui extends GuiScreen {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int SIZE = 54;
    /** 每页可显示的动作数量（Row 2-4，共 27 格） */
    private static final int ITEMS_PER_PAGE = 27;
    /** 动作列表起始槽位（跳过 Row 1） */
    private static final int START_SLOT = 9;

    private final NpcManager npcManager;
    private final ActionManager actionManager;
    private final GuiManager guiManager;
    private final ChatInputManager chatInputManager;
    private final UUID npcId;

    /** 当前编辑的 trigger（默认 LEFT_CLICK） */
    private ActionTrigger currentTrigger = ActionTrigger.LEFT_CLICK;
    /** 当前页码（0-based） */
    private int page = 0;
    /** 当前选中的动作全局索引（-1 表示未选中） */
    private int selectedIndex = -1;
    /** 是否在「类型选择视图」中（同 GUI 内视图切换，不增加层级） */
    private boolean typeSelectMode = false;

    /**
     * 构造动作管理 GUI。
     *
     * @param npcManager       NPC 管理器
     * @param actionManager    动作管理器
     * @param guiManager       GUI 管理器（打开/返回导航）
     * @param chatInputManager 聊天输入管理器（用于输入动作参数）
     * @param npcId            目标 NPC 的 UUID
     * @param parent           父级 GUI（通常为 NpcDetailGui）
     */
    public NpcActionManageGui(@NotNull NpcManager npcManager,
                              @NotNull ActionManager actionManager,
                              @NotNull GuiManager guiManager,
                              @NotNull ChatInputManager chatInputManager,
                              @NotNull UUID npcId,
                              @Nullable GuiScreen parent) {
        super(buildTitle(npcManager, npcId), SIZE, parent);
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager cannot be null");
        this.actionManager = Objects.requireNonNull(actionManager, "actionManager cannot be null");
        this.guiManager = Objects.requireNonNull(guiManager, "guiManager cannot be null");
        this.chatInputManager = Objects.requireNonNull(chatInputManager, "chatInputManager cannot be null");
        this.npcId = Objects.requireNonNull(npcId, "npcId cannot be null");
    }

    /**
     * 构造 Inventory 标题：从 npcManager 查询 NPC 名称，查不到时用 "?" 占位。
     */
    private static String buildTitle(@NotNull NpcManager manager, @NotNull UUID id) {
        String name = manager.getById(id).map(Npc::getName).orElse("?");
        return "<dark_aqua>动作管理: <yellow>" + name;
    }

    // ==================== 渲染入口 ====================

    @Override
    public void render() {
        Optional<Npc> opt = npcManager.getById(npcId);
        if (opt.isEmpty()) {
            renderNpcNotFound();
            return;
        }
        if (typeSelectMode) {
            renderTypeSelect();
            return;
        }
        renderRow1();
        renderActionList();
        renderRow5();
        renderRow6();
    }

    // ==================== Row 1: trigger 切换（slot 0-8） ====================

    private void renderRow1() {
        // [0] 返回详情
        setButton(0, GuiButton.builder(Material.BOOK)
                .name("<aqua>返回详情")
                .lore(List.of("<gray>点击返回 NPC 详情"))
                .onClick(ctx -> guiManager.goBack(ctx.player()))
                .build());

        // [1-4] trigger 切换按钮（当前选中 glow）
        renderTriggerButton(1, ActionTrigger.LEFT_CLICK, Material.IRON_SWORD);
        renderTriggerButton(2, ActionTrigger.RIGHT_CLICK, Material.GOLDEN_SWORD);
        renderTriggerButton(3, ActionTrigger.ANY_CLICK, Material.DIAMOND_SWORD);
        renderTriggerButton(4, ActionTrigger.CUSTOM, Material.NETHERITE_SWORD);

        // [5-7] 背景
        setButton(5, backgroundButton());
        setButton(6, backgroundButton());
        setButton(7, backgroundButton());

        // [8] 当前 trigger 动作数摘要
        int count = actionManager.getActions(npcId, currentTrigger).size();
        setButton(8, GuiButton.builder(Material.PAPER)
                .name("<aqua>当前 trigger 动作数: <yellow>" + count)
                .lore(List.of(
                        "<gray>当前: <aqua>" + currentTrigger.name(),
                        "<gray>动作数: <aqua>" + count
                ))
                .build());
    }

    /**
     * 渲染单个 trigger 切换按钮：name 显示 trigger 名，lore 显示动作数，当前选中 glow。
     */
    private void renderTriggerButton(int slot, ActionTrigger trigger, Material material) {
        boolean active = currentTrigger == trigger;
        int count = actionManager.getActions(npcId, trigger).size();
        GuiButton.Builder builder = GuiButton.builder(material)
                .name("<aqua>" + trigger.name())
                .lore(List.of(
                        "<gray>动作数: <aqua>" + count,
                        active ? "<green>当前选中" : "<dark_gray>点击切换到此 trigger"
                ));
        if (active) {
            builder.glow();
        } else {
            builder.onClick(ctx -> {
                currentTrigger = trigger;
                page = 0;
                selectedIndex = -1;
                refresh();
            });
        }
        setButton(slot, builder.build());
    }

    // ==================== Row 2-4: 动作列表（slot 9-35） ====================

    private void renderActionList() {
        List<NpcAction> actions = new ArrayList<>(actionManager.getActions(npcId, currentTrigger));
        int total = actions.size();
        int totalPages = Math.max(1, (total + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        // 分页边界保护：page 越界时回退（动作被删除后可能发生）
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, total);
        for (int i = start; i < end; i++) {
            int slot = START_SLOT + (i - start);
            setButton(slot, buildActionButton(actions.get(i), i));
        }
        // 剩余槽位保持空（Inventory 默认空，不放置按钮）
    }

    /**
     * 构造单个动作按钮：name 显示 {@code #index | typeId}，lore 显示参数摘要，左键选中，右键弹出 NpcConfirmGui 确认删除。
     *
     * <p>Material 根据 typeId 选择（参考 MainCommand.buildAction 的类型列表）。
     * 若 index == selectedIndex 则 glow 高亮。参数摘要通过 {@link NpcAction#argsSummary} 获取，
     * 截断到 40 字符（避免 lore 行过长）。</p>
     */
    private GuiButton buildActionButton(NpcAction action, int index) {
        String summary = action.argsSummary();
        List<String> lore = new ArrayList<>();
        if (!summary.isEmpty()) {
            String truncated = summary.length() > 40 ? summary.substring(0, 37) + "..." : summary;
            lore.add("<gray>参数: <aqua>" + truncated);
        }
        lore.add("<gray>左键: 选中");
        lore.add("<gray>右键: 删除（确认）");
        lore.add("<dark_gray>编辑请用 Row 5 的「编辑参数」按钮");
        GuiButton.Builder builder = GuiButton.builder(materialForType(action.typeId()))
                .name("<yellow>#" + index + " <gray>| <aqua>" + action.typeId())
                .lore(lore);
        if (index == selectedIndex) {
            builder.glow();
        }
        builder.onClick(ctx -> {
            ClickType ct = ctx.clickType();
            if (ct == ClickType.LEFT) {
                selectedIndex = index;
                refresh();
            } else if (ct == ClickType.RIGHT) {
                Player p = ctx.player();
                p.closeInventory();
                guiManager.openGui(p, NpcConfirmGui.create(
                        "<red>确认删除 <yellow>#" + index + " <red>动作？\n<gray>类型: <aqua>" + action.typeId(),
                        confirmed -> {
                            if (confirmed) {
                                deleteAction(p, index);
                            } else {
                                guiManager.openGui(p, NpcActionManageGui.this);
                            }
                        },
                        this
                ));
            }
        });
        return builder.build();
    }

    // ==================== Row 5: 操作（slot 36-44） ====================

    private void renderRow5() {
        // [36] 添加动作 → 切换到类型选择视图
        setButton(36, GuiButton.builder(Material.EMERALD)
                .name("<aqua>添加动作")
                .lore(List.of("<gray>点击选择动作类型"))
                .onClick(ctx -> {
                    typeSelectMode = true;
                    refresh();
                })
                .build());

        // [37] 上移
        setButton(37, GuiButton.builder(Material.ARROW)
                .name("<yellow>上移")
                .lore(List.of("<gray>将选中的动作上移一格"))
                .onClick(ctx -> moveSelected(ctx.player(), -1))
                .build());

        // [38] 下移
        setButton(38, GuiButton.builder(Material.ARROW)
                .name("<yellow>下移")
                .lore(List.of("<gray>将选中的动作下移一格"))
                .onClick(ctx -> moveSelected(ctx.player(), 1))
                .build());

        // [39] 编辑参数（需先选中动作）
        setButton(39, GuiButton.builder(Material.WRITABLE_BOOK)
                .name("<aqua>编辑参数")
                .lore(List.of("<gray>编辑选中动作的参数"))
                .onClick(ctx -> editSelected(ctx.player()))
                .build());

        // [40] 删除选中（弹出 NpcConfirmGui 确认对话框）
        setButton(40, GuiButton.builder(Material.BARRIER)
                .name("<red>删除选中")
                .lore(List.of("<gray>删除当前选中的动作"))
                .onClick(ctx -> {
                    if (selectedIndex < 0) {
                        ctx.player().sendMessage(MM.deserialize("<red>请先选择动作。"));
                        return;
                    }
                    Player p = ctx.player();
                    final int idx = selectedIndex;
                    p.closeInventory();
                    guiManager.openGui(p, NpcConfirmGui.create(
                            "<red>确认删除 <yellow>#" + idx + " <red>动作？",
                            confirmed -> {
                                if (confirmed) {
                                    deleteAction(p, idx);
                                } else {
                                    guiManager.openGui(p, NpcActionManageGui.this);
                                }
                            },
                            this
                    ));
                })
                .build());

        // [41-44] 背景
        for (int s = 41; s <= 44; s++) {
            setButton(s, backgroundButton());
        }
    }

    // ==================== Row 6: 分页（slot 45-53） ====================

    private void renderRow6() {
        int total = actionManager.getActions(npcId, currentTrigger).size();
        int totalPages = Math.max(1, (total + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        boolean hasPrev = page > 0;
        boolean hasNext = page < totalPages - 1;

        // [45] 上一页
        GuiButton.Builder prev = GuiButton.builder(Material.ARROW)
                .name("<aqua>上一页")
                .lore(List.of(hasPrev ? "<gray>点击翻到上一页" : "<dark_gray>已是第一页"));
        if (hasPrev) {
            prev.onClick(ctx -> {
                page--;
                refresh();
            });
        }
        setButton(45, prev.build());

        // [46-48, 50-52] 背景
        setButton(46, backgroundButton());
        setButton(47, backgroundButton());
        setButton(48, backgroundButton());
        setButton(50, backgroundButton());
        setButton(51, backgroundButton());
        setButton(52, backgroundButton());

        // [49] 页码
        setButton(49, GuiButton.builder(Material.PAPER)
                .name("<aqua>第 <yellow>" + (page + 1) + "<aqua>/<yellow>" + totalPages + " <aqua>页")
                .lore(List.of("<gray>动作总数: <aqua>" + total))
                .build());

        // [53] 下一页
        GuiButton.Builder next = GuiButton.builder(Material.ARROW)
                .name("<aqua>下一页")
                .lore(List.of(hasNext ? "<gray>点击翻到下一页" : "<dark_gray>已是最后一页"));
        if (hasNext) {
            next.onClick(ctx -> {
                page++;
                refresh();
            });
        }
        setButton(53, next.build());
    }

    // ==================== 类型选择视图（typeSelectMode=true） ====================

    /**
     * 渲染类型选择视图：slot 0 返回，slot 4 标题，slot 9-16 显示 8 种动作类型按钮。
     *
     * <p>此为同 GUI 内视图切换，不增加 GUI 层级。
     * 点击类型按钮 → 关闭 GUI → 聊天输入参数 → 构造动作 → setActions → 重新打开。</p>
     */
    private void renderTypeSelect() {
        // [0] 返回动作列表
        setButton(0, GuiButton.builder(Material.BOOK)
                .name("<aqua>返回动作列表")
                .lore(List.of("<gray>点击返回动作管理"))
                .onClick(ctx -> {
                    typeSelectMode = false;
                    refresh();
                })
                .build());

        // [4] 标题
        setButton(4, GuiButton.builder(Material.PAPER)
                .name("<aqua>选择动作类型")
                .lore(List.of("<gray>点击下方按钮添加对应类型动作"))
                .build());

        // [9-16] 8 个动作类型按钮
        renderTypeButton(9, "message", Material.PAPER, ChatInputManager.InputType.ACTION_VALUE,
                "消息内容（MiniMessage 格式）");
        renderTypeButton(10, "console_command", Material.COMMAND_BLOCK, ChatInputManager.InputType.ACTION_COMMAND,
                "命令内容（不需前导 /）");
        renderTypeButton(11, "player_command", Material.COMMAND_BLOCK_MINECART, ChatInputManager.InputType.ACTION_COMMAND,
                "命令内容（不需前导 /）");
        renderTypeButton(12, "player_command_as_op", Material.REPEATING_COMMAND_BLOCK, ChatInputManager.InputType.ACTION_COMMAND,
                "命令内容（不需前导 /，将以 OP 身份执行）");
        renderTypeButton(13, "play_sound", Material.NOTE_BLOCK, ChatInputManager.InputType.SOUND_NAME,
                "音效名 [volume] [pitch]（如 ENTITY_EXPERIENCE_ORB_PICKUP 1.0 1.0）");
        renderTypeButton(14, "wait", Material.CLOCK, ChatInputManager.InputType.ACTION_VALUE,
                "等待 tick 数（整数，1 秒 = 20 tick）");
        renderTypeButton(15, "need_permission", Material.OAK_FENCE_GATE, ChatInputManager.InputType.PERMISSION,
                "权限节点（如 woonpc.admin）");
        renderTypeButton(16, "send_to_server", Material.ENDER_EYE, ChatInputManager.InputType.ACTION_VALUE,
                "目标服务器名");
    }

    /**
     * 渲染单个动作类型按钮：点击后关闭 GUI → 聊天输入参数 → 构造动作 → setActions → 重新打开。
     */
    private void renderTypeButton(int slot, String typeId, Material material,
                                  ChatInputManager.InputType inputType, String argHint) {
        setButton(slot, GuiButton.builder(material)
                .name("<aqua>" + typeId)
                .lore(List.of(
                        "<gray>参数: <aqua>" + argHint,
                        "<dark_gray>点击添加此类型动作"
                ))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    guiManager.closeGui(p);
                    chatInputManager.requestInput(p,
                            "<aqua>请输入 <yellow>" + typeId + " <aqua>的参数（cancel 取消）: <gray>" + argHint,
                            inputType,
                            args -> addAction(typeId, args, p));
                })
                .build());
    }

    // ==================== NPC 不存在时的错误显示 ====================

    /**
     * 渲染 NPC 不存在的错误界面：slot 13 提示，slot 22 返回详情按钮。
     */
    private void renderNpcNotFound() {
        setButton(13, GuiButton.builder(Material.BARRIER)
                .name("<red>NPC 不存在")
                .lore(List.of(
                        "<gray>该 NPC 可能已被删除",
                        "<gray>请返回详情刷新"
                ))
                .build());
        setButton(22, GuiButton.builder(Material.BOOK)
                .name("<aqua>返回详情")
                .lore(List.of("<gray>点击返回 NPC 详情"))
                .onClick(ctx -> guiManager.openGui(ctx.player(), parent))
                .build());
    }

    // ==================== 操作逻辑 ====================

    /**
     * 删除指定索引的动作：getActions → remove → setActions + 调整 selectedIndex + A1 警告。
     *
     * @param p     操作的玩家（用于发送消息）
     * @param index 要删除的动作全局索引
     */
    private void deleteAction(Player p, int index) {
        List<NpcAction> actions = new ArrayList<>(actionManager.getActions(npcId, currentTrigger));
        if (index < 0 || index >= actions.size()) {
            return;
        }
        actions.remove(index);
        actionManager.setActions(npcId, currentTrigger, actions);
        // 调整 selectedIndex：删除的是选中项 → 重置；删除的是选中项之前的 → 前移
        if (selectedIndex == index) {
            selectedIndex = -1;
        } else if (selectedIndex > index) {
            selectedIndex--;
        }
        p.sendMessage(MM.deserialize("<green>动作已删除。"));
        refresh();
    }

    /**
     * 移动选中动作：getActions → swap → setActions + 更新 selectedIndex + A1 警告。
     *
     * @param p         操作的玩家
     * @param direction 移动方向：-1 上移，+1 下移
     */
    private void moveSelected(Player p, int direction) {
        if (selectedIndex < 0) {
            p.sendMessage(MM.deserialize("<red>请先选择动作。"));
            return;
        }
        List<NpcAction> actions = new ArrayList<>(actionManager.getActions(npcId, currentTrigger));
        if (actions.isEmpty()) {
            return;
        }
        int target = selectedIndex + direction;
        if (direction < 0 && selectedIndex <= 0) {
            p.sendMessage(MM.deserialize("<red>无法上移：已是第一个动作。"));
            return;
        }
        if (direction > 0 && selectedIndex >= actions.size() - 1) {
            p.sendMessage(MM.deserialize("<red>无法下移：已是最后一个动作。"));
            return;
        }
        // 交换 selectedIndex 与 target 位置
        NpcAction tmp = actions.get(selectedIndex);
        actions.set(selectedIndex, actions.get(target));
        actions.set(target, tmp);
        actionManager.setActions(npcId, currentTrigger, actions);
        selectedIndex = target;
        refresh();
    }

    /**
     * 编辑选中动作的参数：关闭 GUI → 聊天输入新参数 → 构造新 NpcAction 替换原位置 → setActions → 重新打开。
     *
     * <p>编辑时类型保持不变（typeId 不变），仅替换参数。
     * InputType 根据 typeId 选择，与添加逻辑一致。</p>
     *
     * @param p 操作的玩家
     */
    private void editSelected(Player p) {
        if (selectedIndex < 0) {
            p.sendMessage(MM.deserialize("<red>请先选择动作。"));
            return;
        }
        List<NpcAction> actions = new ArrayList<>(actionManager.getActions(npcId, currentTrigger));
        if (selectedIndex >= actions.size()) {
            p.sendMessage(MM.deserialize("<red>选中的动作已不存在。"));
            selectedIndex = -1;
            refresh();
            return;
        }
        NpcAction current = actions.get(selectedIndex);
        String typeId = current.typeId();
        ChatInputManager.InputType inputType = inputTypeForTypeId(typeId);
        final int idx = selectedIndex;
        guiManager.closeGui(p);
        chatInputManager.requestInput(p,
                "<aqua>请输入 <yellow>" + typeId + " <aqua>的新参数（cancel 取消）",
                inputType,
                newArgs -> {
                    NpcAction newAction = buildAction(typeId, newArgs);
                    if (newAction == null) {
                        p.sendMessage(MM.deserialize("<red>参数无效，编辑失败。"));
                        guiManager.openGui(p, this);
                        return;
                    }
                    List<NpcAction> list = new ArrayList<>(actionManager.getActions(npcId, currentTrigger));
                    if (idx < list.size()) {
                        list.set(idx, newAction);
                        actionManager.setActions(npcId, currentTrigger, list);
                        p.sendMessage(MM.deserialize("<green>动作已更新。"));
                    }
                    guiManager.openGui(p, this);
                });
    }

    /**
     * 添加动作：构造 NpcAction → 添加到 actions 列表末尾 → setActions → 重新打开。
     *
     * <p>从类型选择视图触发，回调中重置 typeSelectMode=false 并重新打开 GUI。
     * 若参数无效发送错误消息并重新打开。</p>
     *
     * @param typeId 动作类型 ID
     * @param arg    动作参数字符串
     * @param p      操作的玩家
     */
    private void addAction(String typeId, String arg, Player p) {
        NpcAction newAction = buildAction(typeId, arg);
        if (newAction == null) {
            p.sendMessage(MM.deserialize("<red>参数无效，添加失败。"));
            typeSelectMode = false;
            guiManager.openGui(p, this);
            return;
        }
        List<NpcAction> actions = new ArrayList<>(actionManager.getActions(npcId, currentTrigger));
        actions.add(newAction);
        actionManager.setActions(npcId, currentTrigger, actions);
        p.sendMessage(MM.deserialize("<green>已添加 <aqua>" + typeId + " <green>动作。"));
        typeSelectMode = false;
        guiManager.openGui(p, this);
    }

    // ==================== 辅助方法 ====================

    /** 构造黄绿色玻璃背景按钮（空白名称） */
    private GuiButton backgroundButton() {
        return GuiButton.builder(Material.LIME_STAINED_GLASS_PANE)
                .name(" ")
                .build();
    }

    /**
     * 根据 typeId 返回对应的展示材质（参考 MainCommand.buildAction 的类型列表）。
     */
    private Material materialForType(String typeId) {
        return switch (typeId) {
            case "message" -> Material.PAPER;
            case "console_command" -> Material.COMMAND_BLOCK;
            case "player_command" -> Material.COMMAND_BLOCK_MINECART;
            case "player_command_as_op" -> Material.REPEATING_COMMAND_BLOCK;
            case "play_sound" -> Material.NOTE_BLOCK;
            case "wait" -> Material.CLOCK;
            case "need_permission" -> Material.OAK_FENCE_GATE;
            case "send_to_server" -> Material.ENDER_EYE;
            default -> Material.BARRIER;
        };
    }

    /**
     * 根据 typeId 返回对应的聊天输入类型（与添加逻辑一致）。
     */
    private ChatInputManager.InputType inputTypeForTypeId(String typeId) {
        return switch (typeId) {
            case "console_command", "player_command", "player_command_as_op" ->
                    ChatInputManager.InputType.ACTION_COMMAND;
            case "play_sound" -> ChatInputManager.InputType.SOUND_NAME;
            case "need_permission" -> ChatInputManager.InputType.PERMISSION;
            default -> ChatInputManager.InputType.ACTION_VALUE;
        };
    }

    /**
     * 根据类型与参数构造 NpcAction（复用自 MainCommand.buildAction）。
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
