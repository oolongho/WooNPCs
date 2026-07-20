package com.oolongho.woonpc.nms.versions;

import com.oolongho.woonpc.nms.dto.NpcTexture;
import com.oolongho.woonpc.nms.util.ReflectUtil;
import com.oolongho.woonpc.nms.util.WooNPCsReflectException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Minecraft 1.21.9+ NMS 适配器实现。
 *
 * <p>覆盖 1.21.9、1.21.10、1.21.11+ 及新格式 26.1+ 全部版本。与 {@link Nms_1_21_5}
 * 的核心差异在于 {@code com.mojang.authlib.GameProfile} 从普通类变为 record 风格：</p>
 *
 * <ul>
 *   <li>1.21.5-1.21.8：{@code new GameProfile(uuid, name)} 2 参构造器，再通过
 *       {@code getProperties().put("textures", property)} 注入皮肤纹理</li>
 *   <li>1.21.9+：{@code new GameProfile(uuid, name, propertyMap)} 3 参构造器，
 *       propertyMap 由 {@code new PropertyMap(ImmutableMultimap.of("textures", property))}
 *       构造，访问器为 {@code properties()} 而非 {@code getProperties()}</li>
 * </ul>
 *
 * <p>此差异由本类覆盖 {@link #createGameProfile} 钩子方法实现，其余发包逻辑
 * 继承自 {@link Nms_1_21}。{@link com.oolongho.woonpc.nms.util.PacketFactory} 中的
 * DataValue 反射自适应机制在 1.21.9+ 仍然有效（{@code DataValue.create(value, id)} 静态方法
 * 签名未变），故无需额外覆盖元数据相关方法。</p>
 *
 * <h2>PropertyMap 构造器版本自适应</h2>
 * <p>authlib 7.0.63（26.1.2+）将 {@code PropertyMap} 构造器入参从
 * {@code ImmutableMultimap} 改为更通用的 {@code Multimap}。由于 ImmutableMultimap
 * 实现 Multimap 接口，本类通过扫描 declared constructors 找单参且参数类型可接受
 * ImmutableMultimap 的构造器，兼容 1.21.9-1.21.11（authlib 6.0.x）与 26.1.2+
 * （authlib 7.0.x）两种签名。</p>
 *
 * <h2>参考实现</h2>
 * <p>新 API 写法参考 {@code fancynpcs-v2/implementation_1_21_9/Npc_1_21_9.java} 的
 * {@code getEntry} 与 {@code spawn} 方法。本类用纯反射访问
 * {@code com.mojang.authlib.properties.PropertyMap} 与 {@code com.google.common.collect.ImmutableMultimap}
 * （避免对运行时 1.21.9+ authlib 的编译期依赖）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public class Nms_1_21_11 extends Nms_1_21 {

    /** authlib PropertyMap 反射类缓存（1.21.9+ GameProfile 3 参构造器入参） */
    private static final Class<?> PROPERTY_MAP_CLASS =
            ReflectUtil.getClass("com.mojang.authlib.properties.PropertyMap");

    /** Guava ImmutableMultimap 反射类缓存（构造 PropertyMap 入参用） */
    private static final Class<?> IMMUTABLE_MULTIMAP_CLASS =
            ReflectUtil.getClass("com.google.common.collect.ImmutableMultimap");

    /** ImmutableMultimap.of(K, V) 静态方法（有皮肤时构造单条目 multimap） */
    private static final Method IMMUTABLE_MULTIMAP_OF_METHOD =
            ReflectUtil.getMethod(IMMUTABLE_MULTIMAP_CLASS, "of", Object.class, Object.class);

    /** ImmutableMultimap.of() 静态方法（无参版本，无皮肤时构造空 multimap） */
    private static final Method IMMUTABLE_MULTIMAP_EMPTY_METHOD =
            ReflectUtil.getMethod(IMMUTABLE_MULTIMAP_CLASS, "of");

    /**
     * PropertyMap 单参构造器缓存。
     *
     * <p>扫描 declared constructors 找单参且参数类型可接受 ImmutableMultimap
     * （即 Multimap 或其父类/接口）的构造器，兼容两种签名：</p>
     * <ul>
     *   <li>1.21.9-1.21.11（authlib 6.0.x）：{@code PropertyMap(ImmutableMultimap)}</li>
     *   <li>26.1.2+（authlib 7.0.63）：{@code PropertyMap(Multimap)}</li>
     * </ul>
     * <p>ImmutableMultimap 实例可同时满足两种签名，调用方无需关心具体类型。</p>
     */
    private static final Constructor<?> PROPERTY_MAP_CTOR = findPropertyMapConstructor();

    /** GameProfile(UUID, String, PropertyMap) 3 参构造器缓存（1.21.9+ 专用） */
    private static final Constructor<?> GAME_PROFILE_3_ARG_CTOR =
            ReflectUtil.getConstructor(GAME_PROFILE_CLASS, UUID.class, String.class, PROPERTY_MAP_CLASS);

    /**
     * 扫描 PropertyMap 的 declared constructors 找单参且可接受 ImmutableMultimap 的构造器。
     *
     * <p>查找规则：参数数量为 1，且参数类型对 ImmutableMultimap 可分配
     * （{@code paramType.isAssignableFrom(ImmutableMultimap.class)}）。
     * 兼容 authlib 6.0.x 的 {@code PropertyMap(ImmutableMultimap)} 与
     * authlib 7.0.x 的 {@code PropertyMap(Multimap)} 两种签名。</p>
     *
     * @return 找到的构造器（已 setAccessible）
     * @throws WooNPCsReflectException 如果未找到匹配构造器
     */
    private static Constructor<?> findPropertyMapConstructor() {
        for (Constructor<?> ctor : PROPERTY_MAP_CLASS.getDeclaredConstructors()) {
            if (ctor.getParameterCount() != 1) {
                continue;
            }
            Class<?> paramType = ctor.getParameterTypes()[0];
            if (paramType.isAssignableFrom(IMMUTABLE_MULTIMAP_CLASS)) {
                ctor.setAccessible(true);
                return ctor;
            }
        }
        throw new WooNPCsReflectException(
                "No single-param constructor accepting ImmutableMultimap found in PropertyMap");
    }

    @Override
    public String getVersion() {
        return "1.21.11";
    }

    /**
     * 构造 GameProfile 实例（1.21.9+ 版本）。
     *
     * <p>使用 3 参构造器 {@code new GameProfile(uuid, name, propertyMap)}，
     * 其中 propertyMap 由 {@code new PropertyMap(ImmutableMultimap.of("textures", property))}
     * 构造。与 1.21.5-1.21.8 的差异：</p>
     * <ul>
     *   <li>GameProfile 在 1.21.9+ 为 record 风格，无 2 参构造器，必须 3 参</li>
     *   <li>PropertyMap 不可变，需在构造时一次性传入 multimap，无法后续 {@code put}</li>
     *   <li>访问器为 {@code properties()} 而非 {@code getProperties()}</li>
     * </ul>
     *
     * <p>无皮肤时构造空 PropertyMap（{@code ImmutableMultimap.of()}）传入。
     * PropertyMap 构造器入参类型由 {@link #PROPERTY_MAP_CTOR} 自适应，
     * 兼容 1.21.9-1.21.11 与 26.1.2+ 两种 authlib 签名。</p>
     *
     * @param uuid     NPC 的 UUID
     * @param username GameProfile 玩家名
     * @param texture  皮肤纹理，null 表示无皮肤（默认 Steve/Alex）
     * @return NMS GameProfile 实例
     */
    @Override
    protected Object createGameProfile(UUID uuid, String username, @Nullable NpcTexture texture) {
        try {
            Object immutableMultimap;
            if (texture != null) {
                Object property = PROPERTY_CLASS
                        .getConstructor(String.class, String.class, String.class)
                        .newInstance("textures", texture.value(), texture.signature());
                immutableMultimap = IMMUTABLE_MULTIMAP_OF_METHOD.invoke(null, "textures", property);
            } else {
                immutableMultimap = IMMUTABLE_MULTIMAP_EMPTY_METHOD.invoke(null);
            }
            Object propertyMap = PROPERTY_MAP_CTOR.newInstance(immutableMultimap);
            return GAME_PROFILE_3_ARG_CTOR.newInstance(uuid, username, propertyMap);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to construct GameProfile for " + username, e);
        }
    }
}
