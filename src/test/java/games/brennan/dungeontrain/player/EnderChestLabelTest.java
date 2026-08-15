package games.brennan.dungeontrain.player;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which Ender Chest the title names. Pure component composition — no world, and deliberately no rendered
 * strings: the assertions walk the translation keys, so they hold without a loaded language pack.
 */
class EnderChestLabelTest {

    private static final Component BASE = Component.translatable("container.enderchest");

    /** The label's parts, as translation keys, in order. Empty when vanilla's title is left alone. */
    private static List<String> keys(Optional<Component> title) {
        List<String> out = new ArrayList<>();
        title.ifPresent(t -> collect(t, out));
        return out;
    }

    private static void collect(Component c, List<String> out) {
        if (c.getContents() instanceof TranslatableContents tc) {
            out.add(tc.getKey());
            for (Object arg : tc.getArgs()) {
                if (arg instanceof Component nested) {
                    collect(nested, out);
                }
            }
        }
        for (Component sibling : c.getSiblings()) {
            collect(sibling, out);
        }
    }

    private static Optional<Component> title(boolean cheated, GameType mode, Difficulty difficulty,
                                             boolean isolation) {
        return EnderChestLabel.titleFor(BASE, cheated, mode, difficulty, isolation);
    }

    @Test
    void theDefaultProfileIsNotLabelledAtAll() {
        // Survival + Normal is the chest everyone already has; it must look exactly like vanilla.
        assertTrue(title(false, GameType.SURVIVAL, Difficulty.NORMAL, true).isEmpty());
    }

    @Test
    void aNonDefaultDifficultyIsNamed() {
        assertEquals(
            List.of(EnderChestLabel.KEY_PROFILE, "container.enderchest", "options.difficulty.hard"),
            keys(title(false, GameType.SURVIVAL, Difficulty.HARD, true)));
        assertEquals(
            List.of(EnderChestLabel.KEY_PROFILE, "container.enderchest", "options.difficulty.peaceful"),
            keys(title(false, GameType.SURVIVAL, Difficulty.PEACEFUL, true)));
    }

    @Test
    void aNonDefaultGameModeIsNamed() {
        assertEquals(
            List.of(EnderChestLabel.KEY_PROFILE, "container.enderchest", "selectWorld.gameMode.adventure"),
            keys(title(false, GameType.ADVENTURE, Difficulty.NORMAL, true)),
            "the mode's own vanilla name is used, so it is already translated everywhere");
    }

    @Test
    void bothDeviationsAreNamedTogether() {
        assertEquals(
            List.of(EnderChestLabel.KEY_PROFILE, "container.enderchest", "selectWorld.gameMode.adventure",
                EnderChestLabel.KEY_SEPARATOR, "options.difficulty.hard"),
            keys(title(false, GameType.ADVENTURE, Difficulty.HARD, true)),
            "mode then difficulty, joined by the separator");
    }

    @Test
    void freePlayReplacesModeAndDifficultyRatherThanJoiningThem() {
        // A cheated run is pinned to one slot whatever the mode/difficulty, so naming either would
        // describe a chest other than the one being opened.
        List<String> label = keys(title(true, GameType.CREATIVE, Difficulty.HARD, true));
        assertEquals(
            List.of(EnderChestLabel.KEY_PROFILE, "container.enderchest", EnderChestLabel.KEY_FREE_PLAY),
            label);
    }

    @Test
    void withIsolationOffTheDifficultyIsNotClaimed() {
        // Config off ⇒ the difficulty doesn't partition the chest, so the title must not say it does.
        assertTrue(title(false, GameType.SURVIVAL, Difficulty.HARD, false).isEmpty());
        // The game-mode split is ECP's own and survives the config being off.
        assertTrue(keys(title(false, GameType.ADVENTURE, Difficulty.HARD, false))
            .stream().noneMatch(k -> k.startsWith("options.difficulty.")));
    }

    @Test
    void missingModeOrDifficultyIsTreatedAsDefault() {
        assertTrue(title(false, null, null, true).isEmpty());
    }
}
