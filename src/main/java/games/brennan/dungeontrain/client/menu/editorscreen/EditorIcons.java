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
    public static final ResourceLocation CLEAR = mod("icon/clear");
    public static final ResourceLocation PACKAGE = mod("icon/folder");
    /** Out to the train, and back to your own profile — the two directions of one decision. */
    public static final ResourceLocation SUBMIT = mod("icon/export");
    public static final ResourceLocation WITHDRAW = mod("icon/import");
    public static final ResourceLocation PLAY = mod("icon/play");
    public static final ResourceLocation EXIT = mod("icon/exit");
    public static final ResourceLocation GROUP = mod("icon/group");
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
            case "clear" -> CLEAR;
            case "package" -> PACKAGE;
            case "submit" -> SUBMIT;
            case "withdraw" -> WITHDRAW;
            default -> SAVE;
        };
    }
}
