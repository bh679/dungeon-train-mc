package games.brennan.dungeontrain.client;

import net.neoforged.fml.ModList;

/**
 * Is this the Dungeon Train modpack, or the bare mod added to some other instance? Used to switch
 * player-facing copy (the Support screen, the update help card) between mod and modpack wording.
 *
 * <p>A heuristic: the sibling mods always load, so they can't tell the two apart. The companions
 * below ship in the modpack but are not DT dependencies, and two or more of them present is read as
 * the modpack. A player who adds them to a bare install gets modpack wording, which still works.</p>
 */
public final class ModpackInstall {

    private static final String[] MODPACK_COMPANIONS =
            {"appleskin", "ferritecore", "modernfix", "advancementplaques"};

    private ModpackInstall() {}

    public static boolean detected() {
        int found = 0;
        for (String id : MODPACK_COMPANIONS) {
            if (ModList.get().isLoaded(id) && ++found >= 2) return true;
        }
        return false;
    }
}
