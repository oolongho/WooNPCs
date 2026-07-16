package com.oolongho.woonpc.event;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.npc.ClickType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * NPC 交互事件。
 *
 * <p>当玩家与 NPC 交互（左/右/Shift 点击）时由 {@code NpcImpl.interact} 触发。
 * 本事件<b>可取消</b>，取消后 {@code ActionManager} 不执行该交互对应的动作集合。</p>
 *
 * <h2>触发时机</h2>
 * <p>{@code NpcImpl.interact(player, clickType)} → 构造本事件 →
 * {@code Bukkit.getPluginManager().callEvent(event)} →（未取消）
 * Task 18 的监听器调用 {@code ActionManager.execute(player, npc, clickType)}。</p>
 *
 * <h2>事件优先级</h2>
 * <p>外部插件可使用任意优先级（{@code LOWEST} - {@code HIGHEST}）监听本事件，
 * 在 {@code ActionManager} 执行前修改取消状态或读取上下文。
 * {@code ActionManager} 监听器应使用 {@code MONITOR} 之外的优先级（如 {@code LOW}），
 * 并在监听器内检查 {@link #isCancelled()}。</p>
 *
 * @author oolongho
 */
public class NpcInteractEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Player player;
    private final Npc npc;
    private final ClickType clickType;
    private boolean cancelled;

    /**
     * 构造 NPC 交互事件。
     *
     * @param player    交互的玩家，不可为 null
     * @param npc       被交互的 NPC，不可为 null
     * @param clickType 玩家点击类型，不可为 null
     */
    public NpcInteractEvent(@NotNull Player player, @NotNull Npc npc, @NotNull ClickType clickType) {
        this.player = player;
        this.npc = npc;
        this.clickType = clickType;
    }

    /**
     * 获取交互的玩家。
     *
     * @return 玩家实例
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * 获取被交互的 NPC。
     *
     * @return NPC 实例
     */
    @NotNull
    public Npc getNpc() {
        return npc;
    }

    /**
     * 获取玩家点击类型。
     *
     * @return 点击类型
     */
    @NotNull
    public ClickType getClickType() {
        return clickType;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
