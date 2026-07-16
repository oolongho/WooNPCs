package com.oolongho.woonpc.event;

import com.oolongho.woonpc.api.Npc;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * NPC 创建事件。
 *
 * <p>当 {@link com.oolongho.woonpc.api.NpcManager#create} 完成唯一性校验、
 * 构造 {@link Npc} 实例后，注册到内部映射前触发。本事件<b>不可取消</b>，
 * 仅用于通知外部插件有新 NPC 被创建。</p>
 *
 * <p>触发时机：NpcManagerImpl#create → 构造 NpcImpl → 触发本事件 → 注册到 map → spawn。</p>
 *
 * @author oolongho
 */
public class NpcCreateEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Npc npc;

    /**
     * 构造 NPC 创建事件。
     *
     * @param npc 被创建的 NPC，不可为 null
     */
    public NpcCreateEvent(@NotNull Npc npc) {
        this.npc = npc;
    }

    /**
     * 获取被创建的 NPC。
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
