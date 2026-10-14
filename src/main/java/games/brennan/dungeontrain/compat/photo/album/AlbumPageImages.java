package games.brennan.dungeontrain.compat.photo.album;

import games.brennan.dungeontrain.compat.photo.PhotoPngCodec;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.ExposureServer;
import io.github.mortuusars.exposure.data.ColorPalettes;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.level.storage.ExposureData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Album pictures and this world's Exposure store. A picture travels between worlds as a palette PNG
 * named by {@link AlbumImageHash}; inside a world it is an Exposure photo under
 * {@link AlbumImageHash#exposureId} ({@link AlbumWorldBridge} lends it there).
 */
final class AlbumPageImages {

    /** A page picture read out of the store: the pixels to hash and encode, off the server thread. */
    record Picture(int width, int height, byte[] pixels, int[] palette) {
        String hash() { return AlbumImageHash.of(width, height, pixels); }
        byte[] png() throws java.io.IOException { return PhotoPngCodec.encode(width, height, pixels, palette); }
    }

    private AlbumPageImages() {}

    /** The Exposure id a photograph stack shows, or blank for an empty page / a projected image. */
    static String exposureId(ItemStack photograph) {
        Frame frame = photograph.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        if (frame == null || frame.isProjected() || !frame.identifier().isId()) return "";
        return frame.identifier().id();
    }

    /** Server thread: copy a page's picture out of the store, if this world holds it. */
    static Optional<Picture> read(MinecraftServer server, ItemStack photograph) {
        String id = exposureId(photograph);
        if (id.isEmpty()) return Optional.empty();
        Optional<ExposureData> data = ExposureServer.exposureRepository().load(id).getData();
        return data.map(d -> new Picture(d.getWidth(), d.getHeight(), d.getPixels().clone(),
                ColorPalettes.get(server.registryAccess(), d.getPaletteId()).value().colors()));
    }

    /** The hash an album picture already goes by, when it came from an album (no need to re-hash it). */
    static Optional<String> knownHash(ItemStack photograph) {
        String id = exposureId(photograph);
        if (!id.startsWith(AlbumImageHash.EXPOSURE_ID_PREFIX)) return Optional.empty();
        String hash = id.substring(AlbumImageHash.EXPOSURE_ID_PREFIX.length());
        return AlbumImageHash.isHash(hash) ? Optional.of(hash) : Optional.empty();
    }
}
