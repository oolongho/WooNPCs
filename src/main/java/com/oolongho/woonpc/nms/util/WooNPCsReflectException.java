package com.oolongho.woonpc.nms.util;

import org.jetbrains.annotations.ApiStatus;

/**
 * 反射操作异常。
 *
 * <p>当反射查找字段、方法、构造器失败，或反射调用抛出异常时抛出此异常。
 * 携带目标类名与成员名，便于定位问题。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public class WooNPCsReflectException extends WooNPCsException {

    public WooNPCsReflectException(String message) {
        super(message);
    }

    public WooNPCsReflectException(String message, Throwable cause) {
        super(message, cause);
    }
}
