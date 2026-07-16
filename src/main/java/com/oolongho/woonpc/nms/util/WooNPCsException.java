package com.oolongho.woonpc.nms.util;

import org.jetbrains.annotations.ApiStatus;

/**
 * WooNPCs 运行时异常基类。
 *
 * <p>所有插件内部抛出的非检查异常均继承此类，便于上层统一捕获与日志记录。
 * 反射相关的异常使用子类 {@link WooNPCsReflectException} 表达。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public class WooNPCsException extends RuntimeException {

    public WooNPCsException(String message) {
        super(message);
    }

    public WooNPCsException(String message, Throwable cause) {
        super(message, cause);
    }
}
