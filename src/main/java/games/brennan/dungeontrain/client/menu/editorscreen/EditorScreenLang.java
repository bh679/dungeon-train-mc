package games.brennan.dungeontrain.client.menu.editorscreen;

import net.minecraft.network.chat.Component;

/**
 * Every lang key the inventory-style editor screen uses, in one place so a test can pin that each
 * one exists in {@code en_us.json}. The old X menu hard-coded its labels; this screen does not.
 */
public final class EditorScreenLang {

    private static final String PREFIX = "gui.dungeontrain.editor_screen.";

    public static final String TAB_CURRENT = PREFIX + "tab.current";
    public static final String TAB_CARRIAGES = PREFIX + "tab.carriages";
    public static final String TAB_CONTENTS = PREFIX + "tab.contents";
    public static final String TAB_TRACKS = PREFIX + "tab.tracks";
    public static final String TAB_DIMENSIONS = PREFIX + "tab.dimensions";
    public static final String TAB_MY_BUILDS = PREFIX + "tab.my_builds";
    public static final String TAB_SETTINGS = PREFIX + "tab.settings";
    public static final String TAB_EXIT = PREFIX + "tab.exit";

    public static final String FILTER_HINT = PREFIX + "filter.hint";
    public static final String FILTER_ALL = PREFIX + "filter.all";
    public static final String FILTER_BUILTIN = PREFIX + "filter.builtin";
    public static final String FILTER_MINE = PREFIX + "filter.mine";
    public static final String FILTER_COMMUNITY = PREFIX + "filter.community";

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
    public static final String SHEET_SOURCE = PREFIX + "sheet.source";
    public static final String SHEET_TYPE = PREFIX + "sheet.type";
    public static final String SHEET_STATUS = PREFIX + "sheet.status";
    public static final String SHEET_CHANGES = PREFIX + "sheet.changes";
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
    public static final String SAVE_ALL = PREFIX + "save_all";
    public static final String SAVE_ALL_DIRTY = PREFIX + "save_all_dirty";
    public static final String THEME = PREFIX + "theme";
    public static final String THEME_LIGHT = PREFIX + "theme.light";
    public static final String THEME_DARK = PREFIX + "theme.dark";
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
