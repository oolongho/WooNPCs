package com.oolongho.woonpc.action;

import com.oolongho.woonpc.api.actions.ActionContext;
import com.oolongho.woonpc.api.actions.NpcAction;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * 临时将玩家提权为 OP 执行命令的动作。
 *
 * <p>执行流程：保存 {@code wasOp} → {@code player.setOp(true)} → 派发命令 →
 * finally 恢复 {@code player.setOp(wasOp)}。即使命令抛异常也能保证 OP 状态恢复。</p>
 *
 * <h2>安全提示</h2>
 * <ul>
 *   <li>仅用于信任的管理员命令（如 {@code /gamemode creative}），不要用于玩家可控的输入</li>
 *   <li>finally 块保证 OP 状态始终恢复，避免权限提权漏洞</li>
 * </ul>
 *
 * <h2>示例配置</h2>
 * <pre>
 *   type: player_command_as_op
 *   command: "gamemode creative"
 * </pre>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class PlayerCommandAsOpAction extends NpcAction {

    /** 要执行的命令（含占位符，执行前替换；不需前导 /） */
    private final String command;

    /**
     * 构造玩家以 OP 身份执行命令的动作。
     *
     * @param command 命令字符串，不可为 null
     */
    public PlayerCommandAsOpAction(String command) {
        this.command = Objects.requireNonNull(command, "command cannot be null");
    }

    @Override
    public boolean execute(ActionContext context) {
        var player = context.player();
        boolean wasOp = player.isOp();
        try {
            player.setOp(true);
            Bukkit.dispatchCommand(player, context.replacePlaceholders(command));
        } finally {
            player.setOp(wasOp);
        }
        return true;
    }

    @Override
    public String typeId() {
        return "player_command_as_op";
    }

    @Override
    public String argsSummary() {
        return command;
    }
}
