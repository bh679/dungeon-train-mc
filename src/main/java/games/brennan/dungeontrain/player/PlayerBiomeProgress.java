package games.brennan.dungeontrain.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-player "current run" biome-exploration state, attached to
 * {@link net.minecraft.world.entity.player.Player} via NeoForge AttachmentType.
 *
 * <p>Tracks the distinct biomes and biome <em>families</em> the player has
 * ridden through in the current life. Drives the exploration advancements: the
 * count tiers ("Far Afield" / "Many Lands" / "World Without End") read
 * {@link #biomeCount()}; the family-collection challenge ("All Under Heaven")
 * reads {@link #familyCount()}.</p>
 *
 * <p>This lives in its own attachment rather than as fields on
 * {@link PlayerRunState} because that class's {@code RecordCodecBuilder.group(...)}
 * is already at the 16-field cap (see the note on
 * {@code PlayerRunState#narrativeLetters}). Persisted via {@link #CODEC} so
 * progress survives logout / world reload. Reset on death by
 * {@code AchievementEvents}; the attachment is deliberately <b>not</b>
 * {@code copyOnDeath}, so the respawn clone already starts empty — we
 * {@link #clear()} explicitly to be defensive.</p>
 */
public final class PlayerBiomeProgress {

    public static final Codec<PlayerBiomeProgress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ResourceLocation.CODEC.listOf().optionalFieldOf("biomes", List.of()).forGetter(PlayerBiomeProgress::biomesList),
        Codec.STRING.listOf().optionalFieldOf("families", List.of()).forGetter(PlayerBiomeProgress::familiesList)
    ).apply(instance, PlayerBiomeProgress::new));

    /** Distinct biome ids ridden through this run; drives the count-tier advancements. */
    private final Set<ResourceLocation> biomes;
    /** Distinct family ids (e.g. {@code "frozen"}) covered this run; drives "All Under Heaven". */
    private final Set<String> families;

    public PlayerBiomeProgress() {
        this.biomes = new HashSet<>();
        this.families = new HashSet<>();
    }

    public PlayerBiomeProgress(List<ResourceLocation> biomes, List<String> families) {
        this.biomes = new HashSet<>(biomes);
        this.families = new HashSet<>(families);
    }

    /** Codec-friendly view (List, not Set). */
    public List<ResourceLocation> biomesList() {
        return new ArrayList<>(biomes);
    }

    /** Codec-friendly view (List, not Set). */
    public List<String> familiesList() {
        return new ArrayList<>(families);
    }

    /**
     * Record a biome ridden through this run.
     *
     * @return {@code true} if newly seen this run (caller fires the count
     *         trigger), {@code false} if already counted.
     */
    public boolean addBiome(ResourceLocation id) {
        return biomes.add(id);
    }

    /**
     * Record a biome family reached this run.
     *
     * @return {@code true} if newly reached this run (caller fires the family
     *         trigger + discovery message), {@code false} if already counted.
     */
    public boolean addFamily(String familyId) {
        return families.add(familyId);
    }

    /** Number of distinct biomes ridden through this run. */
    public int biomeCount() {
        return biomes.size();
    }

    /** Number of distinct biome families reached this run. */
    public int familyCount() {
        return families.size();
    }

    /** Drop all biome/family progress (called on respawn). */
    public void clear() {
        biomes.clear();
        families.clear();
    }
}
