package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.feature.NetherFoliageStrip;
import games.brennan.dungeontrain.worldgen.feature.StrippableFoliage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Applies the Nether-band foliage strip ({@link NetherFoliageStrip}) to each newly generated overworld chunk.
 * Runs on {@link ChunkEvent.Load} gated on {@link ChunkEvent.Load#isNewChunk()} (once at generation, after
 * all decoration, never on reload).
 *
 * <p>The scan that decides <em>what</em> to strip was precomputed on the worldgen worker at the {@code SPAWN}
 * step ({@code ChunkStatusSpawnMixin} → {@link NetherFoliageStrip#CACHE}); here on the main thread only the
 * (few) block writes remain. A cache miss — precompute disabled, plan evicted — recomputes inline, which is
 * still cheap thanks to the palette gate, and produces the identical result. The strip used to scan every
 * block of every band chunk right here, which stalled the server thread for seconds in the 0.983.0 lag
 * report.</p>
 *
 * <p><b>History:</b> the strip once covered the WHOLE band ({@code heightRampAt > 0}) to keep the old bare
 * stamped mountains clean — which also deleted the trees/flowers the new real-terrain mountains are meant to
 * have. It is scoped to the netherrack zone so mountains stay forested; see {@link NetherFoliageStrip}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NetherTransitionEvents {

    private NetherTransitionEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        ChunkAccess chunk = event.getChunk();
        long key = chunk.getPos().toLong();
        if (NetherBand.startX(level) == NetherBand.OFF) {
            NetherFoliageStrip.CACHE.remove(key); // band switched off between SPAWN and Load: drop, don't apply
            return;
        }

        // Prefer the plan precomputed off-thread at SPAWN; fall back to the inline scan on a miss.
        NetherFoliageStrip.Plan plan = NetherFoliageStrip.CACHE.remove(key);
        if (plan == null) plan = NetherFoliageStrip.compute(level, chunk);
        if (plan == null) return;
        NetherFoliageStrip.apply(chunk, plan);
    }

    /** Drop pending plans when the overworld unloads — a plan must never outlive its world. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension().equals(Level.OVERWORLD)) {
            NetherFoliageStrip.CACHE.clear();
        }
    }

    /** Tags reloaded (datapack / {@code /reload}): the per-block predicate cache may be stale. */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        StrippableFoliage.reset();
    }
}
