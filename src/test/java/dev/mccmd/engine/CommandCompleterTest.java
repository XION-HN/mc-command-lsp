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
        CommandIndex commands = new CommandIndex("data/commands.json");
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
        assertTrue(r.suggestions.stream().allMatch(s -> s.insertText.startsWith("dia")));
    }
}
