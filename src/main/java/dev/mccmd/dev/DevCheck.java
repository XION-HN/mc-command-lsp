package dev.mccmd.dev;

import dev.mccmd.data.CommandIndex;
import dev.mccmd.data.ItemIndex;
import dev.mccmd.model.CommandDef;
import dev.mccmd.model.ParamDef;
import dev.mccmd.model.ItemDef;

import java.io.IOException;
import java.util.List;

/** 开发自检：加载 data 并打印关键统计。 */
public final class DevCheck {
    public static void main(String[] args) throws IOException {
        String cmdPath = args.length > 0 ? args[0] : "data/commands.json";
        String itemPath = args.length > 1 ? args[1] : "data/items.json";

        CommandIndex commands = new CommandIndex(cmdPath);
        ItemIndex items = new ItemIndex(itemPath);

        System.out.println("commands.json: defaultVersion=" + commands.defaultVersion()
                + " 命令数=" + commands.commands().size());
        CommandDef give = commands.command("give");
        if (give != null) {
            for (String ver : List.of("1.16.220", commands.defaultVersion())) {
                List<ParamDef> ps = commands.paramsFor("give", ver);
                System.out.println("  give @" + ver + ": "
                        + (ps == null ? "无" : ps.stream()
                            .map(p -> p.name + (p.required ? "" : "?") + ":" + p.type)
                            .toList()));
            }
        }

        System.out.println("items.json: 物品数=" + items.size());
        ItemDef wool = items.byId("wool");
        System.out.println("  wool: " + (wool == null ? "缺失"
                : wool.nameCn + " | special_values=" + wool.specialValues.size()
                    + " states=" + wool.states.size()));
        ItemDef planks = items.byId("planks");
        System.out.println("  planks: " + (planks == null ? "缺失"
                : planks.nameCn + " | special_values=" + planks.specialValues.size()));
        System.out.println("OK");
    }
}
