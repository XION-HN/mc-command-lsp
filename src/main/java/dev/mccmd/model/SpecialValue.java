package dev.mccmd.model;

/** 旧版数据值（数字 data 时代）。 */
public final class SpecialValue {
    public final int value;
    public final String nameCn;
    public final String nameEn;
    public final String icon;

    public SpecialValue(int value, String nameCn, String nameEn, String icon) {
        this.value = value;
        this.nameCn = nameCn;
        this.nameEn = nameEn;
        this.icon = icon;
    }
}
