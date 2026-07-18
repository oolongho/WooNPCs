package com.oolongho.woonpc.gui;

import com.oolongho.woonpc.WooNPCs;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI 管理器：管理玩家当前打开的 GUI，监听 Inventory 事件并委托给 {@link GuiScreen}。
 *
 * <p>由 Task 10 在 {@link WooNPCs} 主类装配时注册为 Listener。</p>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li><b>打开/关闭</b>：{@link #openGui} 记录到 openGuis 并打开；{@link #closeGui} 移除并关闭</li>
 *   <li><b>返回导航</b>：{@link #goBack} 获取当前 GUI 的 parent，重新渲染并打开；无 parent 则关闭</li>
 *   <li><b>事件分发</b>：监听点击/关闭/拖拽事件，校验 Inventory 归属后委托给对应 {@link GuiScreen}</li>
 * </ul>
 *
 * <h2>事件优先级</h2>
 * <ul>
 *   <li>{@link InventoryClickEvent}：{@link EventPriority#HIGH} — 取消事件 + 委托 handleClick</li>
 *   <li>{@link InventoryCloseEvent}：{@link EventPriority#MONITOR} — 从 openGuis 移除（不取消）</li>
 *   <li>{@link InventoryDragEvent}：{@link EventPriority#HIGH} — 取消事件（装备编辑在 Task 5 单独处理）</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>{@link #openGuis} 使用 {@link ConcurrentHashMap}，所有事件回调均在主线程执行（Bukkit 保证）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class GuiManager implements Listener {

    private final WooNPCs plugin;
    private final Map<UUID, GuiScreen> openGuis = new ConcurrentHashMap<>();

    /**
     * 构造 GUI 管理器。
     *
     * @param plugin 插件实例，用于调度
     */
    public GuiManager(@NotNull WooNPCs plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
    }

    /**
     * 为玩家打开指定 GUI（记录到 openGuis 并渲染打开）。
     *
     * @param player 目标玩家
     * @param gui    要打开的 GUI
     */
    public void openGui(@NotNull Player player, @NotNull GuiScreen gui) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(gui, "gui cannot be null");
        // 先记录到 openGuis，确保后续 InventoryCloseEvent（由 openInventory 触发的旧 GUI 关闭）
        // 不会误删新 GUI 条目（onClose 校验 Inventory 归属，旧 Inventory ≠ 新 Inventory）
        openGuis.put(player.getUniqueId(), gui);
        gui.open(player);
    }

    /**
     * 返回上级 GUI：获取当前 GUI 的 parent，若不为 null 重新渲染并打开；否则关闭。
     *
     * @param player 目标玩家
     */
    public void goBack(@NotNull Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        GuiScreen current = openGuis.get(player.getUniqueId());
        if (current == null) {
            return;
        }
        GuiScreen parent = current.getParent();
        if (parent != null) {
            openGui(player, parent);
        } else {
            closeGui(player);
        }
    }

    /**
     * 获取玩家当前打开的 GUI。
     *
     * @param player 目标玩家
     * @return 当前 GUI，未打开则返回 null
     */
    @Nullable
    public GuiScreen getCurrentGui(@NotNull Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        return openGuis.get(player.getUniqueId());
    }

    /**
     * 关闭玩家的 GUI：从 openGuis 移除并关闭 Inventory。
     *
     * @param player 目标玩家
     */
    public void closeGui(@NotNull Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        openGuis.remove(player.getUniqueId());
        player.closeInventory();
    }

    // ==================== 事件监听 ====================

    /**
     * Inventory 点击事件：取消事件并委托给 {@link GuiScreen#handleClick}。
     *
     * <p>仅处理当前由 GuiManager 管理的 GUI，其他 Inventory 不干预。</p>
     *
     * @param event InventoryClickEvent
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        GuiScreen gui = openGuis.get(player.getUniqueId());
        if (gui == null) {
            return;
        }
        // 仅处理顶部 Inventory（GUI 本体），玩家自身背包点击不转发但仍然取消
        if (!event.getInventory().equals(gui.getInventory())) {
            return;
        }
        event.setCancelled(true);
        // rawSlot 仅在点击顶部 Inventory 时才有意义，底部背包点击忽略
        if (event.getRawSlot() < event.getInventory().getSize()) {
            gui.handleClick(player, event.getRawSlot(), event.getClick());
        }
    }

    /**
     * Inventory 关闭事件：从 openGuis 移除玩家条目。
     *
     * <p>使用 MONITOR 优先级，不干扰其他插件的处理。仅当关闭的是当前管理的 GUI 时才移除。</p>
     *
     * @param event InventoryCloseEvent
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        GuiScreen gui = openGuis.get(player.getUniqueId());
        if (gui == null) {
            return;
        }
        // 仅当关闭的 Inventory 是当前 GUI 的 Inventory 时才移除
        // （goBack 切换 GUI 时旧 Inventory 关闭事件不应误删新 GUI 条目）
        if (event.getInventory().equals(gui.getInventory())) {
            openGuis.remove(player.getUniqueId());
        }
    }

    /**
     * Inventory 拖拽事件：取消事件（GUI 不允许拖拽）。
     *
     * <p>装备编辑 GUI（Task 5）如需支持拖拽放入，将在子类中单独处理。</p>
     *
     * @param event InventoryDragEvent
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        GuiScreen gui = openGuis.get(player.getUniqueId());
        if (gui == null) {
            return;
        }
        if (!event.getInventory().equals(gui.getInventory())) {
            return;
        }
        event.setCancelled(true);
    }
}
