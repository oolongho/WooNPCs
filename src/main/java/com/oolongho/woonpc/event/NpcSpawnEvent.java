package com.oolongho.woonpc.event;

import com.oolongho.woonpc.api.Npc;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * NPC 生成事件。
 *
 * <p>当 {@link Npc#spawn()} 被调用时，在实际发送 spawn 包到可见玩家前触发。
 * 本事件<b>可取消</b>，取消后 spawn 流程中止，不发送任何 spawn 包。</p>
 *
 * <p>触发时机：NpcImpl#spawn → 触发本事件 →（未取消）遍历 targetViewers 调用 controller.spawn。</p>
 *
 * <p>注意：{@link Npc#update()} 中 SKIN 字段变更触发的"重新 spawn"是内部协议层操作，
 * 不触发本事件。</p>
 *
 * @author oolongho
 */
public class NpcSpawnEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Npc npc;
    private boolean cancelled;

    /**
     * 构造 NPC 生成事件。
     *
     * @param npc 被生成的 NPC，不可为 null
     */
    public NpcSpawnEvent(@NotNull Npc npc) {
        this.npc = npc;
        this.cancelled = false;
    }

    /**
     * 获取被生成的 NPC。
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
