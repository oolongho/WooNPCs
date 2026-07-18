package com.oolongho.woonpc.action;

import com.oolongho.woonpc.api.actions.ActionContext;
import com.oolongho.woonpc.api.actions.NpcAction;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 从候选动作中随机选取一个执行的动作。
 *
 * <p>执行时从 {@link #candidates} 中均匀随机选取一个，调用其 {@link NpcAction#execute}
 * 并<b>返回其结果</b>。被选中动作的 {@link #delayTicks()} 不影响本动作的延迟判断
 * （本动作 {@link #delayTicks()} 始终返回 0）。</p>
 *
 * <p>空候选列表返回 true（视为无操作，继续后续动作链）。</p>
 *
 * <h2>示例配置</h2>
 * <pre>
 *   type: execute_random
 *   candidates:
 *     - { type: message, message: "运气 A" }
 *     - { type: message, message: "运气 B" }
 * </pre>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class ExecuteRandomAction extends NpcAction {

    /** 候选动作列表（不可变） */
    private final List<NpcAction> candidates;

    /**
     * 构造随机执行动作。
     *
     * @param candidates 候选动作列表，不可为 null（可为空列表）
     */
    public ExecuteRandomAction(List<NpcAction> candidates) {
        this.candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates cannot be null"));
    }

    @Override
    public boolean execute(ActionContext context) {
        if (candidates.isEmpty()) {
            return true;
        }
        int index = ThreadLocalRandom.current().nextInt(candidates.size());
        NpcAction chosen = candidates.get(index);
        return chosen.execute(context);
    }

    @Override
    public String typeId() {
        return "execute_random";
    }

    @Override
    public String argsSummary() {
        return candidates.size() + " candidates";
    }
}
