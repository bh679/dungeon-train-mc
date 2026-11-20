package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.builder.relay.BuilderRelayPreview;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorScreenLang;

import java.text.NumberFormat;
import java.util.List;

/**
 * "Load an oversized build?" — asked before the X menu loads or stages a relay build that is too
 * big to preview.
 *
 * <p>Oversized means over the preview caps, not over anything that stops it loading: the build will
 * load, it is just big enough to take a while. "Load anyway" runs the press exactly as it would have
 * gone; Cancel sends nothing.</p>
 */
public final class OversizedLoadConfirmScreen implements MenuScreen {

    private final String buildName;
    private final BuilderRelayPreview.Oversize oversize;
    private final Runnable onConfirm;

    /**
     * @param onConfirm the press to run on "Load anyway" — it also pops this modal, so the screen
     *                  underneath shows the load under way
     */
    public OversizedLoadConfirmScreen(String buildName, BuilderRelayPreview.Oversize oversize, Runnable onConfirm) {
        this.buildName = buildName == null ? "" : buildName;
        this.oversize = oversize;
        this.onConfirm = onConfirm == null ? () -> { } : onConfirm;
    }

    @Override
    public String title() {
        return EditorScreenLang.text(EditorScreenLang.CREATOR_OVERSIZED_TITLE);
    }

    @Override
    public List<CommandMenuEntry> entries() {
        String box = oversize.l() + "×" + oversize.h() + "×" + oversize.w();
        String blocks = NumberFormat.getIntegerInstance().format(oversize.blocks());
        return List.of(
            new CommandMenuEntry.Label(EditorScreenLang.text(EditorScreenLang.CREATOR_OVERSIZED_SIZE,
                buildName, box, blocks)),
            new CommandMenuEntry.Label(EditorScreenLang.text(EditorScreenLang.CREATOR_OVERSIZED_WARNING)),
            new CommandMenuEntry.ClientAction(EditorScreenLang.text(EditorScreenLang.CREATOR_OVERSIZED_CONFIRM),
                onConfirm),
            new CommandMenuEntry.Back(MenuLang.t("common.cancel")));
    }
}
