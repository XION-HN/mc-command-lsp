package dev.mccmd.model;

import java.util.List;

/** 命令的一个参数。 */
public final class ParamDef {
    public final String name;
    public final String type;
    public final boolean required;
    public final boolean repeat;
    /** 固定枚举候选；可为 null。 */
    public final List<String> values;
    /** 数据值建议来源（如 "special_values"：取 items.json 对应物品的数据值）。 */
    public final String suggestFrom;
    public final String descriptionCn;

    public ParamDef(String name, String type, boolean required, boolean repeat,
                    List<String> values, String suggestFrom, String descriptionCn) {
        this.name = name;
        this.type = type;
        this.required = required;
        this.repeat = repeat;
        this.values = values;
        this.suggestFrom = suggestFrom;
        this.descriptionCn = descriptionCn;
    }
}
