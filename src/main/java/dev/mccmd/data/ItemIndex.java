package dev.mccmd.data;

import dev.mccmd.model.ItemDef;
import dev.mccmd.model.SpecialValue;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 物品索引加载器（items.json，schema v1）。 */
public final class ItemIndex {

    private final Map<String, ItemDef> byId;
    private final List<ItemDef> items;

    public ItemIndex(String jsonPath) throws java.io.IOException {
        this(JsonFiles.readObject(jsonPath));
    }

    public ItemIndex(JSONObject root) {
        Map<String, ItemDef> map = new LinkedHashMap<>();
        List<ItemDef> list = new ArrayList<>();
        JSONArray arr = root.optJSONArray("items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                ItemDef item = parseItem(o);
                if (item.id == null || item.id.isEmpty()) continue;
                if (!map.containsKey(item.id)) {
                    map.put(item.id, item);
                }
                list.add(item);
            }
        }
        this.byId = map;
        this.items = list;
    }

    private static ItemDef parseItem(JSONObject o) {
        String id = o.optString("id", null);
        String namespace = o.optString("namespace", "minecraft");
        String catCn = o.optString("category_cn", "");
        String catEn = o.optString("category_en", "");
        String nameCn = o.optString("name_cn", "");
        String nameEn = o.optString("name_en", "");
        String icon = o.optString("icon", "");
        String wikiUrl = o.optString("wikiUrl", "");
        boolean isBlock = o.optBoolean("isBlock", false);
        boolean isItem = o.optBoolean("isItem", true);
        boolean stackable = o.optBoolean("stackable", true);
        int maxStack = o.optInt("max_stack", stackable ? 64 : 1);
        return new ItemDef(id, namespace, catCn, catEn, nameCn, nameEn, icon, wikiUrl,
                isBlock, isItem, stackable, maxStack,
                parseSpecialValues(o.optJSONArray("special_values")),
                parseStates(o.optJSONObject("states")));
    }

    private static List<SpecialValue> parseSpecialValues(JSONArray arr) {
        if (arr == null) return Collections.emptyList();
        List<SpecialValue> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(new SpecialValue(o.optInt("value", 0),
                    o.optString("name_cn", ""),
                    o.optString("name_en", ""),
                    o.optString("icon", "")));
        }
        return out;
    }

    private static Map<String, List<String>> parseStates(JSONObject o) {
        if (o == null) return Collections.emptyMap();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String k : o.keySet()) {
            List<String> vs = JsonFiles.optStringList(o, k);
            if (vs != null) out.put(k, vs);
        }
        return out;
    }

    /** 全部物品（保序）。 */
    public List<ItemDef> items() {
        return items;
    }

    /** 按短 id 查（首条胜出）。 */
    public ItemDef byId(String id) {
        return byId.get(id);
    }

    public int size() {
        return items.size();
    }
}
