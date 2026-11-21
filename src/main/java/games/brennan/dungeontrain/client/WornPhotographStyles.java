package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.WornPhotographs;
import io.github.mortuusars.exposure.ExposureClient;
import io.github.mortuusars.exposure.client.render.photograph.PhotographStyle;
import io.github.mortuusars.exposure.client.render.photograph.PhotographStyles;
import io.github.mortuusars.exposure.world.photograph.PhotographType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Tells Exposure which paper texture each of DT's worn photograph types is drawn on.
 *
 * <p>Registers once per JVM. When another mod fails during the first resource load, vanilla rolls
 * back the resource packs and reloads, and NeoForge runs client setup a second time. Exposure's
 * style map survives that and throws on a repeat, which would make DT the mod the crash report
 * blames and hide the real error. The work is enqueued because that map is a plain HashMap and
 * Exposure writes it on the main thread.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class WornPhotographStyles {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private WornPhotographStyles() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> registerOnce(REGISTERED, WornPhotographStyles::register));
    }

    /** DT's worn types, in registration order. */
    static List<PhotographType> types() {
        return Stream.concat(Stream.concat(Stream.of(WornPhotographs.LIGHT),
            WornPhotographs.MEDIUM.stream()), WornPhotographs.HEAVY.stream()).toList();
    }

    /** Hands every worn type to {@code registrar} the first time only; false when already done. */
    static boolean registerOnce(AtomicBoolean done, Consumer<PhotographType> registrar) {
        if (!done.compareAndSet(false, true)) {
            LOGGER.info("[DungeonTrain] Worn photograph styles already registered; client setup ran again");
            return false;
        }
        types().forEach(registrar);
        return true;
    }

    private static void register(PhotographType type) {
        // Exposure's own "no overlay" marker. It compares against this, so null here crashes the renderer.
        ResourceLocation noOverlay = ExposureClient.Textures.EMPTY;
        ResourceLocation albumPaper = ExposureClient.Textures.Photograph.REGULAR_ALBUM_PAPER;
        // Each type's paper texture carries the type's own name.
        String texture = type.id().getPath();
        ResourceLocation paper = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "textures/photograph/" + texture + ".png");
        PhotographStyles.register(type, new PhotographStyle(paper, noOverlay, albumPaper, noOverlay,
            new TornEdgeEffect("dt_torn_" + texture, paper)));
    }
}
