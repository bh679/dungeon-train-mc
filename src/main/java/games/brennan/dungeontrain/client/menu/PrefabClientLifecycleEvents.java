package games.brennan.dungeontrain.client.menu;

/**
 * Clear cached prefab state when the player disconnects so the next
 * connection starts clean. Without this, ids from the previous server
 * persist into the next session and the creative tab shows stale entries
 * until a new sync packet arrives.
 */
public final class PrefabClientLifecycleEvents {

    private PrefabClientLifecycleEvents() {}

    public static void onLoggingOut() {
        PrefabTabState.clear();
    }
}
