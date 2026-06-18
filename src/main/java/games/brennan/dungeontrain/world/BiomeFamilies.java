package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Optional;

/**
 * Classifies overworld biomes into the eight "families" used by the
 * exploration advancements (the count-tier chain + "All Under Heaven").
 *
 * <p>Membership is data-driven via custom biome tags under
 * {@code data/dungeontrain/tags/worldgen/biome/family/<id>}. The families are
 * authored disjoint (each biome belongs to at most one), with snowy/frozen
 * variants taking precedence, so {@link #classify} returns at most one family.
 * Unclassified biomes (rivers, beaches, mushroom fields, caves) still count
 * toward the distinct-biome tiers but credit no family.</p>
 */
public final class BiomeFamilies {

    private BiomeFamilies() {}

    /** Number of families that make up the "All Under Heaven" set. */
    public static final int FAMILY_COUNT = 8;

    private static TagKey<Biome> family(String id) {
        return TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "family/" + id));
    }

    public static final TagKey<Biome> FOREST = family("forest");
    public static final TagKey<Biome> FROZEN = family("frozen");
    public static final TagKey<Biome> JUNGLE = family("jungle");
    public static final TagKey<Biome> DESERT = family("desert");
    public static final TagKey<Biome> SAVANNA = family("savanna");
    public static final TagKey<Biome> MOUNTAIN = family("mountain");
    public static final TagKey<Biome> WETLAND = family("wetland");
    public static final TagKey<Biome> OCEAN = family("ocean");

    /**
     * Family tags in priority order. Frozen is checked before forest / ocean /
     * mountain so any snowy variant (snowy taiga, frozen ocean, frozen peaks)
     * resolves to "frozen" even if the biome would also match a broader family.
     * The tag JSON is authored disjoint, so this ordering is belt-and-braces.
     */
    private static final List<FamilyEntry> FAMILIES = List.of(
        new FamilyEntry("frozen", FROZEN),
        new FamilyEntry("forest", FOREST),
        new FamilyEntry("jungle", JUNGLE),
        new FamilyEntry("desert", DESERT),
        new FamilyEntry("savanna", SAVANNA),
        new FamilyEntry("mountain", MOUNTAIN),
        new FamilyEntry("wetland", WETLAND),
        new FamilyEntry("ocean", OCEAN)
    );

    private record FamilyEntry(String id, TagKey<Biome> tag) {}

    /**
     * Resolve the family id for {@code biome}, or empty if it belongs to none
     * of the eight families.
     */
    public static Optional<String> classify(Holder<Biome> biome) {
        for (FamilyEntry e : FAMILIES) {
            if (biome.is(e.tag())) return Optional.of(e.id());
        }
        return Optional.empty();
    }

    /**
     * Localised display name for a family id, e.g. {@code "frozen"} → "Frozen"
     * (lang key {@code dungeontrain.biome_family.frozen}).
     */
    public static Component displayName(String familyId) {
        return Component.translatable("dungeontrain.biome_family." + familyId);
    }
}
