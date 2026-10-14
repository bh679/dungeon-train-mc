package games.brennan.dungeontrain.compat.photo;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.PhotoPaperComposite;
import io.github.mortuusars.exposure.world.photograph.PhotographType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The paper texture a photograph type is drawn on, read on the server — which has no resource
 * manager for assets — straight out of the owning mod's jar. Used to show a tributed photo on the
 * paper it wore in game ({@link PhotoPaperComposite}).
 *
 * <p>Exposure's regular paper is {@code exposure:textures/photograph/photograph.png}; DT's worn
 * papers carry their type's own name ({@code client.WornPhotographStyles}).</p>
 */
public final class PhotoPaperTextures {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EXPOSURE_MOD_ID = "exposure";
    private static final String REGULAR_PAPER = "photograph";

    /** Loaded papers by type; a type whose paper could not be read maps to empty. */
    private static final Map<ResourceLocation, Optional<int[]>> CACHE = new ConcurrentHashMap<>();

    private PhotoPaperTextures() {}

    /** The paper for {@code type}, {@code PAPER_SIZE²} ARGB, or empty if it cannot be read. Any thread. */
    public static Optional<int[]> paper(PhotographType type) {
        return CACHE.computeIfAbsent(type.id(), id -> load(type)).map(int[]::clone);
    }

    private static Optional<int[]> load(PhotographType type) {
        boolean regular = type.id().equals(PhotographType.REGULAR.id());
        String modId = regular ? EXPOSURE_MOD_ID : DungeonTrain.MOD_ID;
        String path = "assets/" + modId + "/textures/photograph/"
                + (regular ? REGULAR_PAPER : type.id().getPath()) + ".png";
        try {
            IModFileInfo info = ModList.get().getModFileById(modId);
            if (info == null) return missing(path, "mod not loaded");
            Path file = info.getFile().findResource(path);
            if (!Files.exists(file)) return missing(path, "not in the jar");
            BufferedImage image;
            try (InputStream in = Files.newInputStream(file)) {
                image = ImageIO.read(in);
            }
            int size = PhotoPaperComposite.PAPER_SIZE;
            if (image == null || image.getWidth() != size || image.getHeight() != size) {
                return missing(path, "not a " + size + "x" + size + " image");
            }
            return Optional.of(image.getRGB(0, 0, size, size, null, 0, size));
        } catch (Exception e) {
            return missing(path, e.toString());
        }
    }

    private static Optional<int[]> missing(String path, String why) {
        LOGGER.warn("[DungeonTrain] Photo paper {} unavailable ({}); the tributed photo posts without it.", path, why);
        return Optional.empty();
    }
}
