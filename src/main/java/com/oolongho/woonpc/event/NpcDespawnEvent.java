package com.oolongho.woonpc.event;

import com.oolongho.woonpc.api.Npc;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * NPC 销毁显示事件。
 *
 * <p>当 {@link Npc#despawn()} 被调用时，在实际发送 despawn 包到可见玩家前触发。
 * 本事件<b>可取消</b>，取消后 despawn 流程中止，不发送任何 despawn 包，
 * NPC 仍对已可见玩家保持显示。</p>
 *
 * <p>触发时机：NpcImpl#despawn → 触发本事件 →（未取消）调用 controller.despawnAll。</p>
 *
 * @author oolongho
 */
public class NpcDespawnEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Npc npc;
    private boolean cancelled;

    /**
     * 构造 NPC 销毁显示事件。
     *
     * @param npc 被销毁显示的 NPC，不可为 null
     */
    public NpcDespawnEvent(@NotNull Npc npc) {
        this.npc = npc;
        this.cancelled = false;
    }

    /**
     * 获取被销毁显示的 NPC。
     *
     * @return NPC 实例
     */
    @NotNull
    public Npc getNpc() {
        return npc;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
