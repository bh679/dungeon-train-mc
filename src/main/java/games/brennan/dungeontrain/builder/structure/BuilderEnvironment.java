package games.brennan.dungeontrain.builder.structure;

import games.brennan.dungeontrain.builder.BuilderMode;

/**
 * What the ground under a Train Builder build looks like.
 *
 * <p>The third of the builder's three closed systems, beside the template and the structures around
 * it. It is a small question — grass or void — but it is a <em>different</em> question from both of
 * the others, and it used to be answered inside {@code BuilderWorldSetup} as a negation
 * ("everything except Train Dimensions has scenery") that four call sites each had to remember.</p>
 *
 * <p>Distinct from a structure on the one test that matters: <b>it is never a ghost</b>. The platform
 * is where you stand while you decide what the scenery should be, so it cannot itself be something
 * the scenery control can take away — ghosting it would mean the control could drop you out of the
 * world. That is why the platform is not in {@link BuilderStructureNeeds}, and why ghosting a
 * carriage shell lands you on grass rather than in the void.</p>
 *
 * <p>Pure, so the rule is testable without a world.</p>
 */
public enum BuilderEnvironment {

    /** The 300×300 slab of grass on bedrock, with the platform under everything. */
    FLATGRASS,

    /**
     * Open void — no platform, no floor.
     *
     * <p>Costs nothing to produce: the builder preset is a {@code minecraft:flat} with an
     * <em>empty</em> layer list, so void is what the generator already makes and the platform was
     * only ever a stamp on top of it.</p>
     */
    VOID;

    /**
     * Which environment a mode builds in.
     *
     * <p>{@link BuilderMode#TRAIN_DIMENSIONS} alone builds in void: a portal room <em>is</em> the
     * whole scene, and it stands in the empty space behind a tunnel portal rather than on a platform.
     * Everything else is a train being looked at from somewhere, which needs a floor to stand on.</p>
     */
    public static BuilderEnvironment forMode(BuilderMode mode) {
        return mode == BuilderMode.TRAIN_DIMENSIONS ? VOID : FLATGRASS;
    }

    /** Whether this environment lays a platform. */
    public boolean hasPlatform() {
        return this == FLATGRASS;
    }
}
