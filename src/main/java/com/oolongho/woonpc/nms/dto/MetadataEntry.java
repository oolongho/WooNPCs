package com.oolongho.woonpc.nms.dto;

import org.jetbrains.annotations.ApiStatus;

/**
 * 实体元数据单项。
 *
 * <p>对应 NMS {@code SynchedEntityData.DataValue} 的协议级表示，
 * 在具体 NmsAdapter 实现中由反射转回 DataValue 对象。</p>
 *
 * <ul>
 *   <li>{@code index}：DataWatcher 中的索引（如 0=状态位, 6=自定义名, 7=是否显示名, 13=潜行标志等）</li>
 *   <li>{@code serializerId}：EntityDataSerializer 的注册 ID（0=byte, 1=int, 2=float, 3=string, ...）</li>
 *   <li>{@code value}：实际值，运行时类型与 serializerId 对应（byte/int/float/String/Optional&lt;Component&gt; 等）</li>
 * </ul>
 *
 * @param index        DataWatcher 索引
 * @param serializerId  序列化器 ID
 * @param value        元数据值，类型由 serializerId 决定
 * @author oolongho
 */
@ApiStatus.Internal
public record MetadataEntry(
        int index,
        int serializerId,
        Object value
) {
}
