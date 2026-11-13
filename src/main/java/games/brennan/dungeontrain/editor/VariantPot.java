package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Per-entry <b>plant</b> for a flower pot row ({@link FlowerPots#isPot}). When {@link #on} the pot
 * is planted per spawn with one of {@link #plants} (block ids, rolled evenly from the variant seed),
 * or — with no plants listed — with <b>Random</b>: any plant a pot accepts ({@link FlowerPots#allPlants}).
 *
 * <p>{@link #NONE} (off) is the default and is omitted from JSON, so older templates place the
 * captured pot unchanged. Sidecar token: {@code "random"} /
 * {@code "minecraft:poppy,minecraft:cactus"}.</p>
 */
public record VariantPot(boolean on, List<ResourceLocation> plants) {

    /** Keeps the token well under the sync packet's cap and the popup strip legible. */
    public static final int MAX_PLANTS = 12;

    /** Default: place the captured pot. */
    public static final VariantPot NONE = new VariantPot(false, List.of());

    /** Any plant a pot accepts. */
    public static final VariantPot RANDOM = new VariantPot(true, List.of());

    /** Salt distinct from the rotation / half / active / candle rolls. */
    private static final long POT_SEED_SALT = 0x5A17C0DE2B4F6E91L;

    public VariantPot {
        List<ResourceLocation> clean = new ArrayList<>();
        if (on && plants != null) {
            for (ResourceLocation id : plants) {
                if (id != null && !clean.contains(id) && clean.size() < MAX_PLANTS) clean.add(id);
            }
        }
        plants = List.copyOf(clean);
    }

    /** A pot planted with one of {@code plants} ({@code empty} = Random). */
    public static VariantPot of(List<ResourceLocation> plants) {
        return new VariantPot(true, plants);
    }

    /** True when this is the no-op default — used to skip JSON / NBT emission. */
    public boolean isDefault() {
        return !on;
    }

    /** True when the plant is any a pot accepts. */
    public boolean isRandom() {
        return on && plants.isEmpty();
    }

    /** Copy with {@code plant} added (no-op when already listed or full). */
    public VariantPot withPlant(ResourceLocation plant) {
        List<ResourceLocation> next = new ArrayList<>(plants);
        next.add(plant);
        return of(next);
    }

    /** Copy with {@code plant} removed — the last one going leaves Random. */
    public VariantPot withoutPlant(ResourceLocation plant) {
        List<ResourceLocation> next = new ArrayList<>(plants);
        next.remove(plant);
        return of(next);
    }

    /** {@code state} planted per this option, using the live pot map. */
    public BlockState apply(BlockState state, long posOrLockKey, long worldSeed, int carriageIndex) {
        return apply(state, posOrLockKey, worldSeed, carriageIndex, FlowerPots.allPlants(), FlowerPots::pottedFor);
    }

    /**
     * {@code state} planted with the rolled plant: deterministic for a given seed / carriage / cell
     * (or lock group). Unchanged when off, not a pot, or the rolled plant isn't pottable (a listed
     * plant whose mod is gone).
     */
    BlockState apply(BlockState state, long posOrLockKey, long worldSeed, int carriageIndex,
                     List<ResourceLocation> allPlants, Function<ResourceLocation, BlockState> potted) {
        if (isDefault() || !FlowerPots.isPot(state)) return state;
        List<ResourceLocation> pool = plants.isEmpty() ? allPlants : plants;
        if (pool.isEmpty()) return state;
        BlockState planted = potted.apply(pool.get(roll(posOrLockKey, worldSeed, carriageIndex, pool.size())));
        return planted == null ? state : planted;
    }

    static int roll(long posOrLockKey, long worldSeed, int carriageIndex, int size) {
        long h = worldSeed ^ (long) carriageIndex * 0x9E3779B97F4A7C15L ^ posOrLockKey ^ POT_SEED_SALT;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return (int) Math.floorMod(h, (long) size);
    }

    /** Sidecar / wire token: {@code "random"}, a comma list, or {@code ""} for the default. */
    public String id() {
        if (!on) return "";
        if (plants.isEmpty()) return "random";
        StringBuilder sb = new StringBuilder();
        for (ResourceLocation id : plants) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /** Parse a token; {@code null} when malformed. {@code null} / blank parses to {@link #NONE}. */
    @Nullable
    public static VariantPot parse(@Nullable String token) {
        if (token == null || token.isBlank()) return NONE;
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (t.equals("random")) return RANDOM;
        List<ResourceLocation> ids = new ArrayList<>();
        for (String part : t.split(",")) {
            ResourceLocation id = ResourceLocation.tryParse(part.trim());
            if (id == null) return null;
            ids.add(id);
        }
        return of(ids);
    }
}
