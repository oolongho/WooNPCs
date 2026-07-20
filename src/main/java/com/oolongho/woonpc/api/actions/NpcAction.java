package com.oolongho.woonpc.api.actions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NPC 动作抽象基类。
 *
 * <p>所有具体动作（{@code ConsoleCommandAction}、{@code MessageAction} 等）继承本类，
 * 实现 {@link #execute(ActionContext)}、{@link #typeId()} 与 {@link #serialize()}。</p>
 *
 * <h2>动作链执行模型</h2>
 * <p>{@link ActionManager} 按顺序遍历一个 trigger 下的动作列表，对每个动作：</p>
 * <ol>
 *   <li>检查 {@link #delayTicks()}：若 {@code > 0}，用 {@code BukkitScheduler.runTaskLater}
 *       延迟剩余 tick 后继续执行后续动作（用于 {@code WaitAction}）</li>
 *   <li>调用 {@link #execute(ActionContext)}：
 *     <ul>
 *       <li>返回 {@code true}：继续执行下一个动作</li>
 *       <li>返回 {@code false}：中止整个动作链（用于 {@code NeedPermissionAction} 校验失败）</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h2>序列化</h2>
 * <p>每个动作通过 {@link #serialize()} 输出 {@code Map<String, String>}（参数名 → 值），
 * 与 {@link ActionManager#registerActionType} 注册的工厂互为逆运算。
 * 序列化结构<b>不</b>包含 {@code type} 字段（type 由 typeId 单独存储）。</p>
 *
 * <h2>线程安全</h2>
 * <p>动作在主线程执行（Bukkit API 大多非线程安全）。{@link ActionManager#execute}
 * 必须在主线程调用。</p>
 *
 * @author oolongho
 */
public abstract class NpcAction {

    /**
     * 延迟 tick 数。
     *
     * <p>0 = 立即执行后续动作；{@code > 0} = 延迟指定 tick 后执行后续动作。
     * 默认返回 0，{@code WaitAction} 覆盖此方法返回配置的延迟值。</p>
     *
     * @return 延迟 tick 数
     */
    public int delayTicks() {
        return 0;
    }

    /**
     * 执行动作。
     *
     * @param context 动作上下文
     * @return true 继续执行后续动作，false 中止动作链
     */
    public abstract boolean execute(ActionContext context);

    /**
     * 动作类型 ID（用于序列化与注册表查找）。
     *
     * <p>小写下划线命名，如 {@code "console_command"}、{@code "play_sound"}。
     * {@link ActionManager#registerActionType} 以此为 key 注册反序列化工厂。</p>
     *
     * @return 类型 ID
     */
    public abstract String typeId();

    /**
     * 序列化本动作为参数 Map（不含 {@code type} 字段）。
     *
     * <p>与 {@link ActionManager#registerActionType} 工厂的输入互为逆运算。
     * 默认返回空 Map（无参数动作返回空）。</p>
     *
     * @return 参数 Map，使用 {@link LinkedHashMap} 保证顺序稳定
     */
    public Map<String, String> serialize() {
        return new LinkedHashMap<>();
    }

    /**
     * 动作参数摘要（用于 GUI 显示与日志记录）。
     *
     * <p>子类应返回简短的人类可读字符串，标识本动作的关键参数。
     * 默认返回空字符串。GUI 截断显示（避免 lore 行过长）。</p>
     *
     * @return 参数摘要，无参数时返回空字符串
     */
    public String argsSummary() {
        return "";
    }
}
