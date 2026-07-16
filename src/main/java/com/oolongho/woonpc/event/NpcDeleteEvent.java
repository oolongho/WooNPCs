package com.oolongho.woonpc.event;

import com.oolongho.woonpc.api.Npc;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * NPC 删除事件。
 *
 * <p>当 {@link com.oolongho.woonpc.api.NpcManager#remove} 执行 despawn 后、
 * 从内部映射移除前触发。本事件<b>不可取消</b>，仅用于通知外部插件有 NPC 被删除。</p>
 *
 * <p>触发时机：NpcManagerImpl#remove → despawn → 触发本事件 → 从 map 移除。</p>
 *
 * @author oolongho
 */
public class NpcDeleteEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Npc npc;

    /**
     * 构造 NPC 删除事件。
     *
     * @param npc 被删除的 NPC，不可为 null
     */
    public NpcDeleteEvent(@NotNull Npc npc) {
        this.npc = npc;
    }

    /**
     * 获取被删除的 NPC。
     *
     * @return NPC 实例
     */
    @NotNull
    public Npc getNpc() {
        return npc;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
