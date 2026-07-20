package com.oolongho.woonpc.nms.util;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射工具类。
 *
 * <p>缓存 {@link Field} / {@link Method} / {@link Constructor} 的查找结果，
 * 避免重复执行反射元数据扫描。所有方法线程安全（基于 {@link ConcurrentHashMap}）。</p>
 *
 * <p>查找策略：</p>
 * <ul>
 *   <li>字段：本类 declared → 公有 → 父类继承链</li>
 *   <li>方法：本类 declared → declared 装箱回退 → 公有 → 公有装箱回退 → 父类继承链
 *       （按名称 + 形参类型匹配，装箱类型自动回退到原始类型，详见
 *       {@link #findMethodInHierarchy}）</li>
 *   <li>构造器：按形参类型精确匹配</li>
 * </ul>
 *
 * <p>所有反射失败统一抛 {@link WooNPCsReflectException}。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class ReflectUtil {

    /** 字段缓存：className#fieldName → Field */
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    /** 方法缓存：className#methodName(paramTypes) → Method */
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    /** 构造器缓存：className#(paramTypes) → Constructor */
    private static final Map<String, Constructor<?>> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();

    /**
     * 装箱类型 → 原始类型映射表。
     *
     * <p>用于 {@link #findMethodInHierarchy} 的装箱回退：当调用方传入 {@code Integer.valueOf(42)}
     * 时，{@link #toParamTypes} 推断形参类型为 {@code Integer.class}，但目标方法签名是
     * {@code int.class}（如 {@code FriendlyByteBuf.writeVarInt(int)}）。本表用于在精确匹配
     * 失败时将装箱类型转换为原始类型再次尝试。</p>
     */
    private static final Map<Class<?>, Class<?>> BOXED_TO_PRIMITIVE = Map.of(
            Integer.class, int.class,
            Long.class, long.class,
            Boolean.class, boolean.class,
            Double.class, double.class,
            Float.class, float.class,
            Short.class, short.class,
            Byte.class, byte.class,
            Character.class, char.class);

    private ReflectUtil() {
        throw new IllegalAccessError("Utility class");
    }

    // ==================== Class 查找 ====================

    /**
     * 通过全限定名加载类。
     *
     * @param className 全限定类名
     * @return 类对象
     * @throws WooNPCsReflectException 如果类不存在
     */
    public static Class<?> getClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new WooNPCsReflectException("Could not find class: " + className, e);
        }
    }

    /**
     * 通过全限定名加载类，找不到时返回 null（不抛异常）。
     *
     * <p>用于版本可选类的容错加载：某些 NMS 类仅在特定版本存在
     * （如 {@code PositionMoveRotation} 仅 1.21.2+ 存在），
     * 调用方需自行 null 检查并按版本分支处理。</p>
     *
     * @param className 全限定类名
     * @return 类对象，或 null 表示当前服务端不存在该类
     */
    public static Class<?> getClassOrNull(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * 通过全限定名加载类，返回泛型类型。
     *
     * @param className 全限定类名
     * @param <T>       期望类型
     * @return 类对象
     * @throws WooNPCsReflectException 如果类不存在
     */
    @SuppressWarnings("unchecked")
    public static <T> Class<? extends T> getClass(String className, Class<T> superType) {
        Class<?> clazz = getClass(className);
        if (!superType.isAssignableFrom(clazz)) {
            throw new WooNPCsReflectException("Class " + className + " is not assignable to " + superType.getName());
        }
        return (Class<? extends T>) clazz;
    }

    // ==================== Field 操作 ====================

    /**
     * 获取类（含父类）中指定名称的字段，结果缓存。
     *
     * @param clazz     目标类
     * @param fieldName 字段名
     * @return 字段对象（已 setAccessible）
     * @throws WooNPCsReflectException 如果字段不存在
     */
    public static Field getField(@NotNull Class<?> clazz, @NotNull String fieldName) {
        String key = clazz.getName() + "#" + fieldName;
        return FIELD_CACHE.computeIfAbsent(key, k -> {
            Field found = findFieldInHierarchy(clazz, fieldName);
            found.setAccessible(true);
            return found;
        });
    }

    /**
     * 获取静态字段的值。
     *
     * @param clazz     字段所在类
     * @param fieldName 字段名
     * @param <T>       字段类型
     * @return 字段值
     * @throws WooNPCsReflectException 如果查找或访问失败
     */
    @SuppressWarnings("unchecked")
    public static <T> T getFieldValue(@NotNull Class<?> clazz, @NotNull String fieldName) {
        try {
            return (T) getField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new WooNPCsReflectException("Could not access field '" + fieldName
                    + "' in class " + clazz.getName(), e);
        }
    }

    /**
     * 获取实例字段的值。
     *
     * @param instance  字段所在实例
     * @param fieldName  字段名
     * @param <T>       字段类型
     * @return 字段值
     * @throws WooNPCsReflectException 如果查找或访问失败
     */
    @SuppressWarnings("unchecked")
    public static <T> T getFieldValue(@NotNull Object instance, @NotNull String fieldName) {
        try {
            return (T) getField(instance.getClass(), fieldName).get(instance);
        } catch (IllegalAccessException e) {
            throw new WooNPCsReflectException("Could not access field '" + fieldName
                    + "' in class " + instance.getClass().getName(), e);
        }
    }

    /**
     * 设置实例字段的值。
     *
     * @param instance  字段所在实例
     * @param fieldName  字段名
     * @param value     新值
     * @throws WooNPCsReflectException 如果查找或访问失败
     */
    public static void setFieldValue(@NotNull Object instance, @NotNull String fieldName, Object value) {
        try {
            getField(instance.getClass(), fieldName).set(instance, value);
        } catch (IllegalAccessException e) {
            throw new WooNPCsReflectException("Could not set field '" + fieldName
                    + "' in class " + instance.getClass().getName(), e);
        }
    }

    // ==================== Method 操作 ====================

    /**
     * 获取类（含父类）中指定名称和形参类型的方法，结果缓存。
     *
     * @param clazz       目标类
     * @param methodName  方法名
     * @param paramTypes  形参类型数组
     * @return 方法对象（已 setAccessible）
     * @throws WooNPCsReflectException 如果方法不存在
     */
    public static Method getMethod(@NotNull Class<?> clazz, @NotNull String methodName, Class<?>... paramTypes) {
        String key = buildMethodKey(clazz.getName(), methodName, paramTypes);
        return METHOD_CACHE.computeIfAbsent(key, k -> {
            Method found = findMethodInHierarchy(clazz, methodName, paramTypes);
            if (found == null) {
                throw new WooNPCsReflectException("Could not find method '" + methodName
                        + "' with params " + Arrays.toString(paramTypes) + " in class " + clazz.getName());
            }
            found.setAccessible(true);
            return found;
        });
    }

    /**
     * 调用实例方法。
     *
     * @param instance   方法所在实例
     * @param methodName  方法名
     * @param args        实参（含形参类型推断）
     * @param <T>         返回类型
     * @return 方法返回值
     * @throws WooNPCsReflectException 如果方法查找或调用失败
     */
    @SuppressWarnings("unchecked")
    public static <T> T invokeMethod(@NotNull Object instance, @NotNull String methodName, Object... args) {
        Class<?>[] paramTypes = toParamTypes(args);
        Method method = getMethod(instance.getClass(), methodName, paramTypes);
        try {
            return (T) method.invoke(instance, args);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to invoke method '" + methodName
                    + "' on " + instance.getClass().getName(), e);
        }
    }

    /**
     * 调用实例方法，显式指定形参类型。
     *
     * <p>适用于实参为 null 的场景（此时无法从实参推断形参类型，
     * 需调用方明确告知目标方法的形参类型）。</p>
     *
     * @param instance    方法所在实例
     * @param methodName  方法名
     * @param paramTypes  形参类型数组（必须与目标方法签名一致）
     * @param args        实参
     * @param <T>         返回类型
     * @return 方法返回值
     * @throws WooNPCsReflectException 如果方法查找或调用失败
     */
    @SuppressWarnings("unchecked")
    public static <T> T invokeMethod(@NotNull Object instance, @NotNull String methodName,
                                      @NotNull Class<?>[] paramTypes, Object... args) {
        Method method = getMethod(instance.getClass(), methodName, paramTypes);
        try {
            return (T) method.invoke(instance, args);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to invoke method '" + methodName
                    + "' on " + instance.getClass().getName(), e);
        }
    }

    // ==================== Constructor 操作 ====================

    /**
     * 获取类中指定形参类型的构造器，结果缓存。
     *
     * @param clazz       目标类
     * @param paramTypes  形参类型数组
     * @param <T>         类类型
     * @return 构造器对象（已 setAccessible）
     * @throws WooNPCsReflectException 如果构造器不存在
     */
    @SuppressWarnings("unchecked")
    public static <T> Constructor<T> getConstructor(@NotNull Class<T> clazz, Class<?>... paramTypes) {
        String key = buildConstructorKey(clazz.getName(), paramTypes);
        Constructor<?> cached = CONSTRUCTOR_CACHE.get(key);
        if (cached != null) {
            return (Constructor<T>) cached;
        }
        try {
            Constructor<T> found = clazz.getDeclaredConstructor(paramTypes);
            found.setAccessible(true);
            CONSTRUCTOR_CACHE.put(key, found);
            return found;
        } catch (NoSuchMethodException e) {
            throw new WooNPCsReflectException("Could not find constructor with params "
                    + Arrays.toString(paramTypes) + " in class " + clazz.getName(), e);
        }
    }

    /**
     * 在类继承链中查找方法（按名称 + 形参类型精确匹配），结果缓存。
     * 与 {@link #getMethod(Class, String, Class[])} 不同，找不到方法时返回 null 而非抛异常。
     *
     * @param clazz       目标类
     * @param methodName  方法名
     * @param paramTypes  形参类型数组
     * @return 方法对象（已 setAccessible），或 null 表示未找到
     */
    public static Method getMethodOrNull(@NotNull Class<?> clazz, @NotNull String methodName, Class<?>... paramTypes) {
        String key = buildMethodKey(clazz.getName(), methodName, paramTypes);
        Method cached = METHOD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Method found = findMethodInHierarchy(clazz, methodName, paramTypes);
        if (found == null) {
            return null;
        }
        found.setAccessible(true);
        METHOD_CACHE.put(key, found);
        return found;
    }

    /**
     * 调用构造器创建实例。
     *
     * @param clazz       目标类
     * @param paramTypes  形参类型数组
     * @param args        实参
     * @param <T>         类类型
     * @return 新实例
     * @throws WooNPCsReflectException 如果构造器查找或调用失败
     */
    public static <T> T newInstance(@NotNull Class<T> clazz, Class<?>[] paramTypes, Object... args) {
        Constructor<T> constructor = getConstructor(clazz, paramTypes);
        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new WooNPCsReflectException("Failed to instantiate " + clazz.getName()
                    + " with args " + Arrays.toString(args), e);
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 在类继承链中查找字段（含 declared 与 public）。
     */
    private static Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // 尝试 public
            }
            try {
                return current.getField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // 继续向上
            }
            current = current.getSuperclass();
        }
        throw new WooNPCsReflectException("Could not find field '" + fieldName
                + "' in class " + clazz.getName() + " or any superclass");
    }

    /**
     * 在类继承链中查找方法（按名称 + 形参类型精确匹配）。
     *
     * <p>装箱回退策略：当精确匹配（含 declared 与 public）均失败时，
     * 将形参数组中的装箱类型（如 {@code Integer.class}）转换为对应的原始类型
     * （如 {@code int.class}）再次尝试 declared 与 public 匹配。这使得
     * {@code ReflectUtil.invokeMethod(buf, "writeVarInt", Integer.valueOf(42))}
     * 能正确解析到 {@code FriendlyByteBuf.writeVarInt(int)} 等签名。</p>
     *
     * <p>四阶段查找顺序：declared 精确 → declared 原始 → public 精确 → public 原始。
     * 任一阶段命中即返回；全部失败则继续向父类查找。</p>
     *
     * @return 找到的方法，或 null
     */
    private static Method findMethodInHierarchy(Class<?> clazz, String methodName, Class<?>[] paramTypes) {
        Class<?>[] primitiveTypes = toPrimitiveTypes(paramTypes);
        boolean hasPrimitiveFallback = (primitiveTypes != paramTypes);
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(methodName, paramTypes);
            } catch (NoSuchMethodException ignored) {
                // 装箱 → 原始类型回退（declared）
                if (hasPrimitiveFallback) {
                    try {
                        return current.getDeclaredMethod(methodName, primitiveTypes);
                    } catch (NoSuchMethodException ignoredP) {
                        // 继续尝试 public
                    }
                }
                // public 精确匹配
                try {
                    return current.getMethod(methodName, paramTypes);
                } catch (NoSuchMethodException ignored2) {
                    // public 装箱 → 原始类型回退
                    if (hasPrimitiveFallback) {
                        try {
                            return current.getMethod(methodName, primitiveTypes);
                        } catch (NoSuchMethodException ignoredP2) {
                            // 继续向上查找父类
                        }
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * 将形参数组中的装箱类型转换为对应的原始类型。
     *
     * <p>惰性拷贝策略：仅当数组中确实存在装箱类型时才创建新数组，
     * 否则原样返回入参引用（便于上层用 {@code ==} 引用比较判断是否需要回退）。</p>
     *
     * @param paramTypes 原始形参数组
     * @return 原始类型数组（若存在装箱类型）或入参引用（若全为非装箱类型）
     */
    private static Class<?>[] toPrimitiveTypes(Class<?>[] paramTypes) {
        if (paramTypes.length == 0) {
            return paramTypes;
        }
        Class<?>[] primitives = null;
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> primitive = BOXED_TO_PRIMITIVE.get(paramTypes[i]);
            if (primitive != null) {
                if (primitives == null) {
                    primitives = paramTypes.clone();
                }
                primitives[i] = primitive;
            }
        }
        return primitives != null ? primitives : paramTypes;
    }

    /**
     * 从实参数组推断形参类型数组。null 实参按 Object.class 处理。
     */
    private static Class<?>[] toParamTypes(Object[] args) {
        if (args == null || args.length == 0) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] != null ? args[i].getClass() : Object.class;
        }
        return types;
    }

    /**
     * 构造方法缓存键：className#methodName(paramType1,paramType2,...)
     */
    private static String buildMethodKey(String className, String methodName, Class<?>[] paramTypes) {
        return className + "#" + methodName + "(" + joinTypes(paramTypes) + ")";
    }

    /**
     * 构造构造器缓存键：className#(paramType1,paramType2,...)
     */
    private static String buildConstructorKey(String className, Class<?>[] paramTypes) {
        return className + "#(" + joinTypes(paramTypes) + ")";
    }

    /**
     * 拼接形参类型为逗号分隔字符串。
     */
    private static String joinTypes(Class<?>[] paramTypes) {
        if (paramTypes == null || paramTypes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(paramTypes[i] == null ? "null" : paramTypes[i].getName());
        }
        return sb.toString();
    }
}
