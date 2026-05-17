package games.brennan.dungeontrain.naming;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Ordered composition recipe — a chain of {@link NameSegment}s that the
 * {@link NameComposer} walks left-to-right, accumulating output.
 *
 * <p>Chains may reference other chains via {@link NameSegment.WeightedRef},
 * so a top-level {@code weapon_name_full} can repeatedly invoke
 * {@code title_combinations} which in turn invokes {@code title_prefix}
 * and a weighted pick across the category pools.</p>
 */
public record NameChain(ResourceLocation id, List<NameSegment> segments) {}
