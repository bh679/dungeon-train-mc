package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The photo beside a template, as a texture the New screen can draw.
 *
 * <p>Read straight off disk rather than shipped through a packet: a builder world is single-player,
 * so the template directory the server writes to is the one this client can open.</p>
 *
 * <p><b>Misses are cached as hard as hits.</b> The screen asks for the selected template's photo
 * every frame, and most templates don't have one — an uncached miss would be a failed file open at
 * 60fps for as long as the menu is open.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderPhotoTextures {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A loaded photo and its real pixel size — the size is what lets the screen crop it to the slot
     * instead of squashing it, since a {@code ResourceLocation} carries no dimensions.
     */
    public record Photo(ResourceLocation texture, int width, int height) {}

    /** Absent value = looked and found nothing. Absent key = not looked yet. */
    private static final Map<String, Optional<Photo>> CACHE = new HashMap<>();

    private BuilderPhotoTextures() {}

    /** As {@link #textureFor(BuilderPhotoPaths.Kind, String, CarriagePartKind, TrackKind)}, carriage-side. */
    public static Photo textureFor(BuilderPhotoPaths.Kind kind, String id,
                                   CarriagePartKind partKind) {
        return textureFor(kind, id, partKind, null);
    }

    /** The template's photo, or null when it hasn't got one. */
    public static Photo textureFor(BuilderPhotoPaths.Kind kind, String id,
                                   CarriagePartKind partKind, TrackKind trackKind) {
        if (kind == null || id == null || id.isEmpty()) {
            return null;
        }
        String key = cacheKey(kind, id, partKind, trackKind);
        Optional<Photo> cached = CACHE.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        Optional<Photo> loaded = load(kind, id, partKind, trackKind);
        CACHE.put(key, loaded);
        return loaded.orElse(null);
    }

    /** Forget one entry, so a photo written just now is picked up instead of a cached miss. */
    public static void invalidate(BuilderPhotoPaths.Kind kind, String id, CarriagePartKind partKind) {
        invalidate(kind, id, partKind, null);
    }

    public static void invalidate(BuilderPhotoPaths.Kind kind, String id, CarriagePartKind partKind,
                                  TrackKind trackKind) {
        if (kind == null || id == null) {
            return;
        }
        CACHE.remove(cacheKey(kind, id, partKind, trackKind));
    }

    /**
     * Drop every texture. Called on logout: the next world may be a different install directory, and
     * a stale entry would show one template's photo beside another's name.
     */
    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Optional<Photo> entry : CACHE.values()) {
            entry.ifPresent(photo -> mc.getTextureManager().release(photo.texture()));
        }
        CACHE.clear();
    }

    private static Optional<Photo> load(BuilderPhotoPaths.Kind kind, String id,
                                        CarriagePartKind partKind, TrackKind trackKind) {
        Optional<Path> path = BuilderPhotoPaths.photoFor(kind, id, partKind, trackKind);
        if (path.isEmpty() || !Files.isRegularFile(path.get())) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(path.get())) {
            NativeImage image = NativeImage.read(in);
            int width = image.getWidth();
            int height = image.getHeight();
            // DynamicTexture takes ownership of the image; reading the size first because the
            // texture doesn't expose it afterwards.
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation location = Minecraft.getInstance().getTextureManager()
                    .register("dungeontrain_template_photo", texture);
            return Optional.of(new Photo(location, width, height));
        } catch (Exception e) {
            // A half-written or hand-edited PNG shouldn't take the menu down; it just has no picture.
            LOGGER.warn("[DungeonTrain] Template photo unreadable at {}: {}", path.get(), e.toString());
            return Optional.empty();
        }
    }

    private static String cacheKey(BuilderPhotoPaths.Kind kind, String id, CarriagePartKind partKind,
                                   TrackKind trackKind) {
        // Part names are only unique within their kind — `standard` exists as a floor and as a door.
        // Track names are the same story, and worse: `default` exists under all eight track kinds.
        return kind.id() + ":" + (partKind == null ? "" : partKind.id())
                + ":" + (trackKind == null ? "" : trackKind.id()) + ":" + id;
    }
}
