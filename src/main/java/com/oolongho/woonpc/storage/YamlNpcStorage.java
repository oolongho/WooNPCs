package com.oolongho.woonpc.storage;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.npc.GlowingColor;
import com.oolongho.woonpc.npc.NpcEffect;
import com.oolongho.woonpc.npc.NpcEquipmentSlot;
import com.oolongho.woonpc.npc.NpcPose;
import com.oolongho.woonpc.skin.SkinData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Bukkit {@link YamlConfiguration} 的 NPC 存储实现。
 *
 * <p>使用纯 Paper API + Bukkit YAML 持久化，无第三方序列化依赖。内存中维护
 * {@code UUID → 序列化 Map} 的全量快照，{@link #save} 仅更新内存，
 * {@link #saveAll} 时全量写入文件。</p>
 *
 * <h2>文件布局</h2>
 * <ul>
 *   <li><b>单文件模式</b>（默认，NPC 数 ≤ {@value #SPLIT_THRESHOLD}）：{@code plugins/WooNPCs/npcs.yml}</li>
 *   <li><b>分目录模式</b>（NPC 数 &gt; {@value #SPLIT_THRESHOLD}）：{@code plugins/WooNPCs/npcs/<world>.yml}，
 *       按 NPC 所在世界分组写入</li>
 * </ul>
 * <p>加载阶段同时扫描两种布局，便于运行时无缝切换保存模式。</p>
 *
 * <h2>YAML 结构</h2>
 * <pre>{@code
 * npcs:
 *   <uuid-string>:
 *     name: "shop1"
 *     location: { world: "world", x: 100.5, y: 64.0, z: -200.5, yaw: 90.0, pitch: 0.0 }
 *     display-name: "商店老板"        # nullable，null 时省略
 *     skin: { texture: "...", signature: "..." }
 *     equipment: { mainhand: <ItemStack>, head: <ItemStack> }
 *     glow-color: "NONE"
 *     pose: "STANDING"
 *     scale: 1.0
 *     effects: ["FIRE"]
 *     show-in-tab: false
 *     collidable: false
 *     turn-to-player: true
 *     turn-to-player-distance: 8.0
 *     visibility-distance: 32.0
 *     visibility-permissions: ["perm.vip"]   # 空时省略
 *     interaction-cooldown: 5000
 * }</pre>
 *
 * <h2>线程安全</h2>
 * <ul>
 *   <li>{@link #memoryStore} 为 {@link ConcurrentHashMap}，{@link #save} / {@link #delete} 可在任意线程调用</li>
 *   <li>{@link #saveAll} / {@link #loadAll} 仅在主线程调用，{@link YamlConfiguration} 非线程安全</li>
 *   <li>{@link #saveAll} 通过快照拷贝避免迭代时被并发修改</li>
 * </ul>
 *
 * <h2>加载校验</h2>
 * <ul>
 *   <li>{@code world} 未加载 → 跳过 + warning</li>
 *   <li>{@code location} / {@code name} 缺失 → 跳过 + warning</li>
 *   <li>enum 解析失败 → 用默认值 + warning</li>
 *   <li>装备槽位 {@link ItemStack} 反序列化失败 → 跳过该槽位 + warning</li>
 * </ul>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class YamlNpcStorage implements NpcStorage {

    /** 单文件模式下 NPC 数据文件名 */
    private static final String SINGLE_FILE_NAME = "npcs.yml";

    /** 分目录模式下存放各世界 NPC 数据的目录名 */
    private static final String SPLIT_DIR_NAME = "npcs";

    /** 切换到分目录模式的 NPC 总数阈值（超出则分目录） */
    static final int SPLIT_THRESHOLD = 500;

    private final Plugin plugin;

    /**
     * 内存全量 NPC 数据：{@code UUID → 序列化 Map}。
     *
     * <p>语义：在内存中即"待落盘"。{@link #save} 更新此 Map，{@link #saveAll} 时全量写入文件。
     * {@link #loadAll} 加载完成后会同步填充此 Map 作为缓存。</p>
     */
    private final Map<UUID, Map<String, Object>> memoryStore = new ConcurrentHashMap<>();

    /**
     * 创建存储实例。
     *
     * @param plugin 插件实例，用于获取数据目录与日志器
     */
    public YamlNpcStorage(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
    }

    // ==================== NpcStorage 实现 ====================

    @Override
    public void save(Npc npc) {
        Objects.requireNonNull(npc, "npc cannot be null");
        memoryStore.put(npc.getId(), serialize(npc.getData()));
    }

    @Override
    public void delete(UUID npcId) {
        Objects.requireNonNull(npcId, "npcId cannot be null");
        memoryStore.remove(npcId);
    }

    @Override
    public void saveAll() {
        // 取快照避免迭代期间被并发修改
        Map<UUID, Map<String, Object>> snapshot = new LinkedHashMap<>(memoryStore);

        if (snapshot.size() > SPLIT_THRESHOLD) {
            writeSplit(snapshot);
        } else {
            writeSingle(snapshot);
        }
    }

    @Override
    public List<NpcData> loadAll() {
        List<NpcData> result = new ArrayList<>();
        // 清空内存缓存，从文件全量重建
        memoryStore.clear();

        File dataFolder = plugin.getDataFolder();

        // 1) 加载单文件 npcs.yml
        File singleFile = new File(dataFolder, SINGLE_FILE_NAME);
        if (singleFile.isFile()) {
            loadFromFile(singleFile, result);
        }

        // 2) 加载分目录 npcs/<world>.yml
        File splitDir = new File(dataFolder, SPLIT_DIR_NAME);
        File[] worldFiles = splitDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (worldFiles != null) {
            for (File worldFile : worldFiles) {
                if (worldFile.isFile()) {
                    loadFromFile(worldFile, result);
                }
            }
        }

        return result;
    }

    // ==================== 序列化：NpcData → Map ====================

    /**
     * 将 {@link NpcData} 序列化为可写入 YAML 的嵌套 Map。
     *
     * <p>注意：{@link ItemStack} 直接作为 value 存入 Map，Bukkit 在写入
     * {@link YamlConfiguration} 时会通过 {@code ConfigurationSerializable} 机制
     * 自动序列化（含 {@code ==} 类型标记）。</p>
     *
     * @param data NPC 数据快照
     * @return 序列化结果（有序 Map，便于生成稳定 YAML）
     */
    private Map<String, Object> serialize(NpcData data) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", data.name());

        // location
        Location loc = data.location();
        Map<String, Object> locMap = new LinkedHashMap<>();
        World world = loc.getWorld();
        locMap.put("world", world != null ? world.getName() : "");
        locMap.put("x", loc.getX());
        locMap.put("y", loc.getY());
        locMap.put("z", loc.getZ());
        locMap.put("yaw", loc.getYaw());
        locMap.put("pitch", loc.getPitch());
        map.put("location", locMap);

        // display-name：null 省略
        if (data.displayName() != null) {
            map.put("display-name", data.displayName());
        }

        // skin
        SkinData skin = data.skin();
        Map<String, Object> skinMap = new LinkedHashMap<>();
        skinMap.put("texture", skin.texture());
        skinMap.put("signature", skin.signature());
        map.put("skin", skinMap);

        // equipment：空映射省略
        if (!data.equipment().isEmpty()) {
            Map<String, Object> equipMap = new LinkedHashMap<>();
            for (Map.Entry<NpcEquipmentSlot, ItemStack> e : data.equipment().entrySet()) {
                equipMap.put(e.getKey().name().toLowerCase(Locale.ROOT), e.getValue());
            }
            map.put("equipment", equipMap);
        }

        map.put("glow-color", data.glowColor().name());
        map.put("pose", data.pose().name());
        map.put("scale", data.scale());

        // effects：空集省略
        if (!data.effects().isEmpty()) {
            List<String> effList = new ArrayList<>(data.effects().size());
            for (NpcEffect eff : data.effects()) {
                effList.add(eff.name());
            }
            map.put("effects", effList);
        }

        map.put("show-in-tab", data.showInTab());
        map.put("collidable", data.collidable());
        map.put("turn-to-player", data.turnToPlayer());
        map.put("turn-to-player-distance", data.turnToPlayerDistance());
        map.put("visibility-distance", data.visibilityDistance());

        // visibility-permissions：空集省略
        if (!data.visibilityPermissions().isEmpty()) {
            map.put("visibility-permissions", new ArrayList<>(data.visibilityPermissions()));
        }

        map.put("interaction-cooldown", data.interactionCooldown());
        return map;
    }

    // ==================== 反序列化：ConfigurationSection → NpcData ====================

    /**
     * 从单个 YAML 文件加载所有 NPC。
     *
     * <p>加载失败的 NPC 会被跳过并记录 warning，不中止后续加载。</p>
     *
     * @param file   YAML 文件
     * @param result 累积结果的列表
     */
    private void loadFromFile(File file, List<NpcData> result) {
        YamlConfiguration yaml;
        try {
            yaml = YamlConfiguration.loadConfiguration(file);
        } catch (Exception e) {
            plugin.getLogger().warning("加载 " + file.getName() + " 失败: " + e.getMessage());
            return;
        }

        ConfigurationSection npcsSection = yaml.getConfigurationSection("npcs");
        if (npcsSection == null) {
            return; // 空文件或非 NPC 文件，静默跳过
        }

        for (String uuidStr : npcsSection.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("跳过无效 UUID: " + uuidStr + "（文件 " + file.getName() + "）");
                continue;
            }

            ConfigurationSection sec = npcsSection.getConfigurationSection(uuidStr);
            if (sec == null) {
                plugin.getLogger().warning("跳过 NPC " + uuidStr + "：节点不是 ConfigurationSection");
                continue;
            }

            // UUID 去重：防止单文件 + 分目录共存时同一 NPC 被加载两次
            if (memoryStore.containsKey(uuid)) {
                plugin.getLogger().warning("跳过 NPC " + uuidStr + "：UUID 重复（可能在单文件和分目录中同时存在）");
                continue;
            }

            try {
                NpcData data = deserialize(uuid, sec);
                if (data != null) {
                    result.add(data);
                    memoryStore.put(uuid, serialize(data));
                } else {
                    // deserialize 失败（世界未加载/字段缺失等）：保留原始数据避免 saveAll 全量重写时丢失
                    memoryStore.put(uuid, sectionToMap(sec));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("跳过 NPC " + uuidStr + "：解析异常 - " + e.getMessage());
                // 解析异常也保留原始数据，避免下次 saveAll 抹除
                memoryStore.put(uuid, sectionToMap(sec));
            }
        }
    }

    /**
     * 将 ConfigurationSection 递归转为嵌套 Map（用于保留原始数据）。
     *
     * @param sec 配置节点
     * @return 嵌套 Map（LinkedHashMap 保持顺序）
     */
    private Map<String, Object> sectionToMap(ConfigurationSection sec) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : sec.getKeys(false)) {
            Object val = sec.get(key);
            if (val instanceof ConfigurationSection sub) {
                map.put(key, sectionToMap(sub));
            } else {
                map.put(key, val);
            }
        }
        return map;
    }

    /**
     * 反序列化单个 NPC 节点为 {@link NpcData}。
     *
     * @param uuid NPC 的 UUID（来自文件 key）
     * @param sec  NPC 节点
     * @return 解析成功的 NpcData，校验失败返回 null
     */
    @Nullable
    private NpcData deserialize(UUID uuid, ConfigurationSection sec) {
        // name：必填
        String name = sec.getString("name");
        if (name == null || name.isEmpty()) {
            plugin.getLogger().warning("跳过 NPC " + uuid + "：缺少 name 字段");
            return null;
        }

        // location：必填
        ConfigurationSection locSec = sec.getConfigurationSection("location");
        if (locSec == null) {
            plugin.getLogger().warning("跳过 NPC " + uuid + "：缺少 location 字段");
            return null;
        }
        String worldName = locSec.getString("world");
        if (worldName == null || worldName.isEmpty()) {
            plugin.getLogger().warning("跳过 NPC " + uuid + "：location 缺少 world 字段");
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("跳过 NPC " + uuid + "：世界 " + worldName + " 未加载");
            return null;
        }

        Location location;
        try {
            location = new Location(
                    world,
                    locSec.getDouble("x"),
                    locSec.getDouble("y"),
                    locSec.getDouble("z"),
                    (float) locSec.getDouble("yaw", 0.0),
                    (float) locSec.getDouble("pitch", 0.0)
            );
        } catch (Exception e) {
            plugin.getLogger().warning("跳过 NPC " + uuid + "：location 解析失败 - " + e.getMessage());
            return null;
        }

        NpcData.Builder builder = NpcData.builder(uuid, name, location);

        // display-name：可选
        if (sec.isSet("display-name")) {
            builder.displayName(sec.getString("display-name"));
        }

        // skin：可选，缺失则用默认
        ConfigurationSection skinSec = sec.getConfigurationSection("skin");
        if (skinSec != null) {
            String texture = skinSec.getString("texture", "");
            String signature = skinSec.getString("signature", "");
            builder.skin(new SkinData(texture, signature));
        }

        // equipment：可选，逐槽位解析
        ConfigurationSection equipSec = sec.getConfigurationSection("equipment");
        if (equipSec != null) {
            Map<NpcEquipmentSlot, ItemStack> equipment = new EnumMap<>(NpcEquipmentSlot.class);
            for (String slotKey : equipSec.getKeys(false)) {
                NpcEquipmentSlot slot;
                try {
                    slot = NpcEquipmentSlot.valueOf(slotKey.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("NPC " + uuid + " 装备槽位 " + slotKey + " 未知，跳过该槽位");
                    continue;
                }
                try {
                    ItemStack item = equipSec.getItemStack(slotKey);
                    if (item == null) {
                        plugin.getLogger().warning("NPC " + uuid + " 装备槽位 " + slotKey + " 反序列化返回 null，跳过");
                        continue;
                    }
                    equipment.put(slot, item);
                } catch (Exception e) {
                    plugin.getLogger().warning("NPC " + uuid + " 装备槽位 " + slotKey
                            + " 反序列化失败: " + e.getMessage() + "，跳过该槽位");
                }
            }
            if (!equipment.isEmpty()) {
                builder.equipment(equipment);
            }
        }

        // enum 字段：解析失败用默认值
        builder.glowColor(parseEnum(sec, "glow-color", GlowingColor.class, GlowingColor.NONE, uuid));
        builder.pose(parseEnum(sec, "pose", NpcPose.class, NpcPose.STANDING, uuid));

        // scale
        builder.scale((float) sec.getDouble("scale", NpcData.DEFAULT_SCALE));

        // effects：逐项解析，失败跳过单项
        List<String> effNames = sec.getStringList("effects");
        if (!effNames.isEmpty()) {
            Set<NpcEffect> effects = EnumSet.noneOf(NpcEffect.class);
            for (String effName : effNames) {
                try {
                    effects.add(NpcEffect.valueOf(effName));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("NPC " + uuid + " 效果 " + effName + " 未知，跳过该效果");
                }
            }
            if (!effects.isEmpty()) {
                builder.effects(effects);
            }
        }

        builder.showInTab(sec.getBoolean("show-in-tab", false));
        builder.collidable(sec.getBoolean("collidable", false));
        builder.turnToPlayer(sec.getBoolean("turn-to-player", false));
        builder.turnToPlayerDistance(sec.getDouble("turn-to-player-distance", NpcData.DEFAULT_TURN_TO_PLAYER_DISTANCE));
        builder.visibilityDistance(sec.getDouble("visibility-distance", NpcData.DEFAULT_VISIBILITY_DISTANCE));

        // visibility-permissions
        List<String> perms = sec.getStringList("visibility-permissions");
        if (!perms.isEmpty()) {
            builder.visibilityPermissions(new LinkedHashSet<>(perms));
        }

        builder.interactionCooldown(sec.getLong("interaction-cooldown", NpcData.DEFAULT_INTERACTION_COOLDOWN));

        return builder.build();
    }

    /**
     * 通用枚举解析：失败时记录 warning 并返回默认值。
     *
     * @param sec          NPC 节点
     * @param key          YAML key
     * @param enumClass    枚举类型
     * @param defaultValue 解析失败时的默认值
     * @param npcId        NPC UUID（用于日志）
     * @param <E>          枚举泛型
     * @return 解析值或默认值
     */
    private <E extends Enum<E>> E parseEnum(ConfigurationSection sec, String key,
                                            Class<E> enumClass, E defaultValue, UUID npcId) {
        String value = sec.getString(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("NPC " + npcId + " 字段 " + key
                    + " 值 " + value + " 无效，使用默认值 " + defaultValue.name());
            return defaultValue;
        }
    }

    // ==================== 文件写入 ====================

    /**
     * 单文件模式：覆盖写入 {@code npcs.yml}，并清理 {@code npcs/} 目录残留。
     *
     * <p>清理分目录残留是为了避免下次加载时读到已删除的 NPC 数据
     * （从分目录模式切换回单文件模式，或 NPC 数量从 &gt;500 降到 ≤500 的场景）。</p>
     *
     * @param snapshot 内存快照
     */
    private void writeSingle(Map<UUID, Map<String, Object>> snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Object>> e : snapshot.entrySet()) {
            yaml.set("npcs." + e.getKey(), e.getValue());
        }
        File file = new File(plugin.getDataFolder(), SINGLE_FILE_NAME);
        try {
            ensureParent(file);
            yaml.save(file);
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().warning("保存 " + file.getName() + " 失败: " + e.getMessage());
        }
        // 清理分目录残留，避免下次 loadAll 加载到旧数据
        clearSplitDir();
    }

    /**
     * 清空 {@code npcs/} 目录下所有 {@code .yml} 文件（保留目录本身）。
     */
    private void clearSplitDir() {
        File splitDir = new File(plugin.getDataFolder(), SPLIT_DIR_NAME);
        File[] oldFiles = splitDir.listFiles((dir, n) -> n.endsWith(".yml"));
        if (oldFiles == null) {
            return;
        }
        for (File f : oldFiles) {
            if (!f.delete()) {
                plugin.getLogger().warning("清理分目录残留失败: " + f.getName());
            }
        }
    }

    /**
     * 分目录模式：按 NPC 所在世界分组写入 {@code npcs/<world>.yml}，
     * 并删除旧的 {@code npcs.yml}（避免下次加载时数据重复）。
     *
     * @param snapshot 内存快照
     */
    private void writeSplit(Map<UUID, Map<String, Object>> snapshot) {
        File splitDir = new File(plugin.getDataFolder(), SPLIT_DIR_NAME);
        if (!splitDir.exists() && !splitDir.mkdirs()) {
            plugin.getLogger().warning("创建目录失败: " + splitDir.getAbsolutePath());
            return;
        }

        // 清空旧的世界文件（已不存在的世界 / 已删除的 NPC）
        clearSplitDir();

        // 按世界分组
        Map<String, YamlConfiguration> byWorld = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<String, Object>> e : snapshot.entrySet()) {
            Map<String, Object> data = e.getValue();
            String worldName = extractWorldName(data);
            YamlConfiguration yaml = byWorld.computeIfAbsent(worldName, k -> {
                YamlConfiguration y = new YamlConfiguration();
                y.createSection("npcs");
                return y;
            });
            yaml.set("npcs." + e.getKey(), data);
        }

        // 写入每个世界的文件
        for (Map.Entry<String, YamlConfiguration> e : byWorld.entrySet()) {
            File worldFile = new File(splitDir, e.getKey() + ".yml");
            try {
                e.getValue().save(worldFile);
            } catch (IOException | IllegalArgumentException ex) {
                plugin.getLogger().warning("保存 " + worldFile.getName() + " 失败: " + ex.getMessage());
            }
        }

        // 删除单文件，避免下次加载重复（迁移语义）
        File single = new File(plugin.getDataFolder(), SINGLE_FILE_NAME);
        if (single.exists() && !single.delete()) {
            plugin.getLogger().warning("迁移到分目录模式：删除旧 npcs.yml 失败，下次加载可能重复");
        }
    }

    /**
     * 从序列化 Map 中提取 world 名（用于分目录写入分组）。
     *
     * @param data 序列化数据
     * @return world 名，缺失返回 {@code "unknown"}
     */
    @SuppressWarnings("unchecked")
    private String extractWorldName(Map<String, Object> data) {
        Object locObj = data.get("location");
        if (locObj instanceof Map) {
            Object world = ((Map<String, Object>) locObj).get("world");
            if (world instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return "unknown";
    }

    /**
     * 确保父目录存在。
     *
     * @param file 目标文件
     * @throws IOException 当目录创建失败
     */
    private void ensureParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent.getAbsolutePath());
        }
    }
}
