package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client game-bus hooks for the Nether transition band's atmosphere — the counterpart
 * to {@link VoidSkyEvents} for the End band:
 * <ul>
 *   <li>{@link ViewportEvent.ComputeFogColor} — blends fog toward the Nether's colour as the nether
 *       intensity {@code n} rises. In the real-Nether core it uses the <em>actual biome's</em> own fog
 *       colour (warped teal, crimson red, soul-sand blue, basalt grey, wastes red), so the fog matches
 *       the biome the player is in; the netherrack crossfade (a highland biome) keeps the fixed
 *       nether_wastes red.</li>
 *   <li>{@link ClientPlayerNetworkEvent.LoggingOut} — clears the synced band state.</li>
 * </ul>
 * Cloud suppression piggybacks on the shared {@code LevelRendererVoidSkyMixin}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class NetherFogEvents {

    /** Target fog colour at full intensity — vanilla nether_wastes fog (0x330808). */
    private static final float NETHER_FOG_R = 0.2f;
    private static final float NETHER_FOG_G = 0.03f;
    private static final float NETHER_FOG_B = 0.03f;

    private NetherFogEvents() {}

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double n = ClientNetherBand.netherIntensityAt(event.getCamera().getPosition().x);
        if (n <= 0.0) return;
        float t = (float) Math.min(1.0, n);

        // Target colour: in the real-Nether core, the biome's own fog colour (the biome the biome-source
        // mixin forced + vanilla synced to the client); elsewhere (the crossfade's highland biome) the
        // fixed nether_wastes red. Any read error falls back to the fixed red.
        float fr = NETHER_FOG_R, fg = NETHER_FOG_G, fb = NETHER_FOG_B;
        try {
            Holder<Biome> biome = mc.level.getBiome(event.getCamera().getBlockPosition());
            if (isNetherBiome(biome)) {
                int fog = biome.value().getFogColor();
                fr = ((fog >> 16) & 0xFF) / 255.0f;
                fg = ((fog >> 8) & 0xFF) / 255.0f;
                fb = (fog & 0xFF) / 255.0f;
            }
        } catch (Throwable ignored) {
            // keep the fixed nether_wastes red
        }

        event.setRed(lerp(event.getRed(), fr, t));
        event.setGreen(lerp(event.getGreen(), fg, t));
        event.setBlue(lerp(event.getBlue(), fb, t));
    }

    /** True for the five vanilla Nether biomes (the only ones the core labels columns with). */
    private static boolean isNetherBiome(Holder<Biome> biome) {
        return biome.is(Biomes.NETHER_WASTES)
                || biome.is(Biomes.CRIMSON_FOREST)
                || biome.is(Biomes.WARPED_FOREST)
                || biome.is(Biomes.SOUL_SAND_VALLEY)
                || biome.is(Biomes.BASALT_DELTAS);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientNetherBand.reset();
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
