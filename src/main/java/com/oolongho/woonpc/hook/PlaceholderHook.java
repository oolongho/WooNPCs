package com.oolongho.woonpc.hook;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.npc.NpcEffect;
import com.oolongho.woonpc.npc.NpcEquipmentSlot;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PlaceholderAPI 扩展：暴露 NPC 数据为 {@code %woonpc_<id>_<field>%} 占位符。
 *
 * <p>当 PlaceholderAPI 加载时由 {@code WooNPCs.onEnable} 注册。
 * 通过 {@code join-classpath: true} 由服务端注入到本插件 classpath，
 * 因此可安全引用 {@link PlaceholderExpansion} 而无需运行时反射。</p>
 *
 * <h2>占位符格式</h2>
 * <pre>
 *   %woonpc_<id>_<field>%
 * </pre>
 * <p>{@code <id>} 可为 NPC 名称（区分大小写，允许含下划线）或 UUID 字符串。
 * {@code <field>} 取值如下（snake_case）：</p>
 * <ul>
 *   <li>{@code name} — NPC 名称</li>
 *   <li>{@code uuid} — NPC UUID</li>
 *   <li>{@code display_name} — 显示名（MiniMessage 文本，未设置时为空）</li>
 *   <li>{@code world} — 所在世界名</li>
 *   <li>{@code x} / {@code y} / {@code z} — 坐标（保留 2 位小数）</li>
 *   <li>{@code yaw} / {@code pitch} — 朝向（保留 2 位小数）</li>
 *   <li>{@code glow} — 发光颜色名（如 {@code NONE}、{@code RED}）</li>
 *   <li>{@code pose} — 姿势名（如 {@code STANDING}）</li>
 *   <li>{@code scale} — 缩放比例</li>
 *   <li>{@code skin} — 皮肤纹理值（哈希）</li>
 *   <li>{@code has_skin} — 是否有自定义皮肤（true/false）</li>
 *   <li>{@code show_in_tab} — 是否显示在 tab 列表（true/false）</li>
 *   <li>{@code collidable} — 是否可碰撞（true/false）</li>
 *   <li>{@code turn_to_player} — 是否转头跟随玩家（true/false）</li>
 *   <li>{@code turn_to_player_distance} — 转头触发距离（方块，2 位小数）</li>
 *   <li>{@code visibility_distance} — 可见距离（方块，2 位小数）</li>
 *   <li>{@code visibility_permissions} — 可见权限集合（逗号连接，空为 ""）</li>
 *   <li>{@code interaction_cooldown} — 交互冷却（毫秒）</li>
 *   <li>{@code equipment} — 装备映射（"SLOT=MATERIAL,..." 格式）</li>
 *   <li>{@code effects} — 实体效果集合（逗号连接的效果名，空为 ""）</li>
 * </ul>
 *
 * <p><b>字段分割策略</b>：因 NPC 名称允许含下划线，无法用最后下划线简单分割。
 * 本实现枚举所有已知字段名（按长度降序），尝试匹配 params 后缀，
 * 第一个匹配项决定 id 与 field 的边界。</p>
 *
 * <p>NPC 不存在或字段无效时返回空字符串。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class PlaceholderHook extends PlaceholderExpansion {

    private final WooNPCs plugin;
    private final NpcManager npcManager;

    /**
     * 已知字段名集合（按长度降序排列，确保多词字段优先于单词字段匹配）。
     *
     * <p>例如 {@code turn_to_player_distance} 必须在 {@code turn_to_player} 之前尝试匹配，
     * 否则 {@code test_turn_to_player_distance} 会被错误切分为
     * id={@code test_turn_to_player}、field={@code distance}。</p>
     */
    private static final List<String> KNOWN_FIELDS = List.of(
            // 多词字段（长 → 短）
            "visibility_permissions",
            "turn_to_player_distance",
            "interaction_cooldown",
            "visibility_distance",
            "turn_to_player",
            "show_in_tab",
            "display_name",
            "glow_color",
            "has_skin",
            // 单词字段
            "collidable",
            "equipment",
            "effects",
            "name",
            "uuid",
            "world",
            "yaw",
            "pitch",
            "glow",
            "pose",
            "scale",
            "skin",
            "x",
            "y",
            "z"
    );

    /**
     * 创建 PlaceholderAPI 扩展。
     *
     * @param plugin     插件实例
     * @param npcManager NPC 管理器
     */
    public PlaceholderHook(@NotNull WooNPCs plugin, @NotNull NpcManager npcManager) {
        this.plugin = plugin;
        this.npcManager = npcManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "woonpc";
    }

    @Override
    public @NotNull String getAuthor() {
        return "oolongho";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        // 重载 PAPI 时保留扩展
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        // 字段分割：因 NPC 名称可含下划线（如 "test_npc"），不能用 lastIndexOf 简单切分。
        // 按 KNOWN_FIELDS（长度降序）依次尝试后缀匹配，第一个命中即为字段边界。
        String paramsLower = params.toLowerCase(Locale.ROOT);
        for (String field : KNOWN_FIELDS) {
            int needed = field.length() + 1; // "_" + field
            if (paramsLower.length() > needed && paramsLower.endsWith("_" + field)) {
                String idPart = params.substring(0, params.length() - needed);
                Optional<Npc> opt = lookupNpc(idPart);
                if (opt.isEmpty()) {
                    return "";
                }
                return resolveField(opt.get().getData(), field);
            }
        }
        return "";
    }

    /**
     * 按 UUID 字符串或名称查找 NPC。
     */
    private Optional<Npc> lookupNpc(String idOrName) {
        // 先尝试 UUID
        try {
            UUID uuid = UUID.fromString(idOrName);
            return npcManager.getById(uuid);
        } catch (IllegalArgumentException ignored) {
            // 不是 UUID 字符串，按名称查找
        }
        return npcManager.getByName(idOrName);
    }

    /**
     * 解析 NPC 字段为字符串值。
     *
     * <p>所有字段名以 snake_case 形式接收（由 {@link #onRequest} 保证）。</p>
     */
    private @NotNull String resolveField(@NotNull NpcData data, @NotNull String field) {
        return switch (field) {
            // 标识
            case "name" -> data.name();
            case "uuid" -> data.id().toString();
            case "display_name" -> data.displayName() != null ? data.displayName() : "";
            // 位置
            case "world" -> {
                Location loc = data.location();
                yield loc.getWorld() != null ? loc.getWorld().getName() : "";
            }
            case "x" -> String.format(Locale.ROOT, "%.2f", data.location().getX());
            case "y" -> String.format(Locale.ROOT, "%.2f", data.location().getY());
            case "z" -> String.format(Locale.ROOT, "%.2f", data.location().getZ());
            case "yaw" -> String.format(Locale.ROOT, "%.2f", data.location().getYaw());
            case "pitch" -> String.format(Locale.ROOT, "%.2f", data.location().getPitch());
            // 外观
            case "glow", "glow_color" -> data.glowColor().name();
            case "pose" -> data.pose().name();
            case "scale" -> Float.toString(data.scale());
            case "skin" -> data.skin().texture();
            case "has_skin" -> Boolean.toString(!data.skin().isDefault());
            // 行为开关
            case "show_in_tab" -> Boolean.toString(data.showInTab());
            case "collidable" -> Boolean.toString(data.collidable());
            case "turn_to_player" -> Boolean.toString(data.turnToPlayer());
            case "turn_to_player_distance" ->
                    String.format(Locale.ROOT, "%.2f", data.turnToPlayerDistance());
            case "visibility_distance" ->
                    String.format(Locale.ROOT, "%.2f", data.visibilityDistance());
            case "visibility_permissions" ->
                    String.join(",", data.visibilityPermissions());
            case "interaction_cooldown" -> Long.toString(data.interactionCooldown());
            // 复合字段
            case "equipment" -> serializeEquipment(data.equipment());
            case "effects" -> serializeEffects(data.effects());
            default -> "";
        };
    }

    /**
     * 将装备映射序列化为 {@code "SLOT=MATERIAL,SLOT=MATERIAL"} 格式。
     * 空映射返回空字符串。
     */
    private static @NotNull String serializeEquipment(@NotNull Map<NpcEquipmentSlot, ItemStack> equipment) {
        if (equipment.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(equipment.size());
        for (Map.Entry<NpcEquipmentSlot, ItemStack> entry : equipment.entrySet()) {
            parts.add(entry.getKey().name() + "=" + entry.getValue().getType().name());
        }
        return String.join(",", parts);
    }

    /**
     * 将效果集合序列化为逗号连接的效果名（如 {@code "FIRE,GLOWING"}）。
     * 空集合返回空字符串。
     */
    private static @NotNull String serializeEffects(@NotNull java.util.Set<NpcEffect> effects) {
        if (effects.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>(effects.size());
        for (NpcEffect effect : effects) {
            names.add(effect.name());
        }
        return String.join(",", names);
    }
}
