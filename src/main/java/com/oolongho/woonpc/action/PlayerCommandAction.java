package com.oolongho.woonpc.action;

import com.oolongho.woonpc.api.actions.ActionContext;
import com.oolongho.woonpc.api.actions.NpcAction;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 以玩家自身身份执行命令的动作。
 *
 * <p>玩家必须具有对应权限才能执行成功（与 {@link PlayerCommandAsOpAction} 不同，
 * 后者临时提权为 OP）。占位符替换在派发前进行。</p>
 *
 * <h2>示例配置</h2>
 * <pre>
 *   type: player_command
 *   command: "spawn"
 * </pre>
 *
 * @author oolongho
 * @see PlayerCommandAsOpAction
 */
@ApiStatus.Internal
public final class PlayerCommandAction extends NpcAction {

    /** 要执行的命令（含占位符，执行前替换；不需前导 /） */
    private final String command;

    /**
     * 构造玩家命令动作。
     *
     * @param command 命令字符串，不可为 null
     */
    public PlayerCommandAction(String command) {
        this.command = Objects.requireNonNull(command, "command cannot be null");
    }

    @Override
    public boolean execute(ActionContext context) {
        Bukkit.dispatchCommand(context.player(), context.replacePlaceholders(command));
        return true;
    }

    @Override
    public String typeId() {
        return "player_command";
    }

    @Override
    public Map<String, String> serialize() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("command", command);
        return map;
    }

    @Override
    public String argsSummary() {
        return command;
    }
}
