package games.brennan.dungeontrain.client.survey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiMentionDetectorTest {

    private static final String EN = "en_us";

    @Test
    void onlyScoresBelowFiveGetTheNote() {
        assertTrue(AiMentionDetector.shouldShowNote(0, "AI slop", EN));
        assertTrue(AiMentionDetector.shouldShowNote(4, "AI slop", EN));
        assertFalse(AiMentionDetector.shouldShowNote(5, "AI slop", EN));
        assertFalse(AiMentionDetector.shouldShowNote(10, "AI slop", EN));
        assertFalse(AiMentionDetector.shouldShowNote(-1, "AI slop", EN));
    }

    @Test
    void lowScoreWithoutAMentionGetsNoNote() {
        assertFalse(AiMentionDetector.shouldShowNote(1, "too hard", EN));
        assertFalse(AiMentionDetector.shouldShowNote(1, "", EN));
        assertFalse(AiMentionDetector.shouldShowNote(1, "   ", EN));
        assertFalse(AiMentionDetector.shouldShowNote(1, null, EN));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "this is ai slop", "AI", "Ai generated garbage", "ai-generated", "stop using A.I.",
            "written by a.i", "the AI's stories", "artificial intelligence", "made with ChatGPT",
            "chat gpt wrote this", "gpt-4 books", "an LLM", "claude wrote it", "gemini", "copilot code",
            "midjourney art", "AI, again"
    })
    void englishMentionsMatch(String comment) {
        assertTrue(AiMentionDetector.mentionsAi(comment, EN), comment);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "train", "again", "AIN is fine", "AIS", "wait", "said", "air", "maid", "kill the raid",
            "ki attack", "ia bagus", "the mob ai is dumb", "mob AI is dumb", "the mobs' AI", "Villager AI",
            "enemy ai", "enemies AI", "NPC AI", "pathfinding AI", "the AI of the mobs", "mob-KI ist schlecht",
            "РОССИИ", "России"
    })
    void wordsAndGameplayAiDoNotMatch(String comment) {
        assertFalse(AiMentionDetector.mentionsAi(comment, EN), comment);
    }

    @Test
    void gameplayAiDoesNotHideARealMention() {
        assertTrue(AiMentionDetector.mentionsAi("mob AI is bad and the books are AI", EN));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "c'est de l'IA", "Das ist KI", "это ИИ", "це ШІ", "这是AI做的", "人工智能写的", "人工智慧", "AIが書いた",
            "人工知能", "인공지능", "ปัญญาประดิษฐ์", "inteligencia artificial", "inteligência artificial",
            "intelligence artificielle", "intelligenza artificiale", "künstliche Intelligenz",
            "kunstmatige intelligentie", "sztuczna inteligencja", "kecerdasan buatan",
            "inteligență artificială", "trí tuệ nhân tạo", "искусственный интеллект", "штучний інтелект"
    })
    void otherLanguagesMatch(String comment) {
        assertTrue(AiMentionDetector.mentionsAi(comment, EN), comment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"it_it", "ro_ro", "pt_br", "pt_pt"})
    void lowercaseAiIsAWordInSomeLanguages(String language) {
        assertFalse(AiMentionDetector.mentionsAi("ai giocatori non piace", language));
        assertTrue(AiMentionDetector.mentionsAi("fatto con AI", language));
        assertTrue(AiMentionDetector.mentionsAi("fatto con ChatGPT", language));
    }

    @Test
    void missingLanguageTreatsLowercaseAiAsAMention() {
        assertTrue(AiMentionDetector.mentionsAi("ai slop", null));
    }
}
