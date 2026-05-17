package games.brennan.dungeontrain.naming;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * One link in a {@link NameChain} — picks a weighted pool/chain ref, fires
 * with probability {@code chance}, and prepends {@code connection}
 * (optionally with a leading newline) when it does.
 *
 * <p>Mirrors the original Unity {@code FollowingLine} struct
 * ({@code chanceOfAdding}, {@code connection}, {@code newLine}) but extends
 * each weighted choice to point at either a {@link NamePool} or another
 * {@link NameChain} so multi-level composition ("Title Prefix + of + Title
 * Combinations") stays expressible in JSON.</p>
 */
public record NameSegment(
    List<WeightedRef> refs,
    float chance,
    String connection,
    boolean newline
) {
    public record WeightedRef(ResourceLocation ref, float weight) {}
}
