package com.oolongho.woonpc.skin.json;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 极简 JSON 解析器（仅用于皮肤系统 HTTP 响应解析，避免引入 Gson/Jackson）。
 *
 * <p>采用递归下降解析，支持对象/数组/字符串/数字/布尔/null 全部 JSON 字面量，
 * 严格处理字符串转义。返回 {@link Value} 密封类型层次，调用方通过模式匹配取值。</p>
 *
 * <p>仅解析，不序列化；皮肤缓存文件落盘时由 {@code SkinCacheFile} 手写少量字段。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class Json {

    private Json() {
    }

    /** JSON 值的密封类型层次。 */
    public sealed interface Value permits Obj, Arr, Str, Num, Bool, Null {
    }

    /** JSON 对象，保留插入顺序。 */
    public record Obj(LinkedHashMap<String, Value> entries) implements Value {
        /** 取字段值，不存在返回 null。 */
        public @Nullable Value get(String key) {
            return entries.get(key);
        }

        /** 取字符串字段，非字符串或不存在返回 null。 */
        public @Nullable String str(String key) {
            Value v = entries.get(key);
            return v instanceof Str s ? s.value() : null;
        }

        /** 取整数字段（按 long），非数字或不存在返回 null。 */
        public @Nullable Long lng(String key) {
            Value v = entries.get(key);
            return v instanceof Num n ? (long) n.value() : null;
        }

        /** 取对象字段。 */
        public @Nullable Obj obj(String key) {
            Value v = entries.get(key);
            return v instanceof Obj o ? o : null;
        }

        /** 取数组字段。 */
        public @Nullable Arr arr(String key) {
            Value v = entries.get(key);
            return v instanceof Arr a ? a : null;
        }
    }

    /** JSON 数组。 */
    public record Arr(List<Value> items) implements Value {
    }

    /** JSON 字符串。 */
    public record Str(String value) implements Value {
    }

    /** JSON 数字（统一以 double 存储，整数场景由调用方转换）。 */
    public record Num(double value) implements Value {
    }

    /** JSON 布尔。 */
    public record Bool(boolean value) implements Value {
    }

    /** JSON null。 */
    public record Null() implements Value {
    }

    /**
     * 解析 JSON 文本为顶层对象。
     *
     * @param text JSON 文本
     * @return 顶层对象
     * @throws IllegalArgumentException 文本非法或顶层非对象
     */
    public static Obj parse(String text) {
        Parser p = new Parser(text);
        Value v = p.parseValue();
        p.skipWs();
        if (p.pos < p.len) {
            throw new IllegalArgumentException("Unexpected trailing content at " + p.pos);
        }
        if (!(v instanceof Obj o)) {
            throw new IllegalArgumentException("Top-level JSON must be an object");
        }
        return o;
    }

    /** 递归下降解析器。 */
    private static final class Parser {
        final String s;
        final int len;
        int pos;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
        }

        void skipWs() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Value parseValue() {
            skipWs();
            if (pos >= len) {
                throw error("unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObj();
                case '[' -> parseArr();
                case '"' -> new Str(parseStrRaw());
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> parseNum();
            };
        }

        Obj parseObj() {
            pos++; // consume '{'
            LinkedHashMap<String, Value> map = new LinkedHashMap<>();
            skipWs();
            if (pos < len && s.charAt(pos) == '}') {
                pos++;
                return new Obj(map);
            }
            while (true) {
                skipWs();
                if (pos >= len || s.charAt(pos) != '"') {
                    throw error("expected string key");
                }
                String key = parseStrRaw();
                skipWs();
                if (pos >= len || s.charAt(pos) != ':') {
                    throw error("expected ':' after key");
                }
                pos++; // consume ':'
                map.put(key, parseValue());
                skipWs();
                if (pos >= len) {
                    throw error("unterminated object");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return new Obj(map);
                }
                throw error("expected ',' or '}'");
            }
        }

        Arr parseArr() {
            pos++; // consume '['
            List<Value> list = new ArrayList<>();
            skipWs();
            if (pos < len && s.charAt(pos) == ']') {
                pos++;
                return new Arr(list);
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (pos >= len) {
                    throw error("unterminated array");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return new Arr(list);
                }
                throw error("expected ',' or ']'");
            }
        }

        String parseStrRaw() {
            pos++; // consume opening '"'
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= len) {
                        throw error("unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > len) {
                                throw error("bad unicode escape");
                            }
                            int cp = Integer.parseInt(s, pos, pos + 4, 16);
                            sb.append((char) cp);
                            pos += 4;
                        }
                        default -> throw error("bad escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("unterminated string");
        }

        Value parseBool() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return new Bool(true);
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return new Bool(false);
            }
            throw error("bad literal");
        }

        Value parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return new Null();
            }
            throw error("bad literal");
        }

        Value parseNum() {
            int start = pos;
            if (pos < len && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                pos++;
            }
            while (pos < len) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start) {
                throw error("bad number");
            }
            return new Num(Double.parseDouble(s.substring(start, pos)));
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON parse error at " + pos + ": " + msg);
        }
    }
}
