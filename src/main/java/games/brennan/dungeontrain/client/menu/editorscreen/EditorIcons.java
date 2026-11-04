package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;

/** The GUI sprites the inventory-style editor screen draws, all 16px under {@code icon/}. */
public final class EditorIcons {

    public static final ResourceLocation SAVE = mod("icon/save");
    public static final ResourceLocation RENAME = mod("icon/rename");
    public static final ResourceLocation TRASH = mod("icon/trash");
    public static final ResourceLocation UNDO = mod("icon/undo");
    public static final ResourceLocation REDO = mod("icon/redo");
    public static final ResourceLocation RESET = mod("icon/reset");
    public static final ResourceLocation REROLL = mod("icon/reroll");
    public static final ResourceLocation CLEAR = mod("icon/clear");
    public static final ResourceLocation PACKAGE = mod("icon/folder");
    /** Out to the train, and back to your own profile — the two directions of one decision. */
    public static final ResourceLocation SUBMIT = mod("icon/export");
    public static final ResourceLocation WITHDRAW = mod("icon/import");
    public static final ResourceLocation PLAY = mod("icon/play");
    public static final ResourceLocation EXIT = mod("icon/exit");
    public static final ResourceLocation GROUP = mod("icon/group");
    /** The stage overview's own buttons: link, copy, and step the model back / forward. */
    public static final ResourceLocation APPLY = mod("icon/apply");
    public static final ResourceLocation DUPLICATE = mod("icon/duplicate");
    public static final ResourceLocation PREV = mod("icon/prev");
    public static final ResourceLocation NEXT = mod("icon/next");
    public static final ResourceLocation BAKE = mod("icon/bake");
    public static final ResourceLocation TEST_RELOAD = mod("icon/test_reload");
    public static final ResourceLocation TEST_REROLL_MAIN = mod("icon/test_reroll_main");
    public static final ResourceLocation TEST_REROLL_OTHERS = mod("icon/test_reroll_others");
    /** The toolbar's offset toggle, and the six directions of the row it opens. */
    public static final ResourceLocation OFFSET = mod("icon/offset");
    public static final ResourceLocation OFFSET_X_NEG = mod("icon/offset_x_neg");
    public static final ResourceLocation OFFSET_X_POS = mod("icon/offset_x_pos");
    public static final ResourceLocation OFFSET_Y_NEG = mod("icon/offset_y_neg");
    public static final ResourceLocation OFFSET_Y_POS = mod("icon/offset_y_pos");
    public static final ResourceLocation OFFSET_Z_NEG = mod("icon/offset_z_neg");
    public static final ResourceLocation OFFSET_Z_POS = mod("icon/offset_z_pos");
    public static final ResourceLocation SEARCH = ResourceLocation.withDefaultNamespace("icon/search");

    private EditorIcons() {}

    private static ResourceLocation mod(String path) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path);
    }

    /** The sprite for an icon-row action id, as {@link EditorScreenActions#icons} names them. */
    public static ResourceLocation forAction(String id) {
        return switch (id) {
            case "save" -> SAVE;
            case "rename" -> RENAME;
            case "remove" -> TRASH;
            case "undo" -> UNDO;
            case "redo" -> REDO;
            case "reset" -> RESET;
            case "reroll_shell" -> REROLL;
            case "clear" -> CLEAR;
            case "package", "open_files" -> PACKAGE;
            // Re-parenting is a group operation, so it wears the group sprite.
            case "move" -> GROUP;
            case OffsetControls.TOGGLE -> OFFSET;
            case "offset_x_neg" -> OFFSET_X_NEG;
            case "offset_x_pos" -> OFFSET_X_POS;
            case "offset_y_neg" -> OFFSET_Y_NEG;
            case "offset_y_pos" -> OFFSET_Y_POS;
            case "offset_z_neg" -> OFFSET_Z_NEG;
            case "offset_z_pos" -> OFFSET_Z_POS;
            case "submit" -> SUBMIT;
            case "withdraw" -> WITHDRAW;
            // The stage overview: Refresh re-rolls like Reset re-rolls, Delete is the same bin.
            case EditorStageActions.REFRESH -> RESET;
            case EditorStageActions.SELECT -> APPLY;
            case EditorStageActions.DUPLICATE -> DUPLICATE;
            case EditorStageActions.DELETE -> TRASH;
            case EditorStageActions.PREV -> PREV;
            case EditorStageActions.NEXT -> NEXT;
            case EditorStageActions.REBAKE -> BAKE;
            // The test toolbar: Reseed wears the die Reroll Shell does, its two halves their own.
            case TestModeControls.RELOAD -> TEST_RELOAD;
            case TestModeControls.REROLL_OTHERS -> TEST_REROLL_OTHERS;
            case TestModeControls.REROLL_MAIN -> TEST_REROLL_MAIN;
            case TestModeControls.RESEED_ALL -> REROLL;
            default -> SAVE;
        };
    }
}
