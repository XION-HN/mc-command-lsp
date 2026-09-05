package dev.mccmd.model;

import java.util.List;
import java.util.Map;

/** 一条命令定义（含多版本、每个版本可有多套签名 overloads）。 */
public final class CommandDef {
    public final String name;
    public final String category;
    public final String descriptionCn;
    public final String descriptionEn;
    public final String url;
    /**
     * 版本键 → 该版本全部可用签名（保持 JSON 顺序，不互相覆盖）。
     * 键 "*" 表示全版本通用（作为任何版本的最后兜底，见 CommandIndex 解析）。
     */
    public final Map<String, List<List<ParamDef>>> overloadsByVersion;

    public CommandDef(String name, String category, String descriptionCn, String descriptionEn,
                      String url, Map<String, List<List<ParamDef>>> overloadsByVersion) {
        this.name = name;
        this.category = category;
        this.descriptionCn = descriptionCn;
        this.descriptionEn = descriptionEn;
        this.url = url;
        this.overloadsByVersion = overloadsByVersion;
    }
}
