package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.PhotoPngCodec;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import io.github.mortuusars.exposure.ExposureClient;
import io.github.mortuusars.exposure.client.image.renderable.RenderableImage;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.level.storage.ExposureIdentifier;
import io.github.mortuusars.exposure.world.level.storage.RequestedPalettedExposure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The photo behind each Enchiridion camera advancement, kept on this computer.
 *
 * <p>When the server reports an earn ({@code EarnedPhotoPacket}), the image is fetched from Exposure by
 * id and written as a PNG to {@code <gameDir>/dungeontrain/user/advancement-photos/}. Exposure keeps its
 * images per world, while DT carries earned advancements across worlds, so the copy here is what lets
 * the photo follow the advancement. Clicking an earned advancement with a photo opens
 * {@link EarnedPhotoScreen}. All access is on the client thread.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class EarnedPhotos {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String DIR = "advancement-photos";
    /** How long to keep asking Exposure for an image that is still on its way (it uploads after the shot). */
    private static final int FETCH_TIMEOUT_TICKS = 20 * 30;

    private record Pending(String exposureId, int ticksLeft) {}

    private static final Map<ResourceLocation, Pending> PENDING = new LinkedHashMap<>();

    private EarnedPhotos() {}

    /** The server says {@code advancement} was earned with this photo: fetch it and keep a copy. */
    public static void capture(ResourceLocation advancement, String exposureId) {
        if (advancement == null || exposureId == null || exposureId.isBlank()) return;
        PENDING.put(advancement, new Pending(exposureId, FETCH_TIMEOUT_TICKS));
    }

    /** File names kept on disk, read once — {@link #has} runs every frame a tooltip is hovered. */
    private static java.util.Set<String> kept;

    /** Whether a photo is kept for {@code advancement}. */
    public static boolean has(ResourceLocation advancement) {
        return advancement != null && kept().contains(fileName(advancement));
    }

    private static java.util.Set<String> kept() {
        if (kept == null) {
            java.util.Set<String> names = new java.util.HashSet<>();
            Path dir = PlayerDataPaths.dir(PlayerDataPaths.USER).resolve(DIR);
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    files.forEach(f -> names.add(f.getFileName().toString()));
                } catch (IOException e) {
                    LOGGER.warn("[DungeonTrain] Couldn't list advancement photos: {}", e.toString());
                }
            }
            kept = names;
        }
        return kept;
    }

    /** Open the photo kept for {@code advancement}, if any. Returns whether one opened. */
    public static boolean tryOpen(ResourceLocation advancement, net.minecraft.network.chat.Component title) {
        if (!has(advancement)) return false;
        Minecraft mc = Minecraft.getInstance();
        Screen parent = mc.screen;
        mc.execute(() -> mc.setScreen(new EarnedPhotoScreen(parent, title, file(advancement))));
        return true;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        List<ResourceLocation> done = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Pending> e : new ArrayList<>(PENDING.entrySet())) {
            Pending p = e.getValue();
            Boolean saved = trySave(e.getKey(), p.exposureId());
            if (saved != null || p.ticksLeft() <= 1) {
                if (saved == null) LOGGER.info("[DungeonTrain] Photo for {} never arrived; none kept.", e.getKey());
                done.add(e.getKey());
            } else {
                PENDING.put(e.getKey(), new Pending(p.exposureId(), p.ticksLeft() - 1));
            }
        }
        done.forEach(PENDING::remove);
    }

    /** True when saved, false when it can never be fetched, null while it is still developing. */
    private static Boolean trySave(ResourceLocation advancement, String exposureId) {
        try {
            RequestedPalettedExposure requested = ExposureClient.exposureStore().getOrRequest(exposureId);
            if (requested.isError()) return false;
            if (requested.getData().isEmpty()) return null;
            Frame frame = Frame.create().setIdentifier(ExposureIdentifier.id(exposureId)).toImmutable();
            RenderableImage image = ExposureClient.renderedExposures().getOrCreate(frame);
            if (image == RenderableImage.MISSING) return false;
            if (image.isEmpty() || image.width() <= 0 || image.height() <= 0) return null;
            int w = image.width(), h = image.height();
            int[] argb = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) argb[y * w + x] = image.getPixelARGB(x, y) | 0xFF000000;
            }
            Path target = file(advancement);
            Files.createDirectories(target.getParent());
            Files.write(target, PhotoPngCodec.encodeArgb(w, h, argb));
            kept().add(fileName(advancement));
            return true;
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("[DungeonTrain] Couldn't keep the photo for {}: {}", advancement, ex.toString());
            return false;
        }
    }

    private static Path file(ResourceLocation advancement) {
        return PlayerDataPaths.dir(PlayerDataPaths.USER).resolve(DIR).resolve(fileName(advancement));
    }

    /** {@code dungeontrain:enchiridion/say_cheese} → {@code dungeontrain__enchiridion.say_cheese.png}. */
    static String fileName(ResourceLocation advancement) {
        return advancement.getNamespace() + "__" + advancement.getPath().replace('/', '.') + ".png";
    }
}
