package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.editorscreen.EditorScreenLang;
import games.brennan.dungeontrain.client.menu.editorscreen.TemplateDataSheet;
import games.brennan.dungeontrain.client.menu.editorscreen.VariantKey;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.editor.pool.Pool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Move to pool" — the two pools a shell or whole carriage can move to at its own size, each under
 * its own name or a new one typed here. The server does the move and refuses what cannot go
 * (unsaved edits, a taken name, a template that is not its tier's box); the roster refreshes itself
 * when it lands, which is why this screen holds no state.
 */
public final class PoolMovePickerScreen implements MenuScreen {

    private final Pool from;
    private final String id;

    public PoolMovePickerScreen(PlotCategory category, String id) {
        this.from = TemplateDataSheet.poolOf(category);
        this.id = id == null ? "" : id.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the Move icon opens this picker for {@code sel}: top-level shells and whole carriages.
     * Contents keep their sub-variant picker on the icon and move pools from the data sheet.
     */
    public static boolean supports(VariantKey sel) {
        if (sel == null || sel.isSubVariant()) return false;
        PlotCategory c = sel.category();
        return c == PlotCategory.CARRIAGES || c == PlotCategory.WHOLE || c == PlotCategory.WHOLE_HALF
            || c == PlotCategory.WHOLE_GROUP;
    }

    @Override
    public String title() {
        return EditorScreenLang.text(EditorScreenLang.POOL_TITLE);
    }

    @Override
    public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>(5);
        if (from != null) {
            for (Pool to : Pool.values()) {
                if (to == from) continue;
                String pool = EditorScreenLang.text(EditorScreenLang.poolName(to));
                String keep = TemplateDataSheet.poolMoveCommand(from, id, to);
                out.add(new CommandMenuEntry.Run(EditorScreenLang.text(EditorScreenLang.POOL_KEEP_NAME, pool), keep, false));
                out.add(new CommandMenuEntry.TypeArg(EditorScreenLang.text(EditorScreenLang.POOL_AS_NEW, pool), "name",
                    keep, "", ""));
            }
        }
        out.add(new CommandMenuEntry.Back(EditorScreenLang.text(EditorScreenLang.MOVE_BACK)));
        return out;
    }
}
