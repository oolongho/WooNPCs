package com.oolongho.woonpc.gui;

import org.bukkit.Material;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 通用确认对话框 GUI（27 格，3 行布局）。
 *
 * <p>用于危险操作前的二次确认。回调接收布尔值：true=确认，false=取消。
 * 不持有 GuiManager 引用，onClick 仅执行 callback + closeInventory，
 * 由 callback 内部决定后续操作（如 openGui 返回 parent）。</p>
 *
 * <h2>布局</h2>
 * <pre>
 * Row 1 (0-8):   [取消][背景][背景][背景][确认信息][背景][背景][背景][背景]
 * Row 2 (9-17):  [背景×9]
 * Row 3 (18-26): [背景][背景][背景][背景][确认][背景][背景][背景][背景]
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * guiManager.openGui(player, NpcConfirmGui.create(
 *     "确认删除 NPC " + npc.getName() + "？",
 *     confirmed -> {
 *         if (confirmed) {
 *             // 执行删除...
 *             guiManager.openGui(player, parent);
 *         }
 *     },
 *     this  // parent 为当前 GUI
 * ));
 * }</pre>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcConfirmGui extends GuiScreen {

    private static final int SIZE = 27;

    private final String message;
    private final Consumer<Boolean> callback;

    /**
     * 构造通用确认对话框。
     *
     * @param message  确认消息（可包含 \n 表示多行）
     * @param callback 回调，确认时 accept(true)，取消时 accept(false)
     * @param parent   父级 GUI（取消或确认后由 callback 决定是否返回）
     */
    public NpcConfirmGui(@NotNull String message,
                         @NotNull Consumer<Boolean> callback,
                         @Nullable GuiScreen parent) {
        super("<dark_aqua>确认操作", SIZE, parent);
        this.message = Objects.requireNonNull(message, "message cannot be null");
        this.callback = Objects.requireNonNull(callback, "callback cannot be null");
    }

    /**
     * 工厂方法：创建确认对话框。
     *
     * @param message  确认消息
     * @param callback 回调
     * @param parent   父级 GUI
     * @return NpcConfirmGui 实例
     */
    public static NpcConfirmGui create(@NotNull String message,
                                       @NotNull Consumer<Boolean> callback,
                                       @Nullable GuiScreen parent) {
        return new NpcConfirmGui(message, callback, parent);
    }

    // ==================== 渲染入口 ====================

    @Override
    public void render() {
        // [0] 取消
        setButton(0, GuiButton.builder(Material.GRAY_CONCRETE)
                .name("<yellow>取消")
                .lore(List.of("<gray>点击取消操作"))
                .onClick(ctx -> {
                    callback.accept(false);
                    ctx.player().closeInventory();
                })
                .build());

        // [1-3, 5-8] 背景
        setButton(1, backgroundButton());
        setButton(2, backgroundButton());
        setButton(3, backgroundButton());
        setButton(5, backgroundButton());
        setButton(6, backgroundButton());
        setButton(7, backgroundButton());
        setButton(8, backgroundButton());

        // [4] 确认信息（按 \n 分割为多行 lore，每行加 <gray> 前缀）
        setButton(4, GuiButton.builder(Material.PAPER)
                .name("<aqua>确认操作")
                .lore(buildMessageLore(message))
                .build());

        // [9-17] 背景
        for (int s = 9; s <= 17; s++) {
            setButton(s, backgroundButton());
        }

        // [18-21] 背景
        for (int s = 18; s <= 21; s++) {
            setButton(s, backgroundButton());
        }

        // [22] 确认
        setButton(22, GuiButton.builder(Material.RED_CONCRETE)
                .name("<red>确认")
                .lore(List.of("<gray>点击执行操作"))
                .onClick(ctx -> {
                    callback.accept(true);
                    ctx.player().closeInventory();
                })
                .build());

        // [23-26] 背景
        for (int s = 23; s <= 26; s++) {
            setButton(s, backgroundButton());
        }
    }

    // ==================== 辅助方法 ====================

    /** 构造灰色玻璃背景按钮（空白名称） */
    private GuiButton backgroundButton() {
        return GuiButton.builder(Material.GRAY_STAINED_GLASS_PANE)
                .name(" ")
                .build();
    }

    /** 将消息按 \n 分割为多行 lore，每行加 <gray> 前缀 */
    private static List<String> buildMessageLore(@NotNull String message) {
        return Arrays.stream(message.split("\n"))
                .map(line -> "<gray>" + line)
                .toList();
    }
}
