package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The block-variant documents of DT's own worldgen buildings: {@code <name>.variants.json} beside the
 * building's {@code .nbt}, in the schema every other variants sidecar uses
 * ({@link TrackVariantBlocks#fromJsonText}). Loaded through the resource manager, so a datapack can
 * replace one and {@code /reload} picks it up, and published as an immutable map the worldgen threads
 * only read.
 *
 * <p>A building with no document has no entry, and {@link LostCityVariantsProcessor} leaves it exactly
 * as it generates today.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LostCityVariantDocs {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String SUFFIX = ".variants.json";
    private static final String STRUCTURES = "structure/";
    /** Template folders that may carry a document — DT's Lost City buildings and player-added ones. */
    private static final List<String> ROOTS = List.of("structure/lost_city", "structure/buildings");

    private static volatile Map<ResourceLocation, Doc> docs = Map.of();

    /** One building's pools, with its cells as a set for the per-block lookup. */
    public record Doc(TrackVariantBlocks blocks, Set<BlockPos> cells) {
        boolean has(BlockPos local) {
            return cells.contains(local);
        }
    }

    private LostCityVariantDocs() {}

    /** The document for the template {@code dungeontrain:lost_city/<name>}, or {@code null}. */
    @Nullable
    static Doc get(ResourceLocation template) {
        return docs.get(template);
    }

    /** Replaces the published set (the reload listener, and tests). */
    static void publish(Map<ResourceLocation, Doc> next) {
        docs = Map.copyOf(next);
    }

    /** Parses one document; a document with no usable cell is {@code null}. */
    @Nullable
    public static Doc parse(ResourceLocation template, String json) {
        TrackVariantBlocks blocks = TrackVariantBlocks.fromJsonText(json, null, template.toString(), null);
        if (blocks.isEmpty()) return null;
        Set<BlockPos> cells = new HashSet<>();
        for (CarriageVariantBlocks.Entry entry : blocks.entries()) cells.add(entry.localPos());
        return new Doc(blocks, Set.copyOf(cells));
    }

    /** The template id a document resource belongs to: {@code structure/lost_city/a.variants.json} → {@code lost_city/a}. */
    static ResourceLocation templateOf(ResourceLocation resource) {
        String path = resource.getPath();
        return ResourceLocation.fromNamespaceAndPath(resource.getNamespace(),
                path.substring(STRUCTURES.length(), path.length() - SUFFIX.length()));
    }

    static void load(ResourceManager resources) {
        Map<ResourceLocation, Doc> next = new HashMap<>();
        for (String root : ROOTS) {
            for (Map.Entry<ResourceLocation, Resource> found
                    : resources.listResources(root, id -> id.getPath().endsWith(SUFFIX)).entrySet()) {
                ResourceLocation template = templateOf(found.getKey());
                try (Reader reader = found.getValue().openAsReader()) {
                    Doc doc = parse(template, readAll(reader));
                    if (doc != null) next.put(template, doc);
                } catch (IOException | RuntimeException e) {
                    LOGGER.error("[DungeonTrain] Building variants {} failed to load; that building generates without them",
                            found.getKey(), e);
                }
            }
        }
        publish(next);
        LOGGER.info("[DungeonTrain] Building variants: {} document(s) loaded", next.size());
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[8192];
        for (int n; (n = reader.read(buffer)) >= 0; ) text.append(buffer, 0, n);
        return text.toString();
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                load(resourceManager);
            }

            @Override
            public String getName() {
                return "dungeontrain:building_variants";
            }
        });
    }
}
