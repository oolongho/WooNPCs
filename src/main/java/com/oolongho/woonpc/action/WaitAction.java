package com.oolongho.woonpc.action;

import com.oolongho.woonpc.api.actions.ActionContext;
import com.oolongho.woonpc.api.actions.NpcAction;
import org.jetbrains.annotations.ApiStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 等待指定 tick 的动作。
 *
 * <p>本动作自身不执行任何操作（{@link #execute} 直接返回 true），
 * 延迟效果由 {@link com.oolongho.woonpc.api.actions.ActionManager} 检测
 * {@link #delayTicks()} 返回值 {@code > 0} 后用 {@code BukkitScheduler.runTaskLater}
 * 延迟后续动作实现。</p>
 *
 * <h2>示例配置</h2>
 * <pre>
 *   type: wait
 *   ticks: 20
 * </pre>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class WaitAction extends NpcAction {

    /** 延迟 tick 数（1 秒 = 20 tick） */
    private final int delayTicks;

    /**
     * 构造等待动作。
     *
     * @param delayTicks 延迟 tick 数，必须 {@code >= 0}（{@code <=0} 时无延迟效果）
     */
    public WaitAction(int delayTicks) {
        this.delayTicks = Math.max(0, delayTicks);
    }

    @Override
    public int delayTicks() {
        return delayTicks;
    }

    @Override
    public boolean execute(ActionContext context) {
        return true;
    }

    @Override
    public String typeId() {
        return "wait";
    }

    @Override
    public Map<String, String> serialize() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("ticks", Integer.toString(delayTicks));
        return map;
    }

    @Override
    public String argsSummary() {
        return delayTicks + " ticks";
    }
}
