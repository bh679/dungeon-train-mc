package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.AmbientMoodSettings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Plays cave noises in a dimensional carriage — the client side of {@link ClientPortalCaveAmbience}, and the
 * counterpart to {@link PortalMusicEvents}, which is what makes the room quiet enough to want them.
 *
 * <p><b>The room, not the corridors.</b> The twin corridors are copies of the carriage the player
 * walked out of, and still carry the engine and the last of the fading soundtrack; the room and
 * every tiled copy of it carry neither. {@link ClientPortalTrainAudio#inRoom} draws that line, off
 * the same cached region both other rules read.</p>
 *
 * <p><b>Vanilla's placement, our timing.</b> Only the decision to speak is ours — see
 * {@link ClientPortalCaveAmbience} for why vanilla's own moodiness never reaches it down here.
 * Everything about how the noise sits in the world is taken from
 * {@code BiomeAmbientSoundsHandler}, down to reading the figures off
 * {@link AmbientMoodSettings#LEGACY_CAVE_SETTINGS} rather than restating them: a block picked at
 * random within {@code blockSearchExtent} of the player, the sound pushed a further
 * {@code soundPositionOffset} out along that direction so it lands beyond the wall rather than in
 * the room, and {@link SimpleSoundInstance#forAmbientMood} to build it. That instance is
 * {@code SoundSource.AMBIENT}, so the player's Ambient slider applies without us asking.</p>
 *
 * <p>Unlike vanilla we do not check the light at the block we picked — it is a direction, not a
 * candidate. A dimensional carriage is lit on purpose, and gating on darkness is precisely what leaves it
 * silent.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PortalCaveAmbienceEvents {

    /** Cave mood settings, borrowed for the sound event and its two placement figures. */
    private static final AmbientMoodSettings CAVE = AmbientMoodSettings.LEGACY_CAVE_SETTINGS;

    /** Below this the player is on top of the block picked, and the direction is meaningless. */
    private static final double MIN_DISTANCE = 1.0e-4;

    private static final RandomSource RANDOM = RandomSource.create();

    private PortalCaveAmbienceEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null || mc.isPaused()) return;

        boolean inRoom = ClientPortalTrainAudio.inRoom(player.getX(), player.getY(), player.getZ());
        if (ClientPortalCaveAmbience.tick(inRoom, RANDOM.nextDouble())) {
            play(mc, player);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientPortalCaveAmbience.reset();
    }

    /** One cave noise, placed the way vanilla places a mood sound. */
    private static void play(Minecraft mc, Player player) {
        int extent = CAVE.getBlockSearchExtent();
        int span = extent * 2 + 1;
        BlockPos pos = BlockPos.containing(
            player.getX() + RANDOM.nextInt(span) - extent,
            player.getEyeY() + RANDOM.nextInt(span) - extent,
            player.getZ() + RANDOM.nextInt(span) - extent);

        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Vanilla divides by this unguarded; the roll can land on the player's own eye block.
        if (distance < MIN_DISTANCE) return;

        double reach = distance + CAVE.getSoundPositionOffset();
        mc.getSoundManager().play(SimpleSoundInstance.forAmbientMood(
            CAVE.getSoundEvent().value(), RANDOM,
            player.getX() + dx / distance * reach,
            player.getEyeY() + dy / distance * reach,
            player.getZ() + dz / distance * reach));
    }
}
