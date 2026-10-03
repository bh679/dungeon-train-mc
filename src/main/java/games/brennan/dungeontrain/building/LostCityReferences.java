package games.brennan.dungeontrain.building;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.JigsawStructureAccessor;
import games.brennan.dungeontrain.mixin.SinglePoolElementAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The official Lost City buildings — Big Lost City's own — as the Buildings tab's view-only <b>Lost City</b>
 * type shows them.
 *
 * <p>Exactly the buildings DT's Lost City places, read from the live registries rather than a list: every
 * structure in the {@code dungeontrain:lost_city} set whose start pool is Big Lost City's, and every template
 * that pool places (the {@code …lt} variants the mod actually uses). Nothing when Big Lost City is absent.</p>
 *
 * <p>Big Lost City is All Rights Reserved: these templates are only ever loaded through the server's template
 * manager and stamped into a view-only plot, exactly as worldgen places them. Nothing here writes, copies or
 * uploads one.</p>
 */
public final class LostCityReferences {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAMESPACE = "big_lost_city";
    private static final ResourceKey<StructureSet> SET =
        ResourceKey.create(Registries.STRUCTURE_SET, ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "lost_city"));

    /** One official building: its display name and the template worldgen places for it. */
    public record Reference(String name, ResourceLocation template) {}

    private static volatile List<Reference> cached;
    private static final Map<String, Vec3i> SIZES = new ConcurrentHashMap<>();

    private LostCityReferences() {}

    /** Every official building, sorted by name. Empty until a server has started, or without Big Lost City. */
    public static List<Reference> all() {
        List<Reference> list = cached;
        return list == null ? List.of() : list;
    }

    /** The official building called {@code name}, if there is one. */
    public static Optional<Reference> find(String name) {
        return all().stream().filter(r -> r.name().equals(name)).findFirst();
    }

    /** Read the list from {@code server}'s registries. Called when the server starts and on a reload. */
    public static synchronized void reload(MinecraftServer server) {
        SIZES.clear();
        cached = server == null ? List.of() : scan(server);
        LOGGER.info("[DungeonTrain] Lost City reference buildings: {}", cached.size());
    }

    public static synchronized void clear() {
        cached = null;
        SIZES.clear();
    }

    /** {@code ref}'s template, loaded through the server's template manager, or empty. */
    public static Optional<StructureTemplate> template(MinecraftServer server, Reference ref) {
        return server == null ? Optional.empty() : server.getStructureManager().get(ref.template());
    }

    /** {@code name}'s size, measured once. {@link Buildings#MIN_SIZE} for a name nothing can load. */
    public static Vec3i sizeOf(MinecraftServer server, String name) {
        return SIZES.computeIfAbsent(name, n -> find(n)
            .flatMap(ref -> template(server, ref))
            .map(StructureTemplate::getSize)
            .filter(s -> s.getX() > 0 && s.getY() > 0 && s.getZ() > 0)
            .orElse(Buildings.MIN_SIZE));
    }

    /**
     * An official building's NBT straight from Big Lost City's jar, for drawing its tile and preview — the
     * one place its blocks are read outside the server's template manager, and never to write them
     * anywhere. Tries the {@code lt} variant the pools place, then the plain one; empty without the mod.
     */
    public static Optional<net.minecraft.nbt.CompoundTag> readForPreview(String name) {
        if (name == null || !Buildings.NAME.matcher(name).matches()) return Optional.empty();
        String known = find(name).map(r -> r.template().getPath()).orElse(null);
        List<String> paths = known != null ? List.of(known) : List.of(name + "lt", name);
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (String path : paths) {
            String resource = "data/" + NAMESPACE + "/structure/" + path + ".nbt";
            try (java.io.InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) continue;
                return Optional.of(net.minecraft.nbt.NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap()));
            } catch (java.io.IOException e) {
                LOGGER.warn("[DungeonTrain] Could not read Lost City preview {}: {}", resource, e.toString());
            }
        }
        return Optional.empty();
    }

    /** The name a template goes by: its path, without Big Lost City's {@code lt} suffix. Pure. */
    static String displayName(ResourceLocation template) {
        String path = template.getPath();
        int slash = path.lastIndexOf('/');
        String base = slash < 0 ? path : path.substring(slash + 1);
        return base.endsWith("lt") && base.length() > 2 ? base.substring(0, base.length() - 2) : base;
    }

    private static List<Reference> scan(MinecraftServer server) {
        Map<String, Reference> byName = new TreeMap<>();
        try {
            Registry<StructureSet> sets = server.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
            StructureSet set = sets.get(SET);
            if (set == null) return List.of();
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                Structure structure = entry.structure().value();
                if (!(structure instanceof JigsawStructure jigsaw)) continue;
                Holder<StructureTemplatePool> pool = ((JigsawStructureAccessor) (Object) jigsaw).dungeontrain$startPool();
                boolean official = pool.unwrapKey().map(k -> NAMESPACE.equals(k.location().getNamespace())).orElse(false);
                if (!official) continue;
                for (StructurePoolElement element : pool.value().getShuffledTemplates(RandomSource.create(0L))) {
                    if (!(element instanceof SinglePoolElement single)) continue;
                    ResourceLocation template = ((SinglePoolElementAccessor) single).dungeontrain$template().left().orElse(null);
                    if (template == null || !NAMESPACE.equals(template.getNamespace())) continue;
                    byName.putIfAbsent(displayName(template), new Reference(displayName(template), template));
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("[DungeonTrain] Could not read the Lost City reference buildings", e);
            return List.of();
        }
        return List.copyOf(new ArrayList<>(byName.values()));
    }
}
