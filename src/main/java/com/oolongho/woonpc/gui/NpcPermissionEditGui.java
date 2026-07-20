package com.oolongho.woonpc.gui;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.storage.NpcStorage;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * NPC 可见权限编辑 GUI（27 格，3 行布局）。
 *
 * <p>列出当前可见权限集合（前 9 个），支持右键删除单条、聊天输入添加新权限、
 * 一键清除全部。空权限集合表示无限制（所有人可见）。</p>
 *
 * <h2>布局</h2>
 * <pre>
 * Row 1 (0-8):   [返回][背景][背景][背景][当前权限][背景][背景][背景][背景]
 * Row 2 (9-17):  [权限1][权限2][权限3][权限4][权限5][权限6][权限7][权限8][权限9]
 * Row 3 (18-26): [添加权限][背景][背景][背景][背景][背景][全部清除][背景][背景]
 * </pre>
 *
 * <h2>限制</h2>
 * <p>仅展示前 9 个权限（GUI 单行最多 9 格）。若权限数超过 9，
 * [18] 添加按钮 lore 提示使用命令管理。</p>
 *
 * <h2>添加权限流程</h2>
 * <p>点击 [18] → 关闭 GUI → {@link ChatInputManager#requestInput} 接收权限名 →
 * 主线程回调中将新权限加入集合（{@link LinkedHashSet} 保持插入顺序）→
 * 重新打开 NpcPermissionEditGui 展示最新列表。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcPermissionEditGui extends GuiScreen {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int SIZE = 27;
    /** GUI 单行最多显示的权限数（slot 9-17） */
    private static final int MAX_DISPLAYED_PERMS = 9;

    private final NpcManager npcManager;
    private final NpcStorage storage;
    private final GuiManager guiManager;
    private final ChatInputManager chatInputManager;
    private final UUID npcId;

    /**
     * 构造 NPC 权限编辑 GUI。
     *
     * @param npcManager       NPC 管理器
     * @param storage          NPC 持久化存储
     * @param guiManager       GUI 管理器（返回导航 + 添加权限后重开 GUI）
     * @param chatInputManager 聊天输入管理器（接收新权限名）
     * @param npcId            目标 NPC 的 UUID
     * @param parent           父级 GUI（通常为 NpcDetailGui）
     */
    public NpcPermissionEditGui(@NotNull NpcManager npcManager,
                                @NotNull NpcStorage storage,
                                @NotNull GuiManager guiManager,
                                @NotNull ChatInputManager chatInputManager,
                                @NotNull UUID npcId,
                                @Nullable GuiScreen parent) {
        super("<dark_aqua>NPC 权限编辑", SIZE, parent);
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager cannot be null");
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.guiManager = Objects.requireNonNull(guiManager, "guiManager cannot be null");
        this.chatInputManager = Objects.requireNonNull(chatInputManager, "chatInputManager cannot be null");
        this.npcId = Objects.requireNonNull(npcId, "npcId cannot be null");
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
        Set<String> perms = npc.getData().visibilityPermissions();
        List<String> permList = new ArrayList<>(perms);

        // [0] 返回
        setButton(0, GuiButton.builder(Material.BOOK)
                .name("<yellow>返回")
                .lore(List.of("<gray>点击返回上一级"))
                .onClick(ctx -> guiManager.goBack(ctx.player()))
                .build());

        // [1-3, 5-8] 背景
        setButton(1, backgroundButton());
        setButton(2, backgroundButton());
        setButton(3, backgroundButton());
        setButton(5, backgroundButton());
        setButton(6, backgroundButton());
        setButton(7, backgroundButton());
        setButton(8, backgroundButton());

        // [4] 当前权限
        setButton(4, GuiButton.builder(Material.PAPER)
                .name("<aqua>当前权限 (<yellow>" + perms.size() + "<aqua>)")
                .lore(buildPermissionListLore(perms))
                .build());

        // [9-17] 权限按钮（前 9 个，超过部分不显示）
        for (int i = 0; i < MAX_DISPLAYED_PERMS; i++) {
            int slot = 9 + i;
            if (i < permList.size()) {
                String perm = permList.get(i);
                setButton(slot, buildPermissionButton(npc, perm));
            } else {
                setButton(slot, backgroundButton());
            }
        }

        // [18] 添加权限（关闭 GUI → 聊天输入 → 主线程回调中重开 GUI）
        setButton(18, GuiButton.builder(Material.OAK_SIGN)
                .name("<aqua>添加权限")
                .lore(buildAddPermissionLore(perms.size()))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    guiManager.closeGui(p);
                    chatInputManager.requestInput(p,
                            "<aqua>请输入权限名（如 vip.access），输入 cancel 取消",
                            ChatInputManager.InputType.PERMISSION,
                            input -> {
                                // 切回主线程后 NPC 可能已被其他线程移除，需再次校验
                                Npc current = npcManager.getById(npcId).orElse(null);
                                if (current == null) {
                                    p.sendMessage(MM.deserialize("<red>NPC 已不存在，无法添加权限。"));
                                    return;
                                }
                                // NpcData.visibilityPermissions() 返回不可修改视图，复制到 LinkedHashSet
                                Set<String> newPerms = new LinkedHashSet<>(current.getData().visibilityPermissions());
                                newPerms.add(input);
                                current.setVisibilityPermissions(newPerms);
                                storage.save(current);
                                guiManager.openGui(p, new NpcPermissionEditGui(npcManager, storage,
                                        guiManager, chatInputManager, npcId, parent));
                            });
                })
                .build());

        // [19-23] 背景
        setButton(19, backgroundButton());
        setButton(20, backgroundButton());
        setButton(21, backgroundButton());
        setButton(22, backgroundButton());
        setButton(23, backgroundButton());

        // [24] 全部清除
        setButton(24, GuiButton.builder(Material.BARRIER)
                .name("<red>全部清除")
                .lore(List.of("<gray>清空所有权限（恢复无限制）"))
                .onClick(ctx -> {
                    npc.setVisibilityPermissions(Set.of());
                    storage.save(npc);
                    refresh();
                })
                .build());

        // [25-26] 背景
        setButton(25, backgroundButton());
        setButton(26, backgroundButton());
    }

    // ==================== NPC 不存在时的错误显示 ====================

    /**
     * 渲染 NPC 不存在的错误界面：slot 13 提示，slot 22 返回按钮。
     */
    private void renderNpcNotFound() {
        setButton(13, GuiButton.builder(Material.BARRIER)
                .name("<red>NPC 不存在")
                .lore(List.of(
                        "<gray>该 NPC 可能已被删除",
                        "<gray>请返回上一级刷新"
                ))
                .build());
        setButton(22, GuiButton.builder(Material.BOOK)
                .name("<yellow>返回")
                .lore(List.of("<gray>点击返回上一级"))
                .onClick(ctx -> guiManager.openGui(ctx.player(), parent))
                .build());
    }

    // ==================== 权限按钮构建 ====================

    /**
     * 构造单个权限按钮（右键删除）。
     */
    private GuiButton buildPermissionButton(Npc npc, String perm) {
        return GuiButton.builder(Material.OAK_SIGN)
                .name("<yellow>" + perm)
                .lore(List.of("<gray>右键: 删除此权限"))
                .onClick(ctx -> {
                    if (ctx.clickType() != ClickType.RIGHT) return;
                    Set<String> newPerms = new LinkedHashSet<>(npc.getData().visibilityPermissions());
                    newPerms.remove(perm);
                    npc.setVisibilityPermissions(newPerms);
                    storage.save(npc);
                    refresh();
                })
                .build();
    }

    // ==================== 辅助方法 ====================

    /** 构造灰色玻璃背景按钮（空白名称） */
    private GuiButton backgroundButton() {
        return GuiButton.builder(Material.GRAY_STAINED_GLASS_PANE)
                .name(" ")
                .build();
    }

    /** 构造当前权限列表 lore：空集合显示"无限制"，否则逐行展示 */
    private List<String> buildPermissionListLore(Set<String> perms) {
        List<String> lore = new ArrayList<>();
        if (perms.isEmpty()) {
            lore.add("<gray>无限制");
        } else {
            for (String p : perms) {
                lore.add("<gray>- <aqua>" + p);
            }
        }
        return lore;
    }

    /** 构造添加权限按钮 lore：权限数超过 9 时提示使用命令管理 */
    private List<String> buildAddPermissionLore(int currentSize) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>点击输入新权限名");
        if (currentSize > MAX_DISPLAYED_PERMS) {
            lore.add("<red>权限数超过 9，请使用命令管理");
        }
        return lore;
    }
}
