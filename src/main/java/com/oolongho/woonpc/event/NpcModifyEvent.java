package com.oolongho.woonpc.event;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcField;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * NPC 数据修改事件。
 *
 * <p>当 NPC 的某个可同步字段（{@link NpcField}）被修改时触发。本事件<b>可取消</b>，
 * 取消后修改不会应用到 NPC 的内部数据快照。</p>
 *
 * <p>触发时机：在 {@link Npc} 的 setter 方法（命令系统 / GUI 系统）中，
 * 修改 data 前触发；未取消时执行 {@code data = data.withXxx(newValue)} 并标记 dirty。</p>
 *
 * <p>事件触发由 {@code NpcImpl#modify} 实现：先在 synchronized 块外触发事件（避免持锁调用监听器），
 * 未取消后在 synchronized 块内替换 data。{@code NpcImpl#update()} 的增量同步不触发本事件
 * （dirty 标记已存在，无需再次校验）。</p>
 *
 * <h2>字段值类型对照</h2>
 * <ul>
 *   <li>{@link NpcField#LOCATION} → {@link org.bukkit.Location}</li>
 *   <li>{@link NpcField#DISPLAY_NAME} → {@code String}（可为 null）</li>
 *   <li>{@link NpcField#SKIN} → {@link com.oolongho.woonpc.skin.SkinData}</li>
 *   <li>{@link NpcField#EQUIPMENT} → {@code Map<NpcEquipmentSlot, ItemStack>}</li>
 *   <li>{@link NpcField#GLOW_COLOR} → {@link com.oolongho.woonpc.npc.GlowingColor}</li>
 *   <li>{@link NpcField#POSE} → {@link com.oolongho.woonpc.npc.NpcPose}</li>
 *   <li>{@link NpcField#SCALE} → {@code Float}</li>
 *   <li>{@link NpcField#EFFECTS} → {@code Set<NpcEffect>}</li>
 *   <li>布尔型字段 → {@code Boolean}</li>
 *   <li>数值型字段 → {@code Double} / {@code Long}</li>
 * </ul>
 *
 * @author oolongho
 */
public class NpcModifyEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Npc npc;
    private final NpcField field;
    private final Object oldValue;
    private final Object newValue;
    private boolean cancelled;

    /**
     * 构造 NPC 修改事件。
     *
     * @param npc      被修改的 NPC，不可为 null
     * @param field    被修改的字段，不可为 null
     * @param oldValue 旧值，可为 null（首次设置时）
     * @param newValue 新值，可为 null（如清除显示名）
     */
    public NpcModifyEvent(@NotNull Npc npc,
                          @NotNull NpcField field,
                          @Nullable Object oldValue,
                          @Nullable Object newValue) {
        this.npc = npc;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.cancelled = false;
    }

    /**
     * 获取被修改的 NPC。
     *
     * @return NPC 实例
     */
    @NotNull
    public Npc getNpc() {
        return npc;
    }

    /**
     * 获取被修改的字段。
     *
     * @return 字段枚举
     */
    @NotNull
    public NpcField getField() {
        return field;
    }

    /**
     * 获取修改前的旧值。
     *
     * @return 旧值，首次设置时为 null
     */
    @Nullable
    public Object getOldValue() {
        return oldValue;
    }

    /**
     * 获取修改后的新值。
     *
     * @return 新值，清除字段时为 null
     */
    @Nullable
    public Object getNewValue() {
        return newValue;
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
