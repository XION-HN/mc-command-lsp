package dev.mccmd.model;

import java.util.List;
import java.util.Map;

/** 物品/方块条目。 */
public final class ItemDef {
    public final String id;
    public final String namespace;
    public final String categoryCn;
    public final String categoryEn;
    public final String nameCn;
    public final String nameEn;
    public final String icon;
    public final String wikiUrl;
    public final boolean isBlock;
    public final boolean isItem;
    public final boolean stackable;
    public final int maxStack;
    /** 旧版数字数据值（无则空表）。 */
    public final List<SpecialValue> specialValues;
    /** 新版 block states（无则空表）。 */
    public final Map<String, List<String>> states;

    public ItemDef(String id, String namespace, String categoryCn, String categoryEn,
                   String nameCn, String nameEn, String icon, String wikiUrl,
                   boolean isBlock, boolean isItem, boolean stackable, int maxStack,
                   List<SpecialValue> specialValues, Map<String, List<String>> states) {
        this.id = id;
        this.namespace = namespace;
        this.categoryCn = categoryCn;
        this.categoryEn = categoryEn;
        this.nameCn = nameCn;
        this.nameEn = nameEn;
        this.icon = icon;
        this.wikiUrl = wikiUrl;
        this.isBlock = isBlock;
        this.isItem = isItem;
        this.stackable = stackable;
        this.maxStack = maxStack;
        this.specialValues = specialValues;
        this.states = states;
    }
}
