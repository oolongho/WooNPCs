package com.oolongho.woonpc.nms.versions;

import org.jetbrains.annotations.ApiStatus;

/**
 * Minecraft 1.21.5+ NMS 适配器实现。
 *
 * <p>覆盖 1.21.5 及以上版本。与 {@link Nms_1_21} 的核心差异在于
 * {@code SynchedEntityData.DataValue.create} 签名变更：</p>
 * <ul>
 *   <li>1.21.4-：{@code DataValue.create(EntityDataAccessor, Object)} —— 需要 accessor</li>
 *   <li>1.21.5+：{@code DataValue.create(Object, int)} —— 简化为 (value, id)</li>
 * </ul>
 *
 * <p>此差异已由 {@link com.oolongho.woonpc.nms.util.PacketFactory} 通过反射自动适配
 * （静态初始化时检测 {@code DataValue.create} 方法签名），因此本类继承 {@link Nms_1_21}
 * 的全部发包逻辑，仅覆盖 {@link #getVersion()} 标识版本。</p>
 *
 * <p>未来若 1.21.5+ 出现其他协议层差异（如包构造器签名变更、新 DataWatcher 索引等），
 * 可在本类中覆盖对应方法。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public class Nms_1_21_5 extends Nms_1_21 {

    @Override
    public String getVersion() {
        return "1.21.5";
    }
}
