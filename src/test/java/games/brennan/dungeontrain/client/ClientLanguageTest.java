package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the Chinese-client gate. Only the {@code String} overload is exercised —
 * the no-arg form reads {@code Minecraft.getInstance()} and has no business in a unit test.
 */
class ClientLanguageTest {

    @Test
    void everyChineseLocaleIsInTheFamily() {
        assertTrue(ClientLanguage.isChinese("zh_cn"));
        assertTrue(ClientLanguage.isChinese("zh_tw"));
        assertTrue(ClientLanguage.isChinese("zh_hk"));
        // Classical Chinese — Minecraft renders it in Chinese characters, and LanguageFamily
        // deliberately collapses it in.
        assertTrue(ClientLanguage.isChinese("lzh"));
    }

    @Test
    void otherLocalesAreNot() {
        assertFalse(ClientLanguage.isChinese("en_us"));
        assertFalse(ClientLanguage.isChinese("ja_jp"));
        assertFalse(ClientLanguage.isChinese("ko_kr"));
        assertFalse(ClientLanguage.isChinese("vi_vn"));
    }

    @Test
    void anAbsentLocaleIsNotChinese() {
        // A null locale means the client isn't up yet. LanguageFamily.of(null) answers "en" — the
        // default for untagged content — so the gate must not be satisfied by that default.
        assertFalse(ClientLanguage.isChinese((String) null));
        assertFalse(ClientLanguage.isChinese(""));
    }

    @Test
    void caseAndStrayPunctuationDoNotFoolTheGate() {
        assertTrue(ClientLanguage.isChinese("ZH_CN"));
        assertFalse(ClientLanguage.isChinese("zhulu"));
    }
}
