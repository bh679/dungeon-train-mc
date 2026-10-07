package games.brennan.dungeontrain.compat.photo.album;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.photo.PhotoPngCodec;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.ExposureServer;
import io.github.mortuusars.exposure.data.ColorPalettes;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.item.component.album.AlbumContent;
import io.github.mortuusars.exposure.world.item.component.album.AlbumPage;
import io.github.mortuusars.exposure.world.level.storage.ExposureData;
import io.github.mortuusars.exposure.world.level.storage.ExposureIdentifier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Between a stored album ({@link AlbumStore}, outside any world) and the album a world shows
 * (Exposure's {@link AlbumContent}, whose photographs point into that world's Exposure store).
 *
 * <p>Opening lends the pictures to the world under {@link AlbumImageHash#exposureId}; saving reads
 * them back out and names each by its hash. Both run on the server thread.</p>
 */
final class AlbumWorldBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One page read out of the world: what the store keeps, plus whether others may see the picture. */
    record SavedPage(AlbumStore.Page page, boolean shareable) {}

    /** A whole album read out of the world, and the pictures the store does not hold yet. */
    record Snapshot(List<SavedPage> pages, Map<String, AlbumPageImages.Picture> newPictures) {}

    private AlbumWorldBridge() {}

    // ---- store → world ---------------------------------------------------------------

    /** The album as this world shows it: every picture lent to the world's Exposure store first. */
    static AlbumContent toContent(MinecraftServer server, AlbumStore store, AlbumStore.Entry entry) {
        List<AlbumPage> pages = new ArrayList<>();
        for (AlbumStore.Page page : entry.pages()) {
            pages.add(new AlbumPage(photograph(server, store, page), page.note()));
        }
        return new AlbumContent(pages);
    }

    private static ItemStack photograph(MinecraftServer server, AlbumStore store, AlbumStore.Page page) {
        if (page.hash() == null || !lend(server, store, page.hash())) return ItemStack.EMPTY;
        String id = AlbumImageHash.exposureId(page.hash());
        ItemStack stack = parse(server.registryAccess(), page.photo())
                .orElseGet(() -> new ItemStack(Exposure.Items.PHOTOGRAPH.get()));
        Frame frame = stack.getOrDefault(Exposure.DataComponents.PHOTOGRAPH_FRAME, Frame.EMPTY);
        Frame pointed = frame.toMutable().setIdentifier(ExposureIdentifier.id(id)).toImmutable();
        stack.set(Exposure.DataComponents.PHOTOGRAPH_FRAME, pointed);
        stack.set(Exposure.DataComponents.PHOTOGRAPH_TYPE, pointed.type());
        return stack;
    }

    /** Album pictures known to be in this world's Exposure store, by Exposure id. Cleared when the world stops. */
    private static final Set<String> lent = ConcurrentHashMap.newKeySet();

    static void forgetLent() {
        lent.clear();
    }

    /** From the store: the decoded picture, decoding it here only when it was not prewarmed. */
    private static boolean lend(MinecraftServer server, AlbumStore store, String hash) {
        return lend(server, hash, () -> AlbumPictureCache.get().picture(hash).or(() -> store.image(hash).flatMap(png -> {
            try {
                PhotoPngCodec.Decoded decoded = PhotoPngCodec.decode(png);
                AlbumPictureCache.get().put(hash, decoded);
                return Optional.of(decoded);
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] Album picture {} unreadable: {}", hash, e.toString());
                return Optional.empty();
            }
        })));
    }

    /**
     * Put the picture in this world's Exposure store if it is not there yet — once per world: after
     * that it is only a set lookup, never a read of Exposure's store. False when the picture is not to be had.
     */
    static boolean lend(MinecraftServer server, String hash, Supplier<Optional<PhotoPngCodec.Decoded>> picture) {
        String id = AlbumImageHash.exposureId(hash);
        if (lent.contains(id)) return true;
        if (Files.exists(exposureFile(server, id))) {
            lent.add(id);
            return true;
        }
        Optional<PhotoPngCodec.Decoded> image = picture.get();
        if (image.isEmpty()) return false;
        try {
            ExposureServer.exposureRepository().save(id, new ExposureData(image.get().width(), image.get().height(),
                    image.get().pixels(), ColorPalettes.DEFAULT.location(), ExposureData.Tag.EMPTY));
            lent.add(id);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Album picture {} could not be lent to this world: {}", hash, e.toString());
            return false;
        }
    }

    /** Where Exposure keeps a photo of this world on disk ({@code ExposureRepository}: {@code data/exposures/<id>.dat}). */
    private static Path exposureFile(MinecraftServer server, String id) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("exposures").resolve(id + ".dat");
    }

    /** A photograph showing an album picture, lent to the world first. Empty when the picture is not to be had. */
    static ItemStack photograph(MinecraftServer server, String hash, Supplier<Optional<PhotoPngCodec.Decoded>> picture) {
        if (!lend(server, hash, picture)) return ItemStack.EMPTY;
        Frame frame = Frame.create().setIdentifier(ExposureIdentifier.id(AlbumImageHash.exposureId(hash))).toImmutable();
        ItemStack stack = new ItemStack(Exposure.Items.PHOTOGRAPH.get());
        stack.set(Exposure.DataComponents.PHOTOGRAPH_FRAME, frame);
        stack.set(Exposure.DataComponents.PHOTOGRAPH_TYPE, frame.type());
        return stack;
    }

    // ---- world → store ---------------------------------------------------------------

    /**
     * Read an album out of the world. Each picture is named by its hash (computed here, so the next
     * open in this world finds it straight away) and lent back under that name; pictures the store
     * lacks are returned to be encoded and kept off the server thread.
     */
    static Snapshot fromContent(MinecraftServer server, AlbumStore store, AlbumContent content, String ownerName) {
        List<SavedPage> pages = new ArrayList<>();
        Map<String, AlbumPageImages.Picture> newPictures = new LinkedHashMap<>();
        for (AlbumPage page : content.removeTrailingPages().pages()) {
            ItemStack photo = page.photograph();
            boolean shareable = PlayerAlbums.mayShare(photo, ownerName);
            String hash = AlbumPageImages.knownHash(photo).orElse(null);
            if (hash == null) {
                Optional<AlbumPageImages.Picture> picture = AlbumPageImages.read(server, photo);
                if (picture.isPresent()) {
                    AlbumPageImages.Picture read = picture.get();
                    hash = read.hash();
                    PhotoPngCodec.Decoded decoded = new PhotoPngCodec.Decoded(read.width(), read.height(), read.pixels());
                    AlbumPictureCache.get().put(hash, decoded);
                    lend(server, hash, () -> Optional.of(decoded));
                    if (!store.hasImage(hash)) newPictures.put(hash, read);
                }
            }
            String snbt = photo.isEmpty() ? null : serialize(server.registryAccess(), photo);
            pages.add(new SavedPage(new AlbumStore.Page(hash, page.note(), hash == null ? null : snbt), shareable));
        }
        return new Snapshot(pages, newPictures);
    }

    // ---- photograph stacks ------------------------------------------------------------

    private static String serialize(HolderLookup.Provider registries, ItemStack stack) {
        try {
            return stack.save(registries).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Optional<ItemStack> parse(HolderLookup.Provider registries, String snbt) {
        if (snbt == null || snbt.isBlank()) return Optional.empty();
        try {
            return ItemStack.parse(registries, TagParser.parseTag(snbt)).filter(s -> !s.isEmpty());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
