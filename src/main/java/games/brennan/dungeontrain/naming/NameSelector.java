package games.brennan.dungeontrain.naming;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Maps an item tag (e.g. {@code minecraft:swords}) to a per-tier
 * {@link NameChain} id. The composer iterates registered selectors and
 * fires the first whose {@link #appliesTo()} tag matches the candidate
 * {@code ItemStack}.
 *
 * <p>{@link #tiers()} keys correspond to {@link NameTier#name()} lowercased.
 * Future item kinds (tools, shields, armor) add their own selector JSON
 * without any Java change.</p>
 */
public record NameSelector(
    ResourceLocation id,
    ResourceLocation appliesTo,
    Map<String, ResourceLocation> tiers
) {}
