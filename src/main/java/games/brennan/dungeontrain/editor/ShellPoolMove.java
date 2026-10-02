package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.ShellPool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Move a carriage template — and the sidecars stored beside it — from one {@link ShellPool} folder to
 * another: the X menu's Room · Half · Group switch.
 *
 * <p>The template itself arrives already re-lengthed ({@link TemplateLength}) and is written fresh
 * into the new pool. The sidecars are carried as they are: whichever copy is in effect (the user
 * tier's, else the bundled one) is written into the user tier at the new pool, and the user tier's
 * old copies are removed. In a dev checkout the source-tree copies move too, so a bundled template
 * ships from its new pool. A bundled copy left behind in its old pool is outranked by the user
 * tier's new one ({@code CarriageVariantRegistry}'s scan), so nothing is ever in two pools at once.</p>
 */
final class ShellPoolMove {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String SUBDIR = CarriageTemplateStore.SUBDIR;
    private static final String RESOURCE_ROOT = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    /** The files stored beside a carriage template, by extension. */
    private static final List<String> SIDECARS = List.of(".variants.json", ".parts.json", ".contents-allow.json", ".png");

    private ShellPoolMove() {}

    static void move(CarriageVariant variant, ShellPool to, StructureTemplate resized, boolean devMode) throws IOException {
        String id = variant.id();
        ShellPool from = ShellPool.poolOf(id);
        if (from == to) {
            CarriageTemplateStore.save(variant, resized);
            if (devMode) CarriageTemplateStore.saveToSource(variant, resized);
            return;
        }
        String oldPath = from.pathOf(id);
        String newPath = to.pathOf(id);

        // Read every sidecar in effect before anything moves.
        Map<String, byte[]> sidecars = new LinkedHashMap<>();
        for (String ext : SIDECARS) {
            byte[] bytes = effective(oldPath + ext);
            if (bytes != null) sidecars.put(ext, bytes);
        }

        Path userDir = UserContentPaths.dir(SUBDIR);
        ShellPool.set(id, to);
        try {
            CarriageTemplateStore.save(variant, resized);
            for (Map.Entry<String, byte[]> e : sidecars.entrySet()) {
                Path dst = userDir.resolve(newPath + e.getKey());
                Files.createDirectories(dst.getParent());
                Files.write(dst, e.getValue());
            }
        } catch (IOException e) {
            ShellPool.set(id, from);
            throw e;
        }
        Files.deleteIfExists(userDir.resolve(oldPath + ".nbt"));
        for (String ext : SIDECARS) Files.deleteIfExists(userDir.resolve(oldPath + ext));

        if (devMode && CarriageTemplateStore.sourceTreeAvailable()) moveInSource(variant, resized, oldPath, newPath);
        forgetLegacySize(id, to);

        CarriageVariantBlocks.invalidate(id);
        CarriageVariantPartsStore.invalidate(id);
        CarriageVariantContentsAllowStore.invalidate(id);
        LOGGER.info("[DungeonTrain] Moved carriage '{}' from the {} pool to the {} pool", id, from, to);
    }

    private static void moveInSource(CarriageVariant variant, StructureTemplate resized, String oldPath, String newPath)
            throws IOException {
        Path src = sourceDir();
        CarriageTemplateStore.saveToSource(variant, resized);   // writes at the new pool's path
        Files.deleteIfExists(src.resolve(oldPath + ".nbt"));
        for (String ext : SIDECARS) {
            Path old = src.resolve(oldPath + ext);
            if (!Files.isRegularFile(old)) continue;
            Path dst = src.resolve(newPath + ext);
            Files.createDirectories(dst.getParent());
            Files.move(old, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * A Room-folder template may still carry a size declaration from before the pools. Moved into a
     * pool it no longer needs one; moved back to Room, a declaration saying otherwise is overridden.
     */
    private static void forgetLegacySize(String id, ShellPool to) throws IOException {
        TemplateSizeStore shells = TemplateSizeStore.SHELLS;
        var declared = shells.explicit(id);
        if (declared.isEmpty()) return;
        if (to == ShellPool.ROOM) {
            if (declared.get() != games.brennan.dungeontrain.train.ContentsSize.ROOM) {
                shells.set(id, games.brennan.dungeontrain.train.ContentsSize.ROOM);
            }
        } else {
            shells.forget(id);
        }
    }

    /** The bytes of {@code name} under {@code templates/} as loading would find them: user tier, else bundled. */
    private static byte[] effective(String name) throws IOException {
        Path file = UserContentPaths.findFile(SUBDIR, name);
        if (file != null) return Files.readAllBytes(file);
        try (InputStream in = ShellPoolMove.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static Path sourceDir() {
        Path gameDir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
        return gameDir.getParent().resolve(SOURCE_REL_PATH);
    }
}
