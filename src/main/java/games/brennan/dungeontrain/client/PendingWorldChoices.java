package games.brennan.dungeontrain.client;

/**
 * Client-side static holder for the values picked on
 * {@link DungeonTrainOptionsScreen} during world creation. Read on
 * {@code ServerStartedEvent} (integrated server) and committed into
 * {@link games.brennan.dungeontrain.world.DungeonTrainWorldData}, then cleared.
 *
 * Lifetime is intentionally tiny: from sub-screen "Done" until the integrated
 * server starts (a few hundred milliseconds). Cleared on
 * {@code CreateWorldScreen} close (cancel path) and on logout to avoid stale
 * values polluting subsequent world creations.
 *
 * Client-only — never referenced from a class loaded on a dedicated server.
 */
public final class PendingWorldChoices {

    private static volatile Integer trainY;
    private static volatile Boolean startsWithTrain;

    private PendingWorldChoices() {}

    public static void set(int trainY, boolean startsWithTrain) {
        PendingWorldChoices.trainY = trainY;
        PendingWorldChoices.startsWithTrain = startsWithTrain;
    }

    public static boolean isPresent() {
        return trainY != null && startsWithTrain != null;
    }

    public static int trainY() {
        return trainY;
    }

    public static boolean startsWithTrain() {
        return startsWithTrain;
    }

    public static void clear() {
        trainY = null;
        startsWithTrain = null;
    }
}
