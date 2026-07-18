package com.oolongho.woonpc.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * GUI 屏幕抽象基类。
 *
 * <p>实现 {@link InventoryHolder}，持有一个 {@link Inventory}、按钮映射与父级 GUI 引用。
 * 子类实现 {@link #render()} 方法填充按钮（通过 {@link #setButton}），{@link #refresh()}
 * 会在不关闭重开的前提下重新渲染，实现无闪烁更新。</p>
 *
 * <h2>生命周期</h2>
 * <ol>
 *   <li>子类构造器调用 {@code super(title, size, parent)} 创建空 Inventory</li>
 *   <li>{@link #open(Player)} 调用 {@link #refresh()} 渲染内容后打开 Inventory</li>
 *   <li>用户点击 → {@link GuiManager} 委托 {@link #handleClick} → {@link GuiButton#click}</li>
 *   <li>数据变更后调用 {@link #refresh()} 原地刷新（无闪烁）</li>
 *   <li>{@link #close(Player)} 关闭 Inventory</li>
 * </ol>
 *
 * <h2>无闪烁刷新原理</h2>
 * <p>{@link #refresh()} 清空按钮映射与 Inventory 内容后重新调用 {@link #render()}，
 * 后者通过 {@link #setButton} 直接修改已打开 Inventory 的槽位。
 * Bukkit 在同一 tick 内批量同步 Inventory 更新，客户端只看到最终状态，不产生闪烁。</p>
 *
 * <h2>父级导航</h2>
 * <p>{@link #parent} 字段记录上级 GUI，{@link GuiManager#goBack} 利用此字段实现返回导航。
 * 根级 GUI（如 NpcListGui）的 parent 为 null。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public abstract class GuiScreen implements InventoryHolder {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** 此 GUI 持有的 Inventory（构造时创建，不可变引用） */
    protected final Inventory inventory;

    /** 槽位 → 按钮映射（render 时填充） */
    protected final Map<Integer, GuiButton> buttons = new HashMap<>();

    /** 父级 GUI（可为 null，表示根级 GUI） */
    protected final GuiScreen parent;

    /**
     * 构造 GUI 屏幕。
     *
     * @param title  Inventory 标题（MiniMessage 格式字符串）
     * @param size   Inventory 大小（必须为 9 的倍数）
     * @param parent 父级 GUI，根级 GUI 传 null
     */
    protected GuiScreen(@NotNull String title, int size, @Nullable GuiScreen parent) {
        this.inventory = Bukkit.createInventory(this, size, MM.deserialize(title));
        this.parent = parent;
    }

    /**
     * 在指定槽位设置按钮，同时更新 Inventory 内容。
     *
     * @param slot   槽位索引
     * @param button 按钮，不可为 null
     */
    public void setButton(int slot, @NotNull GuiButton button) {
        Objects.requireNonNull(button, "button cannot be null");
        buttons.put(slot, button);
        inventory.setItem(slot, button.getItemStack());
    }

    /**
     * 获取指定槽位的按钮。
     *
     * @param slot 槽位索引
     * @return 按钮实例，无则返回 null
     */
    @Nullable
    public GuiButton getButton(int slot) {
        return buttons.get(slot);
    }

    /**
     * 移除指定槽位的按钮并清空对应 Inventory 槽位。
     *
     * @param slot 槽位索引
     */
    public void removeButton(int slot) {
        buttons.remove(slot);
        inventory.setItem(slot, null);
    }

    /**
     * 清空所有按钮与 Inventory 内容。
     */
    public void clearButtons() {
        buttons.clear();
        inventory.clear();
    }

    /**
     * 渲染并打开 GUI。先调用 {@link #refresh()} 确保内容最新，再打开 Inventory。
     *
     * @param player 目标玩家
     */
    public void open(@NotNull Player player) {
        refresh();
        player.openInventory(inventory);
    }

    /**
     * 关闭玩家的此 GUI。
     *
     * @param player 目标玩家
     */
    public void close(@NotNull Player player) {
        player.closeInventory();
    }

    /**
     * 无闪烁刷新：清空按钮与 Inventory 后重新调用 {@link #render()}。
     *
     * <p>适用于已打开的 GUI 原地更新内容，不会关闭重开。</p>
     */
    public void refresh() {
        buttons.clear();
        inventory.clear();
        render();
    }

    /**
     * 处理玩家点击：查找对应槽位的按钮并触发其回调。
     *
     * @param player    点击的玩家
     * @param slot      槽位索引
     * @param clickType 点击类型
     */
    public void handleClick(@NotNull Player player, int slot, @NotNull ClickType clickType) {
        GuiButton button = buttons.get(slot);
        if (button != null) {
            button.click(player, clickType);
        }
    }

    /**
     * 获取父级 GUI。
     *
     * @return 父级 GUI，根级 GUI 返回 null
     */
    @Nullable
    public GuiScreen getParent() {
        return parent;
    }

    /**
     * 渲染 GUI 内容。子类实现此方法，通过 {@link #setButton} 填充按钮。
     *
     * <p>由 {@link #refresh()} 和 {@link #open(Player)} 调用。
     * 实现应从 manager 获取最新数据并构建按钮，确保每次调用都反映当前状态。</p>
     */
    public abstract void render();

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
