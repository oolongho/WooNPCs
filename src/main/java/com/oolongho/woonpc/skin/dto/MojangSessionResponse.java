package com.oolongho.woonpc.skin.dto;

import com.oolongho.woonpc.skin.json.Json;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Mojang session/profile API（{@code sessionserver.mojang.com/session/minecraft/profile/{uuid}}）响应。
 *
 * <p>典型结构：{@code {"id":"...","name":"...","properties":[{"name":"textures","value":"<base64>","signature":"<sig>"}]}}。
 * 其中 {@code textures} property 的 {@code value} 即为 {@link com.oolongho.woonpc.skin.SkinData#texture()}，
 * {@code signature} 即为 {@link com.oolongho.woonpc.skin.SkinData#signature()}，无需再解码 Base64。</p>
 *
 * @param id         玩家 UUID（无连字符）
 * @param name       玩家名
 * @param properties 属性数组
 * @author oolongho
 */
@ApiStatus.Internal
public record MojangSessionResponse(String id, String name, List<Property> properties) {

    /** 按名查找属性，不存在返回 null。 */
    public @Nullable Property property(String name) {
        if (properties == null) {
            return null;
        }
        for (Property p : properties) {
            if (name.equals(p.name())) {
                return p;
            }
        }
        return null;
    }

    /** 从已解析的 JSON 对象构造。 */
    public static MojangSessionResponse fromJson(Json.Obj obj) {
        Json.Arr arr = obj.arr("properties");
        List<Property> props = new ArrayList<>();
        if (arr != null) {
            for (Json.Value v : arr.items()) {
                if (v instanceof Json.Obj po) {
                    props.add(new Property(po.str("name"), po.str("value"), po.str("signature")));
                }
            }
        }
        return new MojangSessionResponse(obj.str("id"), obj.str("name"), List.copyOf(props));
    }

    /** Mojang profile property。 */
    @ApiStatus.Internal
    public record Property(String name, String value, String signature) {
    }
}
