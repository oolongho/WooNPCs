package com.oolongho.woonpc.api;

import com.oolongho.woonpc.skin.SkinData;
import org.bukkit.Location;

import java.util.Objects;
import java.util.UUID;

/**
 * NPC 构造器（公共 API，链式）。
 *
 * <p>提供简洁的链式 API 创建 NPC，内部委托 {@link NpcData.Builder} 构造数据快照，
 * 再通过 {@link NpcManager#create} 完成注册与 spawn。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * Npc npc = new NpcBuilder(npcManager)
 *         .name("shop_keeper")
 *         .location(new Location(world, 100, 64, 200))
 *         .displayName("<green>商店老板")
 *         .skin(SkinData.defaultSkin())
 *         .build();
 * }</pre>
 *
 * <p>必填字段：{@link #name(String)}、{@link #location(Location)}。
 * 选填字段：{@link #displayName(String)}（默认 null）、{@link #skin(SkinData)}（默认 {@link SkinData#defaultSkin()}）。
 * 其余字段使用 {@link NpcData.Builder} 的默认值。</p>
 *
 * <p>本类为公共 API，不加 {@code @ApiStatus.Internal} 注解。</p>
 *
 * @author oolongho
 */
public final class NpcBuilder {

    private final NpcManager manager;

    private String name;
    private Location location;
    private String displayName;
    private SkinData skin;

    /**
     * 创建构造器。
     *
     * @param manager NpcManager 实例，build() 时委托其 create 方法
     */
    public NpcBuilder(NpcManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager cannot be null");
    }

    /**
     * 设置 NPC 名称（必填，唯一）。
     *
     * @param name 名称，不可为 null
     * @return 当前构造器
     */
    public NpcBuilder name(String name) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        return this;
    }

    /**
     * 设置 NPC 初始位置（必填）。
     *
     * @param location 位置，不可为 null
     * @return 当前构造器
     */
    public NpcBuilder location(Location location) {
        this.location = Objects.requireNonNull(location, "location cannot be null");
        return this;
    }

    /**
     * 设置头顶显示名（选填，默认 null 表示不显示）。
     *
     * @param displayName 显示名，null 表示不显示；支持 MiniMessage/Section 颜色代码
     * @return 当前构造器
     */
    public NpcBuilder displayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    /**
     * 设置皮肤（选填，默认 {@link SkinData#defaultSkin()}）。
     *
     * @param skin 皮肤数据，不可为 null
     * @return 当前构造器
     */
    public NpcBuilder skin(SkinData skin) {
        this.skin = Objects.requireNonNull(skin, "skin cannot be null");
        return this;
    }

    /**
     * 构建并注册 NPC。
     *
     * <p>内部生成随机 UUID，构造 {@link NpcData} 快照（dirty = 全部字段，首次 spawn 全量同步），
     * 委托 {@link NpcManager#create} 完成注册与 spawn。</p>
     *
     * @return 新创建的 NPC
     * @throws IllegalStateException    当 name 已被其他 NPC 占用
     * @throws NullPointerException    当 name 或 location 未设置
     * @throws com.oolongho.woonpc.nms.util.WooNPCsException 当 NmsAdapter 未就绪
     */
    public Npc build() {
        Objects.requireNonNull(name, "name must be set before build()");
        Objects.requireNonNull(location, "location must be set before build()");
        UUID id = UUID.randomUUID();
        NpcData.Builder builder = NpcData.builder(id, name, location);
        if (displayName != null) {
            builder.displayName(displayName);
        }
        if (skin != null) {
            builder.skin(skin);
        }
        NpcData data = builder.build();
        return manager.create(data);
    }
}
