package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Whether an entity stands somewhere clocks and spawn compasses should stop working, as they do in
 * the real End and Nether: inside the End or Nether band, or anywhere from the Far Lands to the end
 * of that run ({@link WorldGenCycle#isAtOrPastFarLands}).
 *
 * <p>For the bands, "inside" means past each band's music crossover ({@code 0.5}), so the instruments
 * switch where the music does; the Far Lands likewise switch halfway through their entry crossfade.
 * All three checks are off on non-train worlds and when their band is turned off in config.</p>
 */
public final class OtherworldBand {

    private OtherworldBand() {}

    /** True when {@code holder} is in the overworld inside the End or Nether band, or at or past the Far Lands. */
    public static boolean at(Entity holder) {
        if (holder == null || !holder.level().dimension().equals(Level.OVERWORLD)) return false;
        double x = holder.getX();
        return ClientVoidBand.endSkyIntensityAt(x) >= ClientVoidBand.MUSIC_CROSSOVER
                || ClientNetherBand.netherIntensityAt(x) >= ClientNetherBand.MUSIC_CROSSOVER
                || (ClientVoidBand.startsWithTrain()
                        && WorldGenCycle.fromConfig().isAtOrPastFarLands((int) Math.floor(x)));
    }
}
