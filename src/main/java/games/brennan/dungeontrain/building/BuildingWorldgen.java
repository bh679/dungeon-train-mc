package games.brennan.dungeontrain.building;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.SinglePoolElementAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Buildings as worldgen sees them.
 *
 * <p><b>Edits</b> reach worldgen through the template manager: {@code StructureTemplateManagerBuildingMixin}
 * loads the player's copy of a building ahead of the jar's, and {@link #evict} drops a building's cached
 * template after a save so the next chunk generated places the new one. Chunks already generated keep what
 * they have.</p>
 *
 * <p><b>New buildings</b> place from one slot, {@link Buildings#PLAYER_STRUCTURE}, whose pool holds a single
 * placeholder element ({@link #SLOT_TEMPLATE}). {@code StructureTemplatePoolBuildingMixin} swaps it for
 * {@link #pick}: one of this world's new buildings, weighted by {@link BuildingMeta}, as a plain rigid
 * element with no processors. With no new buildings the pick is the empty element, which vanilla's jigsaw
 * start treats as "no structure here" and the structure set then tries its other buildings — though
 * {@code StructureBasementMixin} refuses the slot before that when {@link #hasNewBuildings} is false.</p>
 */
public final class BuildingWorldgen {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The placeholder the new-building pool carries; never a real template. */
    public static final ResourceLocation SLOT_TEMPLATE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "building_slot");

    private record Entry(String name, int weight, StructurePoolElement element) {}

    private record Roster(List<Entry> entries, int totalWeight) {
        static final Roster EMPTY = new Roster(List.of(), 0);
    }

    private static volatile Roster roster;

    private BuildingWorldgen() {}

    /** True when this world has any new building to place. */
    public static boolean hasNewBuildings() {
        return roster().totalWeight() > 0;
    }

    /** The new-building names worldgen draws from, in roster order. */
    public static List<String> newBuildingNames() {
        return roster().entries().stream().map(Entry::name).toList();
    }

    /**
     * One of this world's new buildings, weighted — or {@link EmptyPoolElement#INSTANCE} when there are none.
     * Consumes exactly one {@code nextInt} when there is something to pick.
     */
    public static StructurePoolElement pick(RandomSource random) {
        Roster r = roster();
        if (r.totalWeight() <= 0) return EmptyPoolElement.INSTANCE;
        int[] weights = r.entries().stream().mapToInt(Entry::weight).toArray();
        return r.entries().get(indexFor(weights, random.nextInt(r.totalWeight()))).element();
    }

    /** Which of {@code weights} a roll of {@code roll} ({@code 0 <= roll < sum}) lands on. Pure. */
    static int indexFor(int[] weights, int roll) {
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) return i;
        }
        return weights.length - 1;
    }

    /** Drop {@code name}'s cached template and the roster, so worldgen reads both fresh. */
    public static void evict(MinecraftServer server, String name) {
        roster = null;
        if (server == null || name == null) return;
        server.getStructureManager().remove(Buildings.shippedTemplateId(name));
        server.getStructureManager().remove(Buildings.playerTemplateId(name));
    }

    /** Drop every building's cached template and the roster — a template reload or package switch. */
    public static void evictAll(MinecraftServer server) {
        roster = null;
        if (server == null) return;
        for (String name : BuildingRegistry.names()) evict(server, name);
    }

    /** True when {@code element} is the new-building slot's placeholder. */
    public static boolean isSlot(StructurePoolElement element) {
        return element instanceof SinglePoolElement single
            && ((SinglePoolElementAccessor) single).dungeontrain$template()
                .left().map(SLOT_TEMPLATE::equals).orElse(false);
    }

    private static Roster roster() {
        Roster r = roster;
        if (r != null) return r;
        synchronized (BuildingWorldgen.class) {
            if (roster == null) roster = build();
            return roster;
        }
    }

    private static Roster build() {
        List<Entry> entries = new ArrayList<>();
        int total = 0;
        try {
            for (String name : BuildingRegistry.names()) {
                if (BuildingStore.isShipped(name)) continue;
                if (!BuildingStore.hasPlayerCopy(name) && !BuildingStore.isBundled(name)) continue;
                int weight = BuildingMeta.load(name).weight();
                StructurePoolElement element = StructurePoolElement
                    .single(Buildings.playerTemplateId(name).toString())
                    .apply(StructureTemplatePool.Projection.RIGID);
                entries.add(new Entry(name, weight, element));
                total += weight;
            }
        } catch (RuntimeException e) {
            // No new buildings rather than a worldgen crash: the shipped roster is unaffected either way.
            LOGGER.error("[DungeonTrain] Building roster failed to load; no new buildings will generate", e);
            return Roster.EMPTY;
        }
        LOGGER.info("[DungeonTrain] New-building roster: {} buildings, total weight {}", entries.size(), total);
        return new Roster(List.copyOf(entries), total);
    }
}
