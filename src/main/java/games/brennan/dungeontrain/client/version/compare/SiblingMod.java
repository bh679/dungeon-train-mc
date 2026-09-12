package games.brennan.dungeontrain.client.version.compare;

import net.neoforged.fml.ModList;

import java.util.Optional;

/**
 * The sibling mods Dungeon Train requires but does not bundle — each is its own download that
 * the launcher updates separately, so each can lag on its own. The ones jarJar'd inside DT
 * (DiscordPresence, EdibleBackpacks, Keep Trim) update with DT and are deliberately absent.
 *
 * <p>Latest versions are read from Modrinth only: the siblings publish to both platforms from one
 * workflow with no review queue, so one listing is the truth and it keeps the page to five
 * requests rather than ten.</p>
 */
public enum SiblingMod {
    ADVENTURE_ITEM_NAMES("adventureitemnames", "Adventure Item Names", "adventureitemnames"),
    ADVENTURE_ITEM_STATS("adventureitemstats", "Adventure Item Stats", "adventure-items-stats"),
    INTERACTIVE_PLAYER_MOBS("playermob", "Interactive Player Mobs", "interactive-player-mobs"),
    ENDER_CHEST_PERSISTENCE("enderchestpersistence", "Ender Chest Persistence", "ender-chest-persistence"),
    TRADE_EVERYTHING("tradeeverything", "Trade Everything", "trade-everything");

    private final String modId;
    private final String displayName;
    private final String modrinthSlug;

    SiblingMod(String modId, String displayName, String modrinthSlug) {
        this.modId = modId;
        this.displayName = displayName;
        this.modrinthSlug = modrinthSlug;
    }

    public String modId() { return modId; }
    public String displayName() { return displayName; }
    public String modrinthSlug() { return modrinthSlug; }

    /** The loaded mod's version, or empty when it is not on this client at all. */
    public Optional<FullSemver> installedVersion() {
        return ModList.get().getModContainerById(modId)
                .flatMap(c -> FullSemver.parse(c.getModInfo().getVersion().toString()));
    }
}
