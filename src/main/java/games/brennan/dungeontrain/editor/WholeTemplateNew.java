package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupPlacer;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.train.WholeKind;
import games.brennan.dungeontrain.train.WholeWeights;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Whole section's <b>New</b> — a room or group made in the editor rather than the Train Builder.
 *
 * <p>Blank captures an empty plot; a source copies that template's geometry, weights entry, Z-menu
 * variant pools and C-menu container links, the same set {@link CarriageEditor#duplicate} carries
 * for a carriage.</p>
 *
 * <h2>Row shift</h2>
 * <p>Custom ids list alphabetically, so a new id can land mid-row and push every plot after it one
 * slot along. The row is cleared at its old slots before the id registers and restamped at the new
 * ones after — the same dance {@code whole reset} does in the other direction.</p>
 */
public final class WholeTemplateNew {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String CONTAINERS_RESOURCE = "/data/dungeontrain/" + ContainerContentsStore.SUBDIR + "/";

    private WholeTemplateNew() {}

    /**
     * Create {@code name} of {@code kind}, blank when {@code sourceId} is null, stamp it and walk the
     * player in. The caller has validated the name and made WHOLE the resident category.
     *
     * @return the new plot's origin
     */
    public static BlockPos create(ServerPlayer player, WholeKind kind, String name, @Nullable String sourceId)
        throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        Template target = template(kind, name);

        // Resolve the copy before a block moves: a source that doesn't fit this world's dims must
        // fail with the row untouched.
        StructureTemplate copied = sourceId == null ? null : sourceGeometry(overworld, kind, sourceId, dims);

        for (Template m : row(kind)) WholeCarriageEditor.clearPlot(overworld, m, dims);
        if (!register(kind, name)) {
            restampRow(overworld, kind, dims);
            throw new IOException("'" + name + "' is already registered.");
        }

        BlockPos origin = WholeCarriageEditor.plotOrigin(target, dims);
        try {
            if (origin == null) throw new IOException("Failed to allocate a plot for '" + name + "'.");
            StructureTemplate geometry = copied != null ? copied : captureBlank(overworld, kind, origin, dims);
            save(kind, name, geometry);
            if (sourceId != null) copySidecars(kind, sourceId, name);
        } catch (IOException | RuntimeException e) {
            unregister(kind, name);
            restampRow(overworld, kind, dims);
            throw e;
        }

        restampRow(overworld, kind, dims);
        WholeCarriageEditor.enter(player, target, false, false, EditorPlotArrival.Inside.FRONT_DOOR);
        games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
            .trigger(player, "made_carriage");
        LOGGER.info("[DungeonTrain] Whole editor new: {} created {} '{}' from {} at {}",
            player.getName().getString(), kind.id(), name, sourceId == null ? "blank" : "'" + sourceId + "'", origin);
        return origin;
    }

    // ---- geometry -------------------------------------------------------------------------------

    private static StructureTemplate sourceGeometry(ServerLevel overworld, WholeKind kind, String sourceId,
                                                    CarriageDims dims) throws IOException {
        java.util.Optional<StructureTemplate> found = switch (kind) {
            case GROUP -> CarriageGroupTemplateStore.get(overworld, new CarriageGroup(sourceId), dims, WholeCarriageEditor.groupSize());
            case HALF -> WholeHalfTemplateStore.get(overworld, new WholeCarriage(sourceId), dims);
            case ROOM -> WholeCarriageTemplateStore.get(overworld, new WholeCarriage(sourceId), dims);
        };
        return found.orElseThrow(() -> new IOException(
            "'" + sourceId + "' has no saved geometry that fits this world's carriage size."));
    }

    private static StructureTemplate captureBlank(ServerLevel overworld, WholeKind kind, BlockPos origin,
                                                  CarriageDims dims) {
        if (kind == WholeKind.GROUP) {
            int n = WholeCarriageEditor.groupSize();
            CarriageGroupPlacer.eraseAt(overworld, origin, dims, n);
            return CarriageGroupPlacer.captureTemplate(overworld, origin, dims, n);
        }
        CarriageDims box = kind == WholeKind.HALF ? WholeHalfTemplateStore.halfDims(dims) : dims;
        CarriagePlacer.eraseAt(overworld, origin, box);
        return WholeCarriagePlacer.captureTemplate(overworld, origin, box);
    }

    /** User tier, plus the source tree in dev mode — the same pair {@link WholeCarriageEditor} saves write. */
    private static void save(WholeKind kind, String id, StructureTemplate geometry) throws IOException {
        switch (kind) {
            case GROUP -> CarriageGroupTemplateStore.save(new CarriageGroup(id), geometry);
            case HALF -> WholeHalfTemplateStore.save(new WholeCarriage(id), geometry);
            case ROOM -> WholeCarriageTemplateStore.save(new WholeCarriage(id), geometry);
        }
        if (!EditorDevMode.isEnabled()) return;
        try {
            switch (kind) {
                case GROUP -> CarriageGroupTemplateStore.saveToSource(new CarriageGroup(id), geometry);
                case HALF -> WholeHalfTemplateStore.saveToSource(new WholeCarriage(id), geometry);
                case ROOM -> WholeCarriageTemplateStore.saveToSource(new WholeCarriage(id), geometry);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Whole editor new: source write failed for {}: {}", id, e.toString());
        }
    }

    // ---- sidecars -------------------------------------------------------------------------------

    /** Weights entry, variant pools and container links — everything beside the {@code .nbt}. */
    private static void copySidecars(WholeKind kind, String from, String to) throws IOException {
        WholeWeights.copy(kind, from, to);

        String variants = readText(WholeVariantBlocks.configPathFor(kind, from),
            kind.bundledResourcePrefix() + from + WholeVariantBlocks.EXT);
        if (variants != null) {
            write(WholeVariantBlocks.configPathFor(kind, to), variants);
            if (EditorDevMode.isEnabled()) {
                Path src = WholeVariantBlocks.sourcePathFor(kind, to);
                if (src != null) write(src, variants);
            }
            WholeVariantBlocks.invalidate(kind, to);
        }

        String fromKey = BlockVariantPlot.wholeKey(kind, from);
        String toKey = BlockVariantPlot.wholeKey(kind, to);
        String fromName = ContainerContentsStore.basenameFor(fromKey);
        Path fromFile = UserContentPaths.findFile(ContainerContentsStore.SUBDIR, fromName);
        String containers = readText(fromFile, CONTAINERS_RESOURCE + fromName);
        if (containers != null) {
            write(UserContentPaths.dir(ContainerContentsStore.SUBDIR)
                .resolve(ContainerContentsStore.basenameFor(toKey)), containers);
            ContainerContentsStore.invalidate(toKey);
        }
        ProvenanceCache.invalidateAll();
    }

    /** The file's text, else the bundled resource's, else null. */
    @Nullable
    private static String readText(@Nullable Path file, String resource) throws IOException {
        if (file != null && Files.isRegularFile(file)) return Files.readString(file, StandardCharsets.UTF_8);
        try (InputStream in = WholeTemplateNew.class.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    // ---- registry -------------------------------------------------------------------------------

    /** The model for {@code id} under {@code kind}. */
    public static Template template(WholeKind kind, String id) {
        return switch (kind) {
            case GROUP -> new Template.CarriageGroup(new CarriageGroup(id));
            case HALF -> new Template.WholeHalf(new WholeCarriage(id));
            case ROOM -> new Template.WholeCarriage(new WholeCarriage(id));
        };
    }

    /** Every registered id under {@code kind}, in row order. */
    public static List<String> ids(WholeKind kind) {
        return switch (kind) {
            case GROUP -> CarriageGroupRegistry.ids();
            case HALF -> games.brennan.dungeontrain.train.WholeHalfRegistry.ids();
            case ROOM -> WholeCarriageRegistry.ids();
        };
    }

    private static List<Template> row(WholeKind kind) {
        return ids(kind).stream().map(id -> template(kind, id)).toList();
    }

    public static boolean register(WholeKind kind, String id) {
        return switch (kind) {
            case GROUP -> CarriageGroupRegistry.register(new CarriageGroup(id));
            case HALF -> games.brennan.dungeontrain.train.WholeHalfRegistry.register(new WholeCarriage(id));
            case ROOM -> WholeCarriageRegistry.register(new WholeCarriage(id));
        };
    }

    public static void unregister(WholeKind kind, String id) {
        switch (kind) {
            case GROUP -> CarriageGroupRegistry.unregister(id);
            case HALF -> games.brennan.dungeontrain.train.WholeHalfRegistry.unregister(id);
            case ROOM -> WholeCarriageRegistry.unregister(id);
        }
    }

    private static void restampRow(ServerLevel overworld, WholeKind kind, CarriageDims dims) {
        for (Template m : row(kind)) WholeCarriageEditor.stampPlot(overworld, m, dims);
    }
}
