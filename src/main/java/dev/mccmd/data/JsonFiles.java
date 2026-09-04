package dev.mccmd.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** JSON 读取小工具（data 文件为 UTF-8 无 BOM）。 */
public final class JsonFiles {

    private JsonFiles() {}

    public static JSONObject readObject(String path) throws IOException {
        return new JSONObject(read(path));
    }

    public static JSONObject readObject(InputStream in) throws IOException {
        return new JSONObject(readAll(in));
    }

    public static String read(String path) throws IOException {
        Path p = Path.of(path);
        if (!Files.isRegularFile(p)) {
            throw new IOException("file not found: " + path);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String readAll(InputStream in) throws IOException {
        byte[] buf = in.readAllBytes();
        return new String(buf, StandardCharsets.UTF_8);
    }

    /** 数组 → List<String>；容忍元素为非字符串时跳过。 */
    public static List<String> optStringList(JSONObject o, String key) {
        if (!o.has(key)) return null;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof String) {
            List<String> one = new ArrayList<>(1);
            one.add((String) v);
            return one;
        }
        if (v instanceof JSONArray arr) {
            List<String> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                Object e = arr.opt(i);
                if (e instanceof String s && !s.isEmpty()) out.add(s);
            }
            return out;
        }
        return null;
    }

    public static String nvl(String s) {
        return s != null ? s : "";
    }
}
