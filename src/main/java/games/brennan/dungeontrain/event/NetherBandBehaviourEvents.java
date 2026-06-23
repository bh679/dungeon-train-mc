package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.NetherBand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Server-side Nether-dimension behaviours adopted inside the Nether transition band's core
 * ({@link NetherBand#isInNetherBiome}) — the netherrack/real-Nether stretch that still lives in
 * the <b>overworld</b> dimension. Mirrors the {@link MobDifficultyEvents} /
 * {@link NetherBandZombificationGuard} pattern (gate on a ServerLevel + the band predicate).
 *
 * <ul>
 *   <li><b>No lightning</b> — a {@link LightningBolt} that would spawn inside the core is
 *       cancelled at {@link EntityJoinLevelEvent}, so no strike, fire or damage (the Nether has
 *       no weather). Cancelled server-side only, so it never reaches clients.</li>
 *   <li><b>Wet sponge dries</b> — placing a wet sponge in the core instantly converts it to a dry
 *       sponge with smoke + the drying sound, reproducing vanilla's {@code ultraWarm} behaviour
 *       (see {@code WetSpongeBlock#onPlace}).</li>
 *   <li><b>No falling blocks on the rails</b> — a {@link FallingBlockEntity} (gravel from the
 *       Nether's {@code ore_gravel} deltas, sand, etc.) that begins to fall inside the train
 *       corridor's airspace is cancelled at {@link EntityJoinLevelEvent}, so it never lands on the
 *       track. The block has already been replaced with air where it stood, so it simply
 *       disappears — the same path the worldgen clearance / corridor sweep can't catch because the
 *       fall happens after the chunk is generated and swept. Scoped to the tunnel Z-span, so
 *       gravel falling elsewhere in the core is untouched.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NetherBandBehaviourEvents {

    private NetherBandBehaviourEvents() {}

    /** Cancel lightning strikes inside the Nether core — no weather in the Nether. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof LightningBolt bolt)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (NetherBand.isInNetherBiome(level, (int) Math.floor(bolt.getX()))) {
            event.setCanceled(true);
        }
    }

    /**
     * Stop Nether gravel/sand from burying the rails. The core's {@code ore_gravel} deltas leave
     * gravel that loses its support once {@code track_bed} carves the tunnel (especially the open
     * lava-viaduct sections with no ceiling); it then falls as a {@link FallingBlockEntity} onto the
     * track <em>after</em> worldgen, which neither the worldgen clearance nor the deferred corridor
     * sweep can catch. Cancelling the entity as it joins discards the block (its origin is already
     * air), so it never reaches the rails. Confined to the train corridor's Z-span, so gravel
     * falling elsewhere in the Nether core behaves normally.
     */
    @SubscribeEvent
    public static void onFallingBlock(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof FallingBlockEntity falling)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (!NetherBand.isInNetherBiome(level, Mth.floor(falling.getX()))) return;

        // Only inside the train corridor's airspace — a falling block in this Z-span drops onto the
        // track (or past it into lava); elsewhere in the core gravel is harmless and left alone.
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        TrackGeometry g = TrackGeometry.from(data.dims(), data.getTrainY());
        TunnelGeometry tg = TunnelGeometry.from(g);
        int z = Mth.floor(falling.getZ());
        if (z < tg.airMinZ() || z > tg.airMaxZ()) return;
        if (Mth.floor(falling.getY()) < g.bedY()) return;   // below the track — not a rail threat

        event.setCanceled(true); // gravel never lands on the rails in the Nether core
    }

    /** Dry a wet sponge placed in the Nether core, exactly as ultraWarm dimensions do. */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!event.getPlacedBlock().is(Blocks.WET_SPONGE)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        BlockPos pos = event.getPos();
        if (!NetherBand.isInNetherBiome(level, pos.getX())) return;

        level.setBlock(pos, Blocks.SPONGE.defaultBlockState(), 3);
        level.levelEvent(2009, pos, 0); // drying smoke puff
        level.playSound(null, pos, SoundEvents.WET_SPONGE_DRIES, SoundSource.BLOCKS, 1.0F,
                (1.0F + level.getRandom().nextFloat() * 0.2F) * 0.7F);
    }
}
