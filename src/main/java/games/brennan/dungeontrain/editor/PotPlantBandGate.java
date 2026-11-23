package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Which plants a <b>Random</b> flower pot ({@link VariantPot#isRandom}) may roll in a train carriage: a
 * plant from a band the train has not reached yet (a BetterEnd plant in the first overworld, a crimson
 * fungus before the first Nether) is left out of the pool. Plants are tied to their band by mod
 * namespace ({@link #sourceOf}); vanilla overworld plants and unknown mods are always allowed.
 *
 * <p>Reached is judged at the carriage's world X, like {@link VoidTotemGate}: a slot counts once the X
 * is past its start on the first run (a first-run split look once past its split), and on any later run
 * every slot of the first run is behind it, along with the later look of each slot met so far. Behind
 * the cycle anchor (the mirrored layout) and in the overworld buffer only ungated plants are allowed.
 * The train only runs forward (+X), so once reached a band stays reached.</p>
 */
public final class PotPlantBandGate {

    /** The band a plant comes from. */
    public enum Source { ANY, NETHER, BOP, WWOO, BETTER_NETHER, BETTER_END, OTHERSIDE }

    private static final Set<String> VANILLA_NETHER_PLANTS =
        Set.of("crimson_fungus", "warped_fungus", "crimson_roots", "warped_roots");

    /** Last answer for the live gate: a carriage stamps many pots with the same index. */
    private record Cached(WorldGenCycle cycle, long worldX, Set<Source> reached) {}

    private static volatile Cached cache;

    private PotPlantBandGate() {}

    /** The band {@code plant} comes from, by namespace. */
    public static Source sourceOf(ResourceLocation plant) {
        return switch (plant.getNamespace()) {
            case "minecraft" -> VANILLA_NETHER_PLANTS.contains(plant.getPath()) ? Source.NETHER : Source.ANY;
            case "biomesoplenty" -> Source.BOP;
            case "wwoo" -> Source.WWOO;
            case "betternether" -> Source.BETTER_NETHER;
            case "betterend" -> Source.BETTER_END;
            case "deeperdarker" -> Source.OTHERSIDE;
            default -> Source.ANY;
        };
    }

    /** Every band source the train has reached at {@code worldX} on {@code cycle} ({@link Source#ANY} always). */
    public static Set<Source> reached(WorldGenCycle cycle, long worldX) {
        Set<Source> out = EnumSet.of(Source.ANY);
        CycleLayout layout = cycle.layout();
        if (layout == null || worldX < cycle.startX()) return out;
        WorldGenCycle.BandPosition at = cycle.bandPosition(worldX);
        if (at == null || at.reversed()) return out;
        for (int i = 0; i < layout.count(); i++) {
            CycleLayout.Slot slot = layout.slot(i);
            boolean firstRunStarted = at.run() > 0 || i <= at.slot();
            if (!firstRunStarted) break;
            add(out, slot.type(), slot.style());
            if (slot.hasSplit() && (at.run() > 0 || i < at.slot() || at.local() >= slot.splitAt())) {
                add(out, slot.type(), slot.splitStyle());
            }
        }
        if (at.run() > 0) {
            for (int i = 0; i < layout.count() && i <= at.slot(); i++) {
                CycleLayout.Slot slot = layout.slot(i);
                add(out, slot.type(), slot.styleOnRun(at.run()));
            }
            // Earlier later-runs passed every slot's later look.
            if (at.run() > 1) {
                for (int i = 0; i < layout.count(); i++) {
                    CycleLayout.Slot slot = layout.slot(i);
                    add(out, slot.type(), slot.laterStyle());
                }
            }
        }
        return out;
    }

    private static void add(Set<Source> out, CycleLayout.Type type, CycleLayout.Style style) {
        if (type == CycleLayout.Type.NETHER) out.add(Source.NETHER);
        if (style == null) return;
        switch (style) {
            case BOP -> out.add(Source.BOP);
            case WWOO -> out.add(Source.WWOO);
            case BETTER -> {
                if (type == CycleLayout.Type.NETHER) out.add(Source.BETTER_NETHER);
                if (type == CycleLayout.Type.END) out.add(Source.BETTER_END);
            }
            case OTHERSIDE -> {
                if (type == CycleLayout.Type.NETHER) out.add(Source.OTHERSIDE);
            }
            default -> { }
        }
    }

    /** {@code plants} kept to those whose band is in {@code reached}, in order. */
    public static List<ResourceLocation> filter(List<ResourceLocation> plants, Set<Source> reached) {
        List<ResourceLocation> out = new ArrayList<>(plants.size());
        for (ResourceLocation id : plants) {
            if (reached.contains(sourceOf(id))) out.add(id);
        }
        return out;
    }

    /**
     * The bands reached by the train carriage at position index {@code carriageIndex} on the configured
     * cycle; {@code null} (no gating) when no server is running. A negative index counts as spawn.
     */
    public static Set<Source> reachedAtCarriage(int carriageIndex) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        int length = DungeonTrainWorldData.get(server.overworld()).dims().length();
        long worldX = (long) Math.max(0, carriageIndex) * length;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        Cached c = cache;
        if (c != null && c.cycle() == cycle && c.worldX() == worldX) return c.reached();
        Set<Source> reached = Set.copyOf(reached(cycle, worldX));
        cache = new Cached(cycle, worldX, reached);
        return reached;
    }
}
