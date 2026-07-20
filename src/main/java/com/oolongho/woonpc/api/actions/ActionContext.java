package com.oolongho.woonpc.api.actions;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.npc.ClickType;
import com.oolongho.woonpc.util.PlaceholderUtil;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * NPC 动作执行上下文（不可变值对象）。
 *
 * <p>封装一次 NPC 交互的全部上下文信息：玩家、NPC、点击类型、插件实例。
 * 由 {@link ActionManager} 在 {@code execute} 时构造，传递给 {@link NpcAction#execute}。</p>
 *
 * <h2>占位符替换</h2>
 * <p>{@link #replacePlaceholders(String)} 提供两级占位符替换：</p>
 * <ol>
 *   <li>内置占位符（{@code {player}} / {@code {npc}} / {@code {world}} / {@code {x},{y},{z}} 等）</li>
 *   <li>PlaceholderAPI 占位符（{@code %...%} 格式，需服务端安装 PAPI）</li>
 * </ol>
 *
 * <p>两种占位符可混用，内置先替换再交给 PAPI。</p>
 *
 * @param player    执行交互的玩家
 * @param npc       被交互的 NPC
 * @param clickType 玩家点击类型
 * @param plugin    插件实例
 * @author oolongho
 */
public record ActionContext(Player player, Npc npc, ClickType clickType, WooNPCs plugin) {

    /**
     * Compact constructor：非空校验。
     *
     * @throws NullPointerException 当任一参数为 null
     */
    public ActionContext {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(npc, "npc cannot be null");
        Objects.requireNonNull(clickType, "clickType cannot be null");
        Objects.requireNonNull(plugin, "plugin cannot be null");
    }

    /**
     * 替换字符串中的占位符（内置 + PlaceholderAPI）。
     *
     * <p>顺序：先替换内置 {@code {player}}/{@code {npc}}/{@code {world}}/{@code {x,y,z}}，
     * 再交由 PlaceholderAPI 解析 {@code %...%} 占位符（PAPI 未安装则跳过此步）。
     * 输入 null 时返回空串。</p>
     *
     * @param input 原始字符串，可为 null
     * @return 替换后的字符串，input 为 null 时返回 {@code ""}
     */
    public String replacePlaceholders(String input) {
        if (input == null) return "";
        String result = input
                .replace("{player}", player.getName())
                .replace("{npc}", npc.getName())
                .replace("{npc_name}", npc.getName())
                .replace("{world}", player.getWorld().getName())
                .replace("{x}", String.format("%.2f", player.getLocation().getX()))
                .replace("{y}", String.format("%.2f", player.getLocation().getY()))
                .replace("{z}", String.format("%.2f", player.getLocation().getZ()));
        return PlaceholderUtil.setPlaceholders(player, result);
    }
}
