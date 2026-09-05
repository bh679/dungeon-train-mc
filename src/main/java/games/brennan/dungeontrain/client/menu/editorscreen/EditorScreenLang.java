package games.brennan.dungeontrain.client.menu.editorscreen;

import net.minecraft.network.chat.Component;

/**
 * Every lang key the inventory-style editor screen uses, in one place so a test can pin that each
 * one exists in {@code en_us.json}. The old X menu hard-coded its labels; this screen does not.
 */
public final class EditorScreenLang {

    private static final String PREFIX = "gui.dungeontrain.editor_screen.";

    public static final String TAB_ALL = PREFIX + "tab.all";
    public static final String TAB_CARRIAGES = PREFIX + "tab.carriages";
    public static final String TAB_CONTENTS = PREFIX + "tab.contents";
    public static final String TAB_TRACKS = PREFIX + "tab.tracks";
    public static final String TAB_DIMENSIONS = PREFIX + "tab.dimensions";
    public static final String TAB_SETTINGS = PREFIX + "tab.settings";
    public static final String TAB_EXIT = PREFIX + "tab.exit";

    public static final String FILTER_HINT = PREFIX + "filter.hint";
    public static final String FILTER_BUILTIN = PREFIX + "filter.builtin";
    public static final String FILTER_MINE = PREFIX + "filter.mine";
    public static final String FILTER_CREATOR = PREFIX + "filter.creator";
    public static final String FILTER_FIND_CREATOR = PREFIX + "filter.find_creator";

    /**
     * The in-menu builder search, which speaks the pause menu's own words.
     *
     * <p>Pointed at the existing {@code builder.creators.*} keys rather than copied into this
     * screen's namespace: it is the same search asking the same question, and two translations of
     * "No builder by that name" that could drift apart would be two chances to be wrong.</p>
     */
    public static final String CREATORS_TITLE = "gui.dungeontrain.builder.creators.title";
    public static final String CREATORS_HINT = "gui.dungeontrain.builder.creators.hint";
    public static final String CREATORS_ROW = "gui.dungeontrain.builder.creators.row";
    public static final String CREATORS_PROMPT = "gui.dungeontrain.builder.creators.prompt";
    public static final String CREATORS_FAVOURITES = "gui.dungeontrain.builder.creators.favourites";
    public static final String CREATORS_SEARCHING = "gui.dungeontrain.builder.creators.searching";
    public static final String CREATORS_NONE = "gui.dungeontrain.builder.creators.none";
    public static final String CREATORS_UNAVAILABLE = "gui.dungeontrain.builder.creators.unavailable";
    public static final String CREATORS_MINE = "gui.dungeontrain.builder.profile.back_to_mine";
    public static final String CREATOR_EMPTY = "gui.dungeontrain.builder.profile.empty_other";

    public static final String CREATOR_LOADING = PREFIX + "creator.loading";
    public static final String CREATOR_UNAVAILABLE = PREFIX + "creator.unavailable";
    public static final String CREATOR_BY = PREFIX + "creator.by";
    public static final String CREATOR_KIND = PREFIX + "creator.kind";
    public static final String CREATOR_CHANGES = PREFIX + "creator.changes";
    public static final String CREATOR_STATUS = PREFIX + "creator.status";
    public static final String CREATOR_READ_ONLY = PREFIX + "creator.read_only";
    public static final String CREATOR_NOTHING_SELECTED = PREFIX + "creator.nothing_selected";
    public static final String CREATOR_LOAD = "gui.dungeontrain.builder.profile.load_into_editor";
    public static final String CREATOR_LOAD_COPY = PREFIX + "creator.load_copy";
    public static final String CREATOR_LOADED = PREFIX + "creator.loaded";
    public static final String CREATOR_GOING = PREFIX + "creator.going";
    public static final String CREATOR_LOADING_BUILD = "gui.dungeontrain.builder.profile.downloading";

    public static final String SUB_VARIANTS_OF = PREFIX + "sub_variants_of";
    public static final String TILE_NEW = PREFIX + "tile.new";
    public static final String TILE_NEW_SUB_VARIANT = PREFIX + "tile.new_sub_variant";
    public static final String TILE_SELF = PREFIX + "tile.self";
    public static final String TILE_BUILTIN_ROOM = PREFIX + "tile.builtin_room";
    public static final String NO_ROSTER = PREFIX + "no_roster";
    public static final String NOTHING_SELECTED = PREFIX + "nothing_selected";

    public static final String YOU_ARE_HERE = PREFIX + "you_are_here";
    public static final String STANDING_IN = PREFIX + "standing_in";
    public static final String UNSAVED = PREFIX + "unsaved";

    public static final String SHEET_PATH = PREFIX + "sheet.path";
    public static final String SHEET_SIZE = PREFIX + "sheet.size";
    public static final String SHEET_BLOCKS = PREFIX + "sheet.blocks";
    public static final String SHEET_ENTITIES = PREFIX + "sheet.entities";
    public static final String SHEET_CONTAINERS = PREFIX + "sheet.containers";
    public static final String SHEET_WEIGHT = PREFIX + "sheet.weight";
    public static final String SHEET_SHARE = PREFIX + "sheet.share";
    public static final String SHEET_SPAWNS = PREFIX + "sheet.spawns";
    public static final String SHEET_LEVELS_ALL = PREFIX + "sheet.levels_all";
    public static final String SHEET_STAGE = PREFIX + "sheet.stage";
    public static final String SHEET_STAGE_TOOLTIP = PREFIX + "sheet.stage_tooltip";
    public static final String SHEET_TRAIN_SIZE = PREFIX + "sheet.train_size";
    public static final String GO_HERE = PREFIX + "go_here";
    public static final String STAGE_CUSTOM_SHORT = PREFIX + "stage_custom_short";
    public static final String SHEET_MIN_LEVEL = PREFIX + "sheet.min_level";
    public static final String SHEET_MAX_LEVEL = PREFIX + "sheet.max_level";
    public static final String SHEET_WEIGHT_TOOLTIP = PREFIX + "sheet.weight_tooltip";
    public static final String SHEET_WEIGHT_UP = PREFIX + "sheet.weight_up";
    public static final String SHEET_WEIGHT_DOWN = PREFIX + "sheet.weight_down";
    public static final String UNDO_NOTHING = PREFIX + "undo_nothing";
    public static final String REDO_NOTHING = PREFIX + "redo_nothing";
    public static final String SHEET_SOURCE = PREFIX + "sheet.source";
    public static final String SOURCE_BUILTIN = PREFIX + "source.builtin";
    public static final String SOURCE_MINE = PREFIX + "source.mine";
    public static final String SOURCE_COMMUNITY = PREFIX + "source.community";
    public static final String SHEET_PENDING = PREFIX + "sheet.pending";

    public static final String ICON_SAVE = PREFIX + "icon.save";
    public static final String ICON_RENAME = PREFIX + "icon.rename";
    public static final String ICON_REMOVE = PREFIX + "icon.remove";
    public static final String ICON_UNDO = PREFIX + "icon.undo";
    public static final String ICON_REDO = PREFIX + "icon.redo";
    public static final String ICON_RESET = PREFIX + "icon.reset";
    public static final String ICON_CLEAR = PREFIX + "icon.clear";
    public static final String ICON_PACKAGE = PREFIX + "icon.package";
    public static final String DISABLED_STAND_HERE = PREFIX + "disabled.stand_here";
    public static final String DISABLED_BUILTIN = PREFIX + "disabled.builtin";
    public static final String DISABLED_NOT_HERE = PREFIX + "disabled.not_here";

    public static final String TEST_CARRIAGE = PREFIX + "test_carriage";
    public static final String ENTER = PREFIX + "enter";
    public static final String THEME = PREFIX + "theme";
    public static final String THEME_LIGHT = PREFIX + "theme.light";
    public static final String THEME_DARK = PREFIX + "theme.dark";
    public static final String SKYBOX = PREFIX + "skybox";
    public static final String SKYBOX_ON = PREFIX + "skybox.on";
    public static final String SKYBOX_OFF = PREFIX + "skybox.off";
    public static final String RELAY = PREFIX + "relay";
    public static final String RELAY_LIVE = PREFIX + "relay.live";
    public static final String RELAY_DEV = PREFIX + "relay.dev";
    public static final String EXIT_EDITOR = PREFIX + "exit_editor";
    public static final String WEIGHT = PREFIX + "weight";
    public static final String WEIGHT_READ_ONLY = PREFIX + "weight_read_only";
    public static final String PHASES = PREFIX + "phases";
    public static final String STAGE_CUSTOM = PREFIX + "stage_custom";
    public static final String STAGE_LINKED = PREFIX + "stage_linked";
    public static final String CONTENTS_ALLOW = PREFIX + "contents_allow";

    private EditorScreenLang() {}

    /** The English-side string for a key, resolved through the loaded language. */
    public static String text(String key) {
        return Component.translatable(key).getString();
    }

    public static String text(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
