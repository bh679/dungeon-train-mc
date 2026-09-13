package games.brennan.dungeontrain.client.version.compare;

import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * The player-facing tags a changelog entry carries, in display order. Mirrors
 * {@code changelog_io.VALID_TAGS} and the ledger schema's enum; {@link #jsonId()} is the string
 * stored in {@code changelog.json}. A tag the client does not know (a newer ledger) is ignored by
 * {@link ChangelogLedgerParser} rather than failing the fetch.
 */
public enum ChangelogTag {
    FEATURE("feature"),
    CONTENT("content"),
    FIX("fix"),
    PERFORMANCE("performance"),
    EDITOR("editor"),
    MULTIPLAYER("multiplayer"),
    COMMUNITY("community"),
    TRANSLATIONS("translations"),
    COMPATIBILITY("compatibility"),
    TRAIN("train"),
    WORLD("world"),
    MOBS("mobs"),
    LOOT("loot"),
    BOOKS("books"),
    ADVANCEMENTS("advancements"),
    UI("ui"),
    BALANCE("balance");

    private static final String KEY = "gui.dungeontrain.version.compare.tag.";

    private final String jsonId;

    ChangelogTag(String jsonId) {
        this.jsonId = jsonId;
    }

    public String jsonId() {
        return jsonId;
    }

    public String langKey() {
        return KEY + jsonId;
    }

    public Component label() {
        return Component.translatable(langKey());
    }

    public static Optional<ChangelogTag> fromJson(String id) {
        if (id == null) return Optional.empty();
        for (ChangelogTag tag : values()) {
            if (tag.jsonId.equals(id)) return Optional.of(tag);
        }
        return Optional.empty();
    }
}
