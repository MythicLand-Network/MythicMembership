package cn.mythicland.memberlib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemberNameStyleTest {
    @Test
    void formatsNamesWithoutLeakingPrefixDecorationsIntoNameColor() {
        MemberNameStyle style = new MemberNameStyle("&l§B[MVP&c+&b]");

        assertEquals("Alex", style.plainName("Alex"));
        assertEquals("&b", style.nameColor());
        assertEquals("&bAlex", style.coloredName("Alex"));
        assertEquals("&l§B[MVP&c+&b] &bAlex", style.prefixedName("Alex"));
        assertEquals("[MVP+] Alex", style.plainPrefixedName("Alex"));
    }

    @Test
    void emptyAndUncoloredPrefixesUseGrayWithoutAddingAnEmptyLabel() {
        assertEquals("&7Alex", new MemberNameStyle("").prefixedName("Alex"));
        assertEquals("[Player] &7Alex", new MemberNameStyle("[Player]").prefixedName("Alex"));
        assertEquals("&7Alex", new MemberNameStyle("&l[Player]").coloredName("Alex"));
    }
}
