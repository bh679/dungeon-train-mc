package games.brennan.dungeontrain.player;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Names the Ender Chest a player is about to open, when it isn't the default one.
 *
 * <p>The same block can now open several different chests: EnderChestPersistence keeps one per game mode,
 * {@link DifficultyPartition} adds one per vanilla difficulty, and a Free Play run is locked onto its own
 * (see {@code EnderChestLockBridge}). Without a label, opening the wrong profile's chest and finding it
 * empty looks exactly like having lost everything — which is the single most alarming way for this feature
 * to present itself.</p>
 *
 * <p>Only <em>deviations</em> from the default are named, and the default (survival + Normal) is left
 * completely alone — an ordinary run sees vanilla's own title, unchanged. Free Play <em>replaces</em> the
 * rest rather than adding to it, because that run is pinned to one slot regardless of mode or difficulty,
 * so naming a difficulty alongside it would describe a chest that isn't the one being opened.</p>
 *
 * <p>The mode and difficulty names come from vanilla's own {@code getShortDisplayName()} /
 * {@code getDisplayName()}, so they are already translated everywhere Minecraft is — only the
 * parenthetical wrapper and the Free Play wording are DT's.</p>
 *
 * <p>Pure: the whole decision is a function of (cheated, mode, difficulty, isolation-on), which is exactly
 * the input set {@code EnderChestLockBridge}'s slot-key provider uses. Deriving both from the same inputs
 * is what stops the label and the actual slot from ever describing different chests.</p>
 */
public final class EnderChestLabel {

    /** The game mode whose chest is the default one, i.e. left unlabelled. */
    public static final GameType DEFAULT_MODE = GameType.SURVIVAL;

    public static final String KEY_PROFILE = "container.dungeontrain.enderchest.profile";
    public static final String KEY_SEPARATOR = "container.dungeontrain.enderchest.separator";
    public static final String KEY_FREE_PLAY = "container.dungeontrain.enderchest.free_play";

    private EnderChestLabel() {}

    /**
     * The chest's title, or empty to leave vanilla's alone.
     *
     * @param baseTitle       vanilla's {@code container.enderchest} title
     * @param cheated         whether this run is Free Play (locked onto its own slot)
     * @param mode            the player's game mode
     * @param difficulty      the level's difficulty
     * @param isolationByDifficulty whether the difficulty actually partitions the chest (config)
     */
    public static Optional<Component> titleFor(Component baseTitle, boolean cheated, GameType mode,
                                               Difficulty difficulty, boolean isolationByDifficulty) {
        List<Component> parts = new ArrayList<>(2);
        if (cheated) {
            // Pinned to the Free Play slot — mode and difficulty are not what selected this chest.
            parts.add(Component.translatable(KEY_FREE_PLAY));
        } else {
            if (mode != null && mode != DEFAULT_MODE) {
                parts.add(mode.getShortDisplayName());
            }
            if (isolationByDifficulty && difficulty != null
                    && !DifficultyPartition.suffixFor(difficulty).isEmpty()) {
                parts.add(difficulty.getDisplayName());
            }
        }
        if (parts.isEmpty()) {
            return Optional.empty(); // the default chest: no label, no change at all
        }
        // Vanilla's own title is an argument rather than baked into the key, so a translated client keeps
        // its own word for "Ender Chest" and only the parenthetical comes from DT.
        return Optional.of(Component.translatable(KEY_PROFILE, baseTitle, join(parts)));
    }

    /** Join the named deviations with the separator key, e.g. {@code Adventure · Hard}. */
    private static Component join(List<Component> parts) {
        MutableComponent out = parts.get(0).copy();
        for (int i = 1; i < parts.size(); i++) {
            out = out.append(Component.translatable(KEY_SEPARATOR)).append(parts.get(i));
        }
        return out;
    }
}
