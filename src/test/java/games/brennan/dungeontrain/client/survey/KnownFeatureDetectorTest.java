package games.brennan.dungeontrain.client.survey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Positives are real answers from the relay's feedback, 2026-09-14 to 2026-11-12. */
class KnownFeatureDetectorTest {

    private static final String EN = "en_us";
    private static final Set<KnownFeature> ALL = EnumSet.allOf(KnownFeature.class);

    private static List<KnownFeature> detect(String comment) {
        return KnownFeatureDetector.detect(-1, comment, EN, ALL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SAVING",
            "quit and save",
            "I would like a way to save my progress in a run if I need to get off",
            "add checkpoints the ender chest are nice",
            "A pause button to be able to leave mid run",
            "让游戏可以保存",
            "把存档功能加上",
            "Как сохранить прогресс?",
    })
    void saveQuit(String comment) {
        assertEquals(List.of(KnownFeature.SAVE_QUIT), detect(comment), comment);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I NEED a backpack, but maybe I just haven't found one",
            "我们需要更多背包",
            "я бы добавил мод на рюкзаки",
            "Poner mochilas como el Sophiticate",
            "portable storage and more rpg mods",
    })
    void backpack(String comment) {
        assertEquals(List.of(KnownFeature.BACKPACK), detect(comment), comment);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Edible Backpacks should be easier to find in the first 200-300 carriages",
            "i'd like something like a backpack mod. the edible backpacks arent enough imo",
            "把Edible Backpaok修改成可堆叠的物品",
    })
    void backpackSkippedWhenTheyKnow(String comment) {
        assertTrue(detect(comment).isEmpty(), comment);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I would LOVE a way to play this multiplayer with my friend(s)",
            "Multiplayer, also fun game",
            "мультиплеер",
            "感觉朋友们可能更喜欢多人联机的游戏",
    })
    void multiplayer(String comment) {
        assertEquals(List.of(KnownFeature.MULTIPLAYER), detect(comment), comment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"我想要一键整理", "auto sort is needed pls", "inventory sorting mod", "Better Inventory managment"})
    void sorting(String comment) {
        assertEquals(List.of(KnownFeature.SORTING), detect(comment), comment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"add trashslot", "a trash slot please", "trash can for items", "垃圾桶"})
    void trashSlot(String comment) {
        assertEquals(List.of(KnownFeature.TRASH_SLOT), detect(comment), comment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A compat with JEI would be helpful", "im missing a goal and dont know what i can craft"})
    void jei(String comment) {
        assertEquals(List.of(KnownFeature.JEI), detect(comment), comment);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "more carriage varieties pls",
            "add bosses",
            "THERE IS NO END. ITS JUST SUFFFERING",
            "the jeidi knight",
            "trashy loot",
            "unsorted thoughts",
            "",
            "   ",
    })
    void unrelatedAnswersEarnNothing(String comment) {
        assertTrue(detect(comment).isEmpty(), comment);
    }

    @Test
    void nullCommentEarnsNothing() {
        assertTrue(KnownFeatureDetector.detect(3, null, EN, ALL).isEmpty());
    }

    @Test
    void aiNeedsALowNpsScore() {
        assertEquals(List.of(KnownFeature.AI), KnownFeatureDetector.detect(2, "AI slop", EN, ALL));
        assertTrue(KnownFeatureDetector.detect(9, "AI slop", EN, ALL).isEmpty());
        assertTrue(KnownFeatureDetector.detect(-1, "AI slop", EN, ALL).isEmpty());
    }

    @Test
    void unavailableFeaturesAreSkipped() {
        Set<KnownFeature> noTrashSlot = EnumSet.complementOf(EnumSet.of(KnownFeature.TRASH_SLOT));
        assertTrue(KnownFeatureDetector.detect(-1, "add trashslot", EN, noTrashSlot).isEmpty());
    }

    @Test
    void atMostTwoNotesInTableOrder() {
        assertEquals(List.of(KnownFeature.SAVE_QUIT, KnownFeature.BACKPACK),
                detect("trash slot, JEI, backpacks and a save button"));
    }
}
