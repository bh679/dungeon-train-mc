package games.brennan.dungeontrain.compat;

import games.brennan.pigmanvillagers.api.PigmanVillagersApi;
import net.minecraft.nbt.CompoundTag;

/**
 * Bridge into the <b>Pigman Villagers</b> sibling mod ({@code pigmanvillagers}, jarJar'd inside DT,
 * so always present): every villager rolls once for being a pigman and saves the result.
 *
 * <p>A template captured in the editor freezes one villager's roll into its NBT, so without this
 * every copy stamped from it would repeat that villager's answer instead of rolling its own chance.
 * Call {@link #freshRoll} on entity NBT stamped from a <em>template</em> — carriage contents, part
 * decor, dimensional-carriage occupants, leased shared builds — and never on a restore of the same
 * villager ({@code ContentsEntitySnapshot}), which must come back as it left.</p>
 */
public final class PigmanVillagersBridge {

    private PigmanVillagersBridge() {}

    /** Clears the saved pigman roll so the villager spawned from {@code entityNbt} rolls its own. */
    public static void freshRoll(CompoundTag entityNbt) {
        PigmanVillagersApi.clearRoll(entityNbt);
    }
}
