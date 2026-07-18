package com.oolongho.woonpc.action;

import com.oolongho.woonpc.api.actions.ActionContext;
import com.oolongho.woonpc.api.actions.NpcAction;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * 校验玩家权限的动作（门控）。
 *
 * <p>当玩家<b>具有</b>指定权限时返回 true（继续执行后续动作）；
 * <b>不具有</b>时返回 false 中止整个动作链。</p>
 *
 * <p>典型用法：将本动作置于动作链首位，作为前置权限检查。</p>
 *
 * <h2>示例配置</h2>
 * <pre>
 *   type: need_permission
 *   permission: "woonpc.admin"
 * </pre>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NeedPermissionAction extends NpcAction {

    /** 要求的权限节点 */
    private final String permission;

    /**
     * 构造权限校验动作。
     *
     * @param permission 权限节点，不可为 null
     */
    public NeedPermissionAction(String permission) {
        this.permission = Objects.requireNonNull(permission, "permission cannot be null");
    }

    @Override
    public boolean execute(ActionContext context) {
        return context.player().hasPermission(permission);
    }

    @Override
    public String typeId() {
        return "need_permission";
    }

    @Override
    public String argsSummary() {
        return permission;
    }
}
