package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.block.VaultGreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Which vault rewards are rare finds ({@code VaultFinds}): the relay numbers every one per item id, the
 * first ever is told so in chat, and those from an ominous or green vault carry their number.
 *
 * <p>A hand-picked list rather than a weight threshold: netherite and the Vista mirror are rare by
 * weight too, and were left out on purpose (Brennan, 2026-11-07).</p>
 */
public final class VaultRarity {

    static final Set<String> RARE_IDS = Set.of("minecraft:mace", "minecraft:heavy_core", "minecraft:trident");
    static final String SPAWN_EGG_SUFFIX = "_spawn_egg";

    /** The vault's type, as the relay names it. */
    public enum Kind {
        TRIAL("trial"), OMINOUS("ominous"), GREEN("green");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** Ominous and green finds show their number; a plain trial vault's only count. */
        public boolean numbered() {
            return this != TRIAL;
        }
    }

    private VaultRarity() {}

    public static boolean isRare(ResourceLocation itemId) {
        return RARE_IDS.contains(itemId.toString()) || itemId.getPath().endsWith(SPAWN_EGG_SUFFIX);
    }

    /** Green wins: a green vault never sets ominous, but a hand-edited state might set both. */
    public static Kind kindOf(BlockState state) {
        if (VaultGreen.isGreen(state)) return Kind.GREEN;
        if (state.hasProperty(VaultBlock.OMINOUS) && state.getValue(VaultBlock.OMINOUS)) return Kind.OMINOUS;
        return Kind.TRIAL;
    }
}
