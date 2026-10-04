package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import games.brennan.dungeontrain.compat.photo.PhotoPngCodec;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import io.github.mortuusars.exposure.ExposureClient;
import io.github.mortuusars.exposure.client.image.renderable.RenderableImage;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.level.storage.ExposureIdentifier;
import io.github.mortuusars.exposure.world.level.storage.RequestedPalettedExposure;
import io.github.mortuusars.exposure.client.render.photograph.PhotographStyle;
import io.github.mortuusars.exposure.client.render.photograph.PhotographStyles;
import io.github.mortuusars.exposure.world.photograph.PhotographType;
import com.mojang.blaze3d.platform.NativeImage;
import games.brennan.dungeontrain.discord.PhotoPaperComposite;
import java.io.InputStream;
import java.util.Optional;
import net.minecraft.advancements.AdvancementProgress;
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
 * {@link EarnedPhotoScreen}; its tooltip shows a small print ({@link EarnedPhotoThumbnails}). All access is on the client thread.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class EarnedPhotos {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String DIR = "advancement-photos";
    /** How long to keep asking Exposure for an image that is still on its way (it uploads after the shot). */
    private static final int FETCH_TIMEOUT_TICKS = 20 * 30;

    /** How often to ask again while the image is on its way, and how long before the first ask. */
    private static final int RETRY_TICKS = 20;

    /** Where a photo goes: an advancement's own photo ({@code entry} empty), or one entry of its album. */
    private record Slot(ResourceLocation advancement, String entry) {}

    private record Pending(String exposureId, ResourceLocation type, int ticksLeft, int nextTry) {}

    private static final Map<Slot, Pending> PENDING = new LinkedHashMap<>();

    /** One photo of an album: the entity or biome id it was logged for, and its file. */
    public record Entry(ResourceLocation id, Path file) {}

    private EarnedPhotos() {}

    /**
     * The server says {@code advancement} was earned with this photo, printed on {@code type}'s paper:
     * fetch it and keep a copy, laid on that paper the way the game draws the print.
     */
    public static void capture(ResourceLocation advancement, String exposureId, ResourceLocation type, String entry) {
        if (advancement == null || exposureId == null || exposureId.isBlank()) return;
        ResourceLocation album = entry == null || entry.isEmpty() ? null : albumOf(advancement);
        if (album != null && EnchiridionAdvancements.isBiomeAlbum(album.getPath())) PHOTOGRAPHED_BIOMES.add(entry);
        PENDING.put(new Slot(advancement, entry == null ? "" : entry),
                new Pending(exposureId, type, FETCH_TIMEOUT_TICKS, RETRY_TICKS));
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

    /** The album {@code advancement} shows, or {@code null} for an advancement with one photo. */
    static ResourceLocation albumOf(ResourceLocation advancement) {
        if (advancement == null || !DungeonTrain.MOD_ID.equals(advancement.getNamespace())) return null;
        String album = EnchiridionAdvancements.albumOf(advancement.getPath());
        return album == null ? null : ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, album);
    }

    /** {@code advancement}'s album entries, oldest first; empty when it has no album or nothing logged yet. */
    public static List<Entry> entries(ResourceLocation advancement) {
        ResourceLocation album = albumOf(advancement);
        if (album == null) return List.of();
        return ALBUMS.computeIfAbsent(album, EarnedPhotos::listAlbum);
    }

    /** Album listings, read once each — the tooltip asks every frame it is hovered. */
    private static final Map<ResourceLocation, List<Entry>> ALBUMS = new java.util.HashMap<>();

    private static List<Entry> listAlbum(ResourceLocation album) {
        Path dir = albumDir(album);
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> files = new ArrayList<>();
        try (var listing = Files.list(dir)) {
            listing.filter(f -> f.getFileName().toString().endsWith(".png")).forEach(files::add);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Couldn't list the album {}: {}", album, e.toString());
        }
        files.sort(java.util.Comparator.comparingLong(EarnedPhotos::modified));
        List<Entry> out = new ArrayList<>(files.size());
        for (Path f : files) {
            ResourceLocation id = entryId(f.getFileName().toString());
            if (id != null) out.add(new Entry(id, f));
        }
        return List.copyOf(out);
    }

    /**
     * The album entries this run's progress backs: an animal or mob counts once its criterion is ticked,
     * a biome once this world's photographed set has it. The album folder outlives worlds (and Free Play
     * runs, which never carry progress over), so a photo from a run that no longer counts stays hidden
     * until the subject is photographed again here.
     */
    public static List<Entry> visibleEntries(ResourceLocation advancement, AdvancementProgress progress) {
        List<Entry> all = entries(advancement);
        if (all.isEmpty()) return all;
        boolean biomes = EnchiridionAdvancements.isBiomeAlbum(albumOf(advancement).getPath());
        List<Entry> out = new ArrayList<>(all.size());
        for (Entry e : all) {
            boolean counts = biomes
                    ? PHOTOGRAPHED_BIOMES.contains(e.id().toString())
                    : progress != null && progress.getCriterion(e.id().toString()) != null
                            && progress.getCriterion(e.id().toString()).isDone();
            if (counts) out.add(e);
        }
        return out;
    }

    /** Biomes photographed in this world, from the server ({@code PhotoBiomesPacket}) plus each new one logged. */
    private static final java.util.Set<String> PHOTOGRAPHED_BIOMES = new java.util.HashSet<>();

    /** The server's photographed-biome set for this world — sent on join and respawn. */
    public static void setPhotographedBiomes(List<String> biomes) {
        PHOTOGRAPHED_BIOMES.clear();
        PHOTOGRAPHED_BIOMES.addAll(biomes);
    }

    /** The picture for {@code advancement}'s tooltip: its own photo once earned, else the album's latest that counts. */
    public static Path thumbnail(ResourceLocation advancement, AdvancementProgress progress) {
        boolean earned = progress != null && progress.isDone();
        if (earned && has(advancement)) return file(advancement);
        List<Entry> album = visibleEntries(advancement, progress);
        return album.isEmpty() ? null : album.get(album.size() - 1).file();
    }

    /**
     * Open what {@code advancement} has kept: its album, once anything this run counts (earned or not — a
     * collection shows as it fills), else its own photo once earned. Returns whether anything opened.
     */
    public static boolean tryOpen(ResourceLocation advancement, net.minecraft.network.chat.Component title,
                                  AdvancementProgress progress) {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = mc.screen;
        List<Entry> album = visibleEntries(advancement, progress);
        if (!album.isEmpty()) {
            boolean biomes = EnchiridionAdvancements.isBiomeAlbum(albumOf(advancement).getPath());
            mc.execute(() -> mc.setScreen(new EarnedPhotoAlbumScreen(parent, title, album, biomes)));
            return true;
        }
        if (progress == null || !progress.isDone() || !has(advancement)) return false;
        mc.execute(() -> mc.setScreen(new EarnedPhotoScreen(parent, title, file(advancement))));
        return true;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        List<Slot> done = new ArrayList<>();
        for (Map.Entry<Slot, Pending> e : new ArrayList<>(PENDING.entrySet())) {
            Pending p = e.getValue();
            if (p.ticksLeft() <= 1) {
                LOGGER.warn("[DungeonTrain] Photo {} for {} never arrived; none kept.", p.exposureId(), e.getKey());
                done.add(e.getKey());
                continue;
            }
            if (p.nextTry() > 0) {
                PENDING.put(e.getKey(), new Pending(p.exposureId(), p.type(), p.ticksLeft() - 1, p.nextTry() - 1));
                continue;
            }
            if (trySave(e.getKey(), p.exposureId(), p.type())) {
                done.add(e.getKey());
            } else {
                PENDING.put(e.getKey(), new Pending(p.exposureId(), p.type(), p.ticksLeft() - 1, RETRY_TICKS));
            }
        }
        done.forEach(PENDING::remove);
    }

    /**
     * Try to fetch and keep the image; false while it isn't there yet. The shot reaches the server a
     * moment after the advancement is earned, so an early ask comes back "not found" — Exposure caches
     * that answer, so it is cleared before the next ask.
     */
    private static boolean trySave(Slot slot, String exposureId, ResourceLocation type) {
        ResourceLocation advancement = slot.advancement();
        try {
            RequestedPalettedExposure requested = ExposureClient.exposureStore().getOrRequest(exposureId);
            if (requested.isError()) {
                ExposureClient.exposureStore().refresh(exposureId);
                ExposureClient.renderedExposures().clearCacheOf(exposureId);
                return false;
            }
            if (requested.getData().isEmpty()) return false;
            Frame frame = Frame.create().setIdentifier(ExposureIdentifier.id(exposureId)).toImmutable();
            RenderableImage image = ExposureClient.renderedExposures().getOrCreate(frame);
            if (image == RenderableImage.MISSING) {
                ExposureClient.renderedExposures().clearCacheOf(exposureId);
                return false;
            }
            if (image.isEmpty() || image.width() <= 0 || image.height() <= 0) return false;
            PhotographStyle style = PhotographStyles.get(new PhotographType(type == null ? PhotographType.REGULAR.id() : type));
            RenderableImage styled = style.process(image);
            int w = styled.width(), h = styled.height();
            int[] argb = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) argb[y * w + x] = styled.getPixelARGB(x, y) | 0xFF000000;
            }
            Optional<int[]> paper = paper(style.paperTexture());
            if (paper.isPresent()) {
                PhotoPaperComposite.Composite print = PhotoPaperComposite.compose(paper.get(), argb, w, h);
                w = print.width();
                h = print.height();
                argb = print.argb();
            }
            Path target = slot.entry().isEmpty() ? file(advancement) : entryFile(advancement, slot.entry());
            if (target == null) return true;
            Files.createDirectories(target.getParent());
            Files.write(target, PhotoPngCodec.encodeArgb(w, h, argb));
            if (slot.entry().isEmpty()) kept().add(fileName(advancement));
            else ALBUMS.remove(albumOf(advancement));
            EarnedPhotoThumbnails.forget(target);
            LOGGER.info("[DungeonTrain] Kept photo {} ({}x{}) for {}{}", exposureId, w, h, advancement,
                    slot.entry().isEmpty() ? "" : " [" + slot.entry() + "]");
            return true;
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("[DungeonTrain] Couldn't keep the photo for {} yet: {}", advancement, ex.toString());
            return false;
        }
    }

    /** The paper texture, {@code PAPER_SIZE²} ARGB, from the resource packs; empty when it can't be read. */
    private static Optional<int[]> paper(ResourceLocation texture) {
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(texture);
             NativeImage image = NativeImage.read(in)) {
            int size = PhotoPaperComposite.PAPER_SIZE;
            if (image.getWidth() != size || image.getHeight() != size) return Optional.empty();
            int[] argb = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) argb[y * size + x] = abgrToArgb(image.getPixelRGBA(x, y));
            }
            return Optional.of(argb);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't read photo paper {}: {}", texture, e.toString());
            return Optional.empty();
        }
    }

    /** {@link NativeImage} stores ABGR; the compositor works in ARGB. */
    private static int abgrToArgb(int abgr) {
        return (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
    }

    static Path file(ResourceLocation advancement) {
        return PlayerDataPaths.dir(PlayerDataPaths.USER).resolve(DIR).resolve(fileName(advancement));
    }

    /** {@code dungeontrain:enchiridion/say_cheese} → {@code dungeontrain__enchiridion.say_cheese.png}. */
    static String fileName(ResourceLocation advancement) {
        return advancement.getNamespace() + "__" + advancement.getPath().replace('/', '.') + ".png";
    }

    /** The folder an album's entries live in, beside the advancement photos. */
    static Path albumDir(ResourceLocation album) {
        String name = fileName(album);
        return PlayerDataPaths.dir(PlayerDataPaths.USER).resolve(DIR).resolve(name.substring(0, name.length() - 4));
    }

    /** Where {@code entry} of {@code advancement}'s album is kept, or {@code null} for no album / a bad id. */
    static Path entryFile(ResourceLocation advancement, String entry) {
        ResourceLocation album = albumOf(advancement);
        ResourceLocation id = ResourceLocation.tryParse(entry);
        if (album == null || id == null) return null;
        return albumDir(album).resolve(fileName(id));
    }

    /** The entity or biome id an album file was kept for: the reverse of {@link #fileName}. */
    static ResourceLocation entryId(String fileName) {
        if (!fileName.endsWith(".png")) return null;
        String stem = fileName.substring(0, fileName.length() - 4);
        int split = stem.indexOf("__");
        if (split <= 0) return null;
        return ResourceLocation.tryBuild(stem.substring(0, split), stem.substring(split + 2).replace('.', '/'));
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
