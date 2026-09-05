package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGenerationMode;

/**
 * Client-side static holder for the values picked on
 * {@link DungeonTrainOptionsScreen} during world creation. Read on
 * {@code ServerStartedEvent} (integrated server) and committed into
 * {@link games.brennan.dungeontrain.world.DungeonTrainWorldData}, then cleared.
 *
 * Lifetime is intentionally tiny: from sub-screen "Done" until the integrated
 * server starts (a few hundred milliseconds). The only {@link #clear()} is the
 * one {@code WorldLifecycleEvents} makes after committing the values, so a
 * launch that is armed and then abandoned before its world loads leaves them
 * standing for the next world creation.
 *
 * <p>Also armed by the title screen's quick-world launchers
 * ({@code DevQuickWorldHandler.launchEditorWorld} / {@code launchBuilderWorld}),
 * which use it to create their worlds with {@code startsWithTrain = false}.</p>
 *
 * Client-only — never referenced from a class loaded on a dedicated server.
 */
public final class PendingWorldChoices {

    private static volatile Integer trainY;
    private static volatile Boolean startsWithTrain;
    private static volatile CarriageDims dims;
    private static volatile CarriageGenerationMode generationMode;
    private static volatile Integer groupSize;

    private PendingWorldChoices() {}

    public static void set(
            int trainY,
            boolean startsWithTrain,
            CarriageDims dims,
            CarriageGenerationMode generationMode,
            int groupSize
    ) {
        PendingWorldChoices.trainY = trainY;
        PendingWorldChoices.startsWithTrain = startsWithTrain;
        PendingWorldChoices.dims = dims;
        PendingWorldChoices.generationMode = generationMode;
        PendingWorldChoices.groupSize = groupSize;
    }

    public static boolean isPresent() {
        return trainY != null
                && startsWithTrain != null
                && dims != null
                && generationMode != null
                && groupSize != null;
    }

    public static int trainY() {
        return trainY;
    }

    public static boolean startsWithTrain() {
        return startsWithTrain;
    }

    public static CarriageDims dims() {
        return dims;
    }

    public static CarriageGenerationMode generationMode() {
        return generationMode;
    }

    public static int groupSize() {
        return groupSize;
    }

    public static void clear() {
        trainY = null;
        startsWithTrain = null;
        dims = null;
        generationMode = null;
        groupSize = null;
    }
}
