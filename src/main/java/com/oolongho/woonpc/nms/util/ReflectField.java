package com.oolongho.woonpc.nms.util;

import org.jetbrains.annotations.ApiStatus;

import java.lang.reflect.Field;

/**
 * 反射字段缓存工具。
 *
 * <p>持有一个字段引用，首次访问时通过反射查找并缓存 {@link Field} 对象，
 * 避免每次访问都重复执行 {@code getDeclaredField} 查找开销。</p>
 *
 * <p>查找顺序：先 {@link Class#getDeclaredField(String)}（含私有），
 * 失败再 {@link Class#getField(String)}（含继承的公有字段），
 * 仍失败则向上遍历父类。字段找到后 {@code setAccessible(true)}。</p>
 *
 * @param <T> 字段值的静态类型
 * @author oolongho
 */
@ApiStatus.Internal
public class ReflectField<T> {

    private final Class<?> parentClass;
    private final String fieldName;
    private volatile Field field;

    /**
     * 构造字段缓存。
     *
     * @param clazz     字段所在类
     * @param fieldName 字段名
     */
    public ReflectField(Class<?> clazz, String fieldName) {
        this.parentClass = clazz;
        this.fieldName = fieldName;
    }

    /**
     * 获取字段的值。
     *
     * @param instance 字段所在实例，静态字段传 null
     * @return 字段当前值
     * @throws WooNPCsReflectException 如果字段查找或访问失败
     */
    @SuppressWarnings("unchecked")
    public T get(Object instance) {
        Field resolved = resolveField();
        try {
            return (T) resolved.get(instance);
        } catch (IllegalAccessException e) {
            throw new WooNPCsReflectException("Could not get value of field '" + fieldName
                    + "' in class " + parentClass.getName(), e);
        }
    }

    /**
     * 设置字段的值。
     *
     * @param instance 字段所在实例，静态字段传 null
     * @param value    新值
     * @throws WooNPCsReflectException 如果字段查找或访问失败
     */
    public void set(Object instance, T value) {
        Field resolved = resolveField();
        try {
            resolved.set(instance, value);
        } catch (IllegalAccessException e) {
            throw new WooNPCsReflectException("Could not set value of field '" + fieldName
                    + "' in class " + parentClass.getName(), e);
        }
    }

    /**
     * 解析并缓存字段对象。双重检查锁定保证只解析一次。
     *
     * @return 已缓存的 Field 对象
     * @throws WooNPCsReflectException 如果字段不存在
     */
    private Field resolveField() {
        Field snapshot = field;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (field == null) {
                field = findField(parentClass, fieldName);
            }
            return field;
        }
    }

    /**
     * 在类层次中查找字段：先本类 declared，再本类 public，再递归父类。
     *
     * @param clazz     起始类
     * @param fieldName 字段名
     * @return 找到的 Field
     * @throws WooNPCsReflectException 如果整条继承链都找不到
     */
    private Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field found = current.getDeclaredField(fieldName);
                found.setAccessible(true);
                return found;
            } catch (NoSuchFieldException ignored) {
                // 当前类没有该字段，尝试父类
            }
            try {
                Field found = current.getField(fieldName);
                found.setAccessible(true);
                return found;
            } catch (NoSuchFieldException ignored) {
                // 公有字段也没有，继续向上
            }
            current = current.getSuperclass();
        }
        throw new WooNPCsReflectException("Could not find field '" + fieldName
                + "' in class " + parentClass.getName() + " or any of its superclasses");
    }
}
