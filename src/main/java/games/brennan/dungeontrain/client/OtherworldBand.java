package games.brennan.dungeontrain.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Whether an entity stands where the End or Nether band has taken over the overworld — the point
 * past which instruments that stop working in the real End and Nether (clocks, spawn compasses)
 * should stop working too.
 *
 * <p>"Taken over" is each band's music crossover ({@code 0.5}), so the instruments switch where the
 * music does. Both intensities are {@code 0} on non-train worlds and when a band is turned off in
 * config, so this is then always {@code false}.</p>
 */
public final class OtherworldBand {

    private OtherworldBand() {}

    /** True when {@code holder} is in the overworld inside the End or Nether band. */
    public static boolean at(Entity holder) {
        if (holder == null || !holder.level().dimension().equals(Level.OVERWORLD)) return false;
        double x = holder.getX();
        return ClientVoidBand.endSkyIntensityAt(x) >= ClientVoidBand.MUSIC_CROSSOVER
                || ClientNetherBand.netherIntensityAt(x) >= ClientNetherBand.MUSIC_CROSSOVER;
    }
}
