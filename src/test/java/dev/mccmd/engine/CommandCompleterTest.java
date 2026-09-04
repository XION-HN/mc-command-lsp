package dev.mccmd.engine;

import dev.mccmd.data.CommandIndex;
import dev.mccmd.data.ItemIndex;
import dev.mccmd.engine.CommandCompleter.Result;
import dev.mccmd.engine.CommandCompleter.Suggestion;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class CommandCompleterTest {

    private static CommandCompleter cc;

    @BeforeClass
    public static void load() throws Exception {
        CommandIndex commands = new CommandIndex("data/commands.json", "data/grammar_extra.json");
        ItemIndex items = new ItemIndex("data/items.json");
        cc = new CommandCompleter(commands, items);
    }

    @Test
    public void commandName_prefixListsMatchingCommands() {
        Result r = cc.complete("/giv", "1.21.60");
        assertFalse(r.suggestions.isEmpty());
        assertTrue(r.suggestions.stream().anyMatch(s -> "give".equals(s.insertText)));
        assertTrue(r.suggestions.stream().allMatch(s -> "command".equals(s.kind)));
    }

    @Test
    public void unknownCommand_reportsError() {
        Result r = cc.complete("/nope", "1.21.60");
        assertTrue(r.errors.stream().anyMatch(e -> e.contains("未知命令")));
    }

    @Test
    public void giveNew_afterPlayerAndItem_movesToAmount() {
        Result r = cc.complete("/give @p diamond ", "1.21.60");
        assertEquals("数量", r.currentParam);
        assertTrue(r.suggestions.isEmpty());
    }

    @Test
    public void giveOld_dataParam_suggestsWoolSpecialValues() {
        // 旧版 give：give <player> <item> [amount] [data(special_values)]
        Result r = cc.complete("/give @p wool 1 ", "1.16.220");
        assertEquals("数据值", r.currentParam);
        assertTrue("应有 wool special_values 候选",
                r.suggestions.stream().anyMatch(s -> "0 - 白色羊毛".equals(s.label)));
    }

    @Test
    public void itemParam_filtersByPrefix() {
        Result r = cc.complete("/give @p dia", "1.21.60");
        assertEquals("物品", r.currentParam);
        assertTrue(r.suggestions.stream().anyMatch(s -> "diamond".equals(s.insertText)));
        // 模糊匹配允许含中文/英文名命中的项，但主命中仍是 dia 前缀
        assertTrue(r.suggestions.stream().anyMatch(s -> s.insertText.startsWith("dia")));
    }

    @Test
    public void itemParam_matchesChineseName() {
        Result r = cc.complete("/give @p 钻", "1.21.60");
        assertTrue("应按中文名匹配到钻石",
                r.suggestions.stream().anyMatch(s -> "diamond".equals(s.insertText)));
    }

    @Test
    public void playerParam_suggestsSelectors() {
        Result r = cc.complete("/give @", "1.21.60");
        assertEquals("玩家", r.currentParam);
        assertTrue(r.suggestions.stream().anyMatch(s -> "@a".equals(s.insertText)));
    }

    @Test
    public void selectorBracket_suggestsKeys() {
        Result r = cc.complete("/give @a[n", "1.21.60");
        assertTrue("选择器括号内应补 name=/tag= 等键",
                r.suggestions.stream().anyMatch(s -> s.insertText.endsWith("name=")));
        assertTrue(r.suggestions.stream().anyMatch(s -> s.insertText.contains("@a[")));
    }

    @Test
    public void execute_run_suggestsSubAndNestedCommand() {
        Result r1 = cc.complete("/execute as @a ru", "1.21.60");
        assertTrue("未到 run 前应给 run 子命令候选",
                r1.suggestions.stream().anyMatch(s -> "run".equals(s.insertText)));
        Result r2 = cc.complete("/execute as @a run giv", "1.21.60");
        assertTrue("run 后递归补命令名",
                r2.suggestions.stream().anyMatch(s -> "give".equals(s.insertText)));
        Result r3 = cc.complete("/execute as @a run give @p wool ", "1.21.60");
        // 数量(int 无可选项)被可选跳词跳过 → 落到数据值(有 wool 特殊值候选)
        assertEquals("数据值", r3.currentParam);
        assertTrue(r3.suggestions.stream().anyMatch(s -> "0 - 白色羊毛".equals(s.label)));
    }

    @Test
    public void setblock_coordinatesAndModeEnum() {
        Result r = cc.complete("/setblock ~ ~ ~ wool 1 des", "1.21.60");
        assertEquals("模式", r.currentParam);
        assertTrue(r.suggestions.stream().anyMatch(s -> "destroy".equals(s.insertText)));
        assertTrue(r.errors.isEmpty());
    }

    @Test
    public void time_queryEnum() {
        Result r = cc.complete("/time que", "1.21.60");
        assertEquals("操作", r.currentParam);
        assertTrue(r.suggestions.stream().anyMatch(s -> "query".equals(s.insertText)));
    }

    @Test
    public void setblock_compactCoords_skipsCoordinatesGroup() {
        Result r = cc.complete("/setblock ~~~ stone ", "1.21.60");
        // stone 无可选数据值候选 → 自动跳到 模式(destroy/keep/replace)
        assertEquals("模式", r.currentParam);
        assertTrue(r.suggestions.stream().anyMatch(s -> "destroy".equals(s.insertText)));
    }

    @Test
    public void selectorBracket_valueCompletion() {
        Result r = cc.complete("/give @a[gamemode=crea", "1.21.60");
        assertTrue(r.suggestions.stream()
                .anyMatch(s -> "@a[gamemode=creative".equals(s.insertText)));
        Result t = cc.complete("/give @a[type=zom", "1.21.60");
        assertTrue(t.suggestions.stream()
                .anyMatch(s -> "@a[type=zombie".equals(s.insertText)));
    }

    @Test
    public void setblock_compactOffsetCoords_thenDataThenMode() {
        Result r = cc.complete("/setblock ~13~13~13 wool 1 des", "1.21.60");
        assertEquals("模式", r.currentParam);
        assertTrue(r.suggestions.stream().anyMatch(s -> "destroy".equals(s.insertText)));
        assertTrue(r.errors.isEmpty());
    }
}
