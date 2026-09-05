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

    // ---- 选择器括号内值补全（仿 Blockception）----

    @Test
    public void selector_bracketAllAttributeKeys() {
        Result r = cc.complete("/kill @a[", "1.21.60");
        assertTrue("应含完整属性键 name=/type=/scores=/c= 等",
                r.suggestions.stream().anyMatch(s -> "@a[name=".equals(s.insertText)));
        assertTrue(r.suggestions.stream().anyMatch(s -> "@a[type=".equals(s.insertText)));
        assertTrue(r.suggestions.stream().anyMatch(s -> "@a[scores=".equals(s.insertText)));
        assertTrue(r.suggestions.stream().anyMatch(s -> "@a[c=".equals(s.insertText)));
    }

    @Test
    public void selector_attributeKey_hasCnDetail() {
        Result r = cc.complete("/kill @a[", "1.21.60");
        var s = r.suggestions.stream().filter(x -> "@a[tag=".equals(x.insertText)).findFirst();
        assertTrue("属性键应带中文说明", s.isPresent() && s.get().detail != null
                && s.get().detail.contains("标签"));
    }

    @Test
    public void selector_valueCount_hasLimits() {
        Result r = cc.complete("/kill @e[c=", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "@e[c=1".equals(s.insertText)));
        assertTrue(r.suggestions.stream().anyMatch(s -> "@e[c=-1".equals(s.insertText)));
    }

    @Test
    public void selector_valueNegative_type() {
        Result r = cc.complete("/kill @e[type=!zom", "1.21.60");
        assertTrue("应支持排除 !type",
                r.suggestions.stream().anyMatch(s -> "@e[type=!zombie".equals(s.insertText)));
    }

    @Test
    public void selector_valueCoordinate_relOrAbs() {
        Result r = cc.complete("/kill @e[x=", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "@e[x=~1".equals(s.insertText)));
        assertTrue(r.suggestions.stream().anyMatch(s -> "@e[x=1".equals(s.insertText)));
    }

    @Test
    public void selector_valueScores_nestedBrace() {
        Result r = cc.complete("/kill @a[scores=", "1.21.60");
        assertTrue("scores= 后应给 {}",
                r.suggestions.stream().anyMatch(s -> s.insertText.contains("scores={")));
    }

    @Test
    public void selector_bracketFilter_isValueSuffix() {
        // 客户端词尾过滤键应为"用户正在敲的值"，而非整词 @a[type=...
        Result r = cc.complete("/kill @a[type=zom", "1.21.60");
        var s = r.suggestions.stream().filter(x -> x.insertText.contains("zombie")).findFirst();
        assertTrue(s.isPresent());
        assertEquals("zombie", s.get().filter);
    }

    @Test
    public void setblock_compactOffsetCoords_thenDataThenMode() {
        Result r = cc.complete("/setblock ~13~13~13 wool 1 des", "1.21.60");
        assertEquals("模式", r.currentParam);
        assertTrue(r.suggestions.stream().anyMatch(s -> "destroy".equals(s.insertText)));
        assertTrue(r.errors.isEmpty());
    }

    // ---- M5：多签名 overload 回溯 ----

    @Test
    public void effect_clearOverload_disambiguatesByToken() {
        // 输入 clear 前缀 → 命中 clear 字面量候选；效果签名不会给出（clear 不在效果表）
        Result r = cc.complete("/effect @p cl", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "clear".equals(s.insertText)));
        // 输入已完整 clear + 空格 → clear 签名到底，无后续建议且无报错
        Result r2 = cc.complete("/effect @p clear ", "1.21.60");
        assertTrue(r2.errors.isEmpty());
    }

    @Test
    public void effect_effectOverload_suggestsEffectIds() {
        Result r = cc.complete("/effect @p ", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "clear".equals(s.insertText)));
        assertTrue("应含效果 id 候选",
                r.suggestions.stream().anyMatch(s -> "speed".equals(s.insertText)));
    }

    @Test
    public void effect_effectOverload_partialEffectPrefix() {
        Result r = cc.complete("/effect @p spe", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "speed".equals(s.insertText)));
        assertTrue(r.suggestions.stream().noneMatch(s -> "clear".equals(s.insertText)));
    }

    @Test
    public void effect_fullGive_withDuration() {
        // effect <player> <effect> 30 1 true
        Result r = cc.complete("/effect @p speed 30 1 tr", "1.21.60");
        assertTrue("隐藏粒子应建议 true",
                r.suggestions.stream().anyMatch(s -> "true".equals(s.insertText)));
        assertTrue(r.errors.isEmpty());
    }

    @Test
    public void tp_coordsOverload_consumesThreeCoords() {
        Result r = cc.complete("/tp @p 10 20 30", "1.21.60");
        assertTrue("坐标签名应无报错", r.errors.isEmpty());
    }

    @Test
    public void tp_playerOverload_disambiguatesFromCoords() {
        // 第 3 个 token 是玩家名而非坐标 → 命中「tp <p> <p>」签名
        Result r = cc.complete("/tp @p Notch", "1.21.60");
        assertTrue(r.errors.isEmpty());
        // 坐标签名应因 Notch 不是坐标而失效，不该有 x 相关报错
    }

    @Test
    public void tp_partialCoords_midCoordinate() {
        Result r = cc.complete("/tp @p ~ 10", "1.21.60");
        assertTrue(r.errors.isEmpty());
    }

    @Test
    public void scoreboard_objectivesAdd_fullPath() {
        Result r = cc.complete("/scoreboard objectives add foo dummy", "1.21.60");
        assertTrue(r.errors.isEmpty());
    }

    @Test
    public void scoreboard_playersReset_suggestsPlayer() {
        Result r = cc.complete("/scoreboard players res", "1.21.60");
        assertTrue("players reset 应给出操作候选",
                r.suggestions.stream().anyMatch(s -> "reset".equals(s.insertText)));
    }

    @Test
    public void execute_grammar_suggestsSubcommands() {
        Result r = cc.complete("/execute ", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "as".equals(s.insertText)));
        assertTrue(r.suggestions.stream().anyMatch(s -> "if".equals(s.insertText)));
        assertTrue(r.suggestions.stream().anyMatch(s -> "run".equals(s.insertText)));
    }

    @Test
    public void execute_as_afterTarget_stillSubcommands() {
        Result r = cc.complete("/execute as @p po", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "positioned".equals(s.insertText)));
        assertTrue(r.errors.isEmpty());
    }

    @Test
    public void execute_positioned_branchesToAsOver() {
        Result r = cc.complete("/execute positioned ", "1.21.60");
        assertTrue("positioned 后可 as/over",
                r.suggestions.stream().anyMatch(s -> "as".equals(s.insertText)));
        assertTrue(r.suggestions.stream().anyMatch(s -> "over".equals(s.insertText)));
    }

    @Test
    public void execute_if_suggestsConditionKinds() {
        Result r = cc.complete("/execute if ", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "block".equals(s.insertText)));
        assertTrue(r.suggestions.stream().anyMatch(s -> "entity".equals(s.insertText)));
        assertTrue(r.suggestions.stream().anyMatch(s -> "score".equals(s.insertText)));
    }

    @Test
    public void execute_ifBlock_suggestsBlocks() {
        Result r = cc.complete("/execute if block ~ ~ ~ sto", "1.21.60");
        assertTrue("if block 后应补方块",
                r.suggestions.stream().anyMatch(s -> "stone".equals(s.insertText)));
        assertTrue(r.errors.isEmpty());
    }

    @Test
    public void execute_ifEntity_chainsBackToSubcommand() {
        Result r = cc.complete("/execute if entity @e[type=zombie] po", "1.21.60");
        assertTrue("条件后应可继续子命令链",
                r.suggestions.stream().anyMatch(s -> "positioned".equals(s.insertText)));
    }

    @Test
    public void execute_facing_branchesToEntity() {
        Result r = cc.complete("/execute facing ", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "entity".equals(s.insertText)));
    }

    @Test
    public void execute_in_suggestsDimensions() {
        Result r = cc.complete("/execute in ", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "the_end".equals(s.insertText)));
    }

    @Test
    public void execute_deepChain_thenRun_thenCommand() {
        Result r = cc.complete("/execute as @a at @s positioned ~ ~ ~ run setb", "1.21.60");
        assertTrue("run 后应补命令", r.suggestions.stream()
                .anyMatch(s -> "setblock".equals(s.insertText)));
    }

    // ---- 新命令扩覆盖 ----

    @Test
    public void trigger_twoOverloads() {
        // 只有记分项 → 可选操作 add/set
        Result r = cc.complete("/trigger foo ", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "add".equals(s.insertText)));
        assertTrue(r.suggestions.stream().anyMatch(s -> "set".equals(s.insertText)));
    }

    @Test
    public void damage_enumCause_andSelector() {
        Result r = cc.complete("/damage @e 5 ", "1.21.60");
        assertTrue("damage 应建议伤害类型",
                r.suggestions.stream().anyMatch(s -> "fall".equals(s.insertText)));
    }

    @Test
    public void structure_saveVsLoad_viaActionEnum() {
        Result r1 = cc.complete("/structure sa", "1.21.60");
        assertTrue(r1.suggestions.stream().anyMatch(s -> "save".equals(s.insertText)));
        Result r2 = cc.complete("/structure lo", "1.21.60");
        assertTrue(r2.suggestions.stream().anyMatch(s -> "load".equals(s.insertText)));
    }

    @Test
    public void testforblock_blockNameCompletion() {
        Result r = cc.complete("/testforblock ~ ~ ~ sto", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "stone".equals(s.insertText)));
    }

    @Test
    public void music_enumActions() {
        Result r = cc.complete("/music @p ", "1.21.60");
        assertTrue(r.suggestions.stream().anyMatch(s -> "queue".equals(s.insertText)));
        assertTrue(r.suggestions.stream().anyMatch(s -> "stop".equals(s.insertText)));
    }

    // ---- 选择器括号提示 + 单行诊断 ----

    @Test
    public void selector_fullBase_suggestsBracketParam() {
        // 输入完整 @s 后，应提示可加 [可选参数]（kill 首参是选择器位）
        Result r = cc.complete("/kill @s", "1.21.60");
        assertTrue("@s 后应给出 @s[ 候选",
                r.suggestions.stream().anyMatch(s -> "@s[".equals(s.insertText)));
        assertTrue("也应保留纯 @s",
                r.suggestions.stream().anyMatch(s -> "@s".equals(s.insertText)));
    }

    @Test
    public void diagnose_unknownCommand_reportsLine() {
        java.util.List<CommandCompleter.LineDiag> d = cc.diagnose("/nope arg", "1.21.60");
        assertEquals(1, d.size());
        assertTrue(d.get(0).message.contains("未知命令"));
        assertEquals(1, d.get(0).startCol); // '/' 占 1 列
        assertEquals("/nope".length(), d.get(0).endCol);
    }

    @Test
    public void diagnose_validCommand_noError() {
        assertTrue(cc.diagnose("/give @p diamond 1", "1.21.60").isEmpty());
        assertTrue(cc.diagnose("/execute as @a at @s run say hi", "1.21.60").isEmpty());
    }

    @Test
    public void diagnose_blankOrComment_noError() {
        assertTrue(cc.diagnose("", "1.21.60").isEmpty());
        assertTrue(cc.diagnose("   ", "1.21.60").isEmpty());
        assertTrue(cc.diagnose("# comment", "1.21.60").isEmpty());
    }

    @Test
    public void diagnose_badEnumParam_reports() {
        java.util.List<CommandCompleter.LineDiag> d = cc.diagnose("/time moon", "1.21.60");
        assertFalse("moon 非合法操作，应报错", d.isEmpty());
    }

    @Test
    public void diagnose_executeBadSubcommand_reports() {
        java.util.List<CommandCompleter.LineDiag> d = cc.diagnose("/execute xyz @a", "1.21.60");
        assertFalse("xyz 不是 execute 子命令，应报错", d.isEmpty());
    }
}
