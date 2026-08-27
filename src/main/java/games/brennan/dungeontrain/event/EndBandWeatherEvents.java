package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Server-side weather suppression inside the disintegration <b>End band</b> — the void / floating
 * End-island stretch that still lives in the <b>overworld</b> dimension, and therefore still has
 * vanilla weather running through it. The real End has none, so a thunderstorm dropping bolts on
 * the train mid-band breaks the illusion the band's sky, lighting and music build.
 *
 * <p>Sibling of {@link NetherBandBehaviourEvents}, which does the same for the Nether core (gate on
 * a {@link ServerLevel} in the overworld + the band predicate). The client half — hiding the rain
 * sheets, splash particles and rain ambience — lives in {@code LevelRendererVoidSkyMixin}.</p>
 *
 * <p>The band's fully-eroded core columns carry real End biomes ({@code EndCoreBiomes}), whose
 * precipitation is {@code NONE}, so vanilla already skips lightning there. It is the erosion /
 * void ramp on either side — still overworld biomes, now hanging over a bottomless void — that
 * strikes today, which is why the guard covers the whole band ({@code middleRamp > 0}) rather
 * than only its core.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EndBandWeatherEvents {

    private EndBandWeatherEvents() {}

    /**
     * Cancel lightning strikes anywhere in the End band. Cancelled server-side as the bolt joins,
     * so it never reaches clients — no strike, no fire, no damage. Unlike the visual crossfades
     * (which ramp with the sky), a bolt is a discrete event with nothing to blend, so the guard
     * covers the band's full span instead of a threshold.
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof LightningBolt bolt)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (DisintegrationBand.middleRampAt(level, Mth.floor(bolt.getX())) > 0.0) {
            event.setCanceled(true);
        }
    }
}
