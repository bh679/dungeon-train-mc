package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * What a submission state looks like on a tile: an icon for the corner, and a colour for the border.
 *
 * <p>Two marks for one fact, on purpose. The border is what makes a wall of builds readable at a
 * glance — it is the only part still legible when six tiles share the width and the icon is eight
 * pixels across — and the icon is what says <em>which</em> state without relying on colour, which a
 * colour-blind player cannot read and a screenshot cannot always carry.</p>
 *
 * <p>A build nobody has submitted has no badge at all ({@code null} from {@link #of}), rather than a
 * fourth icon. Most builds are in that state most of the time, and marking them too would leave the
 * three that mean something with nothing to stand out against.</p>
 */
@OnlyIn(Dist.CLIENT)
record BuilderReviewBadge(int borderColour, ResourceLocation icon) {

    private static final ResourceLocation SUBMITTED =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/review_submitted");
    private static final ResourceLocation ACCEPTED =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/review_accepted");
    private static final ResourceLocation DECLINED =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/review_declined");

    /** The badge for a review state, or null for one that isn't marked. */
    static BuilderReviewBadge of(String review) {
        return switch (BuilderReviewState.of(review)) {
            case BuilderReviewState.SUBMITTED ->
                    new BuilderReviewBadge(BuilderReviewState.BORDER_SUBMITTED, SUBMITTED);
            case BuilderReviewState.ACCEPTED ->
                    new BuilderReviewBadge(BuilderReviewState.BORDER_ACCEPTED, ACCEPTED);
            case BuilderReviewState.DECLINED ->
                    new BuilderReviewBadge(BuilderReviewState.BORDER_DECLINED, DECLINED);
            default -> null;
        };
    }
}
