package dev.mccmd.model;

import java.util.List;
import java.util.Map;

/** 一条命令定义（含多版本参数）。 */
public final class CommandDef {
    public final String name;
    public final String category;
    public final String descriptionCn;
    public final String descriptionEn;
    public final String url;
    /** 版本号 → 参数列表（保持 JSON 顺序）。 */
    public final Map<String, List<ParamDef>> paramsByVersion;

    public CommandDef(String name, String category, String descriptionCn, String descriptionEn,
                      String url, Map<String, List<ParamDef>> paramsByVersion) {
        this.name = name;
        this.category = category;
        this.descriptionCn = descriptionCn;
        this.descriptionEn = descriptionEn;
        this.url = url;
        this.paramsByVersion = paramsByVersion;
    }
}
