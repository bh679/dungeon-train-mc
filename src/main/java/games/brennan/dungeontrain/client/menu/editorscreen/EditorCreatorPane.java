package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.BuilderSubmitNoteScreen;
import games.brennan.dungeontrain.client.builder.BuilderSubmitHintsRequests;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The right pane while the browser is showing somebody else's uploads.
 *
 * <p>The same shape as {@link EditorDetailPane} — a name, a picture, a short sheet — with two
 * controls rather than its toolbar, because two is all that applies to a row on the relay: bring the
 * build down, and once it is down, go and stand in it. Everything else here is read.</p>
 */
public final class EditorCreatorPane {

    static final int LINE_H = 10;
    static final int LABEL_W = 52;
    static final int LOADED_TEXT = 0xFF88DD88;

    /** What a click landed on. */
    public enum HitKind { NONE, LOAD, PARENT, GO_HERE, PREVIEW, OLDER, NEWER, SUBMIT, PAGE_PREV, PAGE_NEXT, EDIT_NOTE }

    /** The parent button's share of the load slot; the load button keeps the rest. */
    static final double PARENT_SHARE = 0.42;
    static final int BUTTON_GAP = 2;

    private final VersionStrip versions = new VersionStrip();

    private InventoryEditorLayout.Rect loadRect;
    private InventoryEditorLayout.Rect parentRect;
    private InventoryEditorLayout.Rect goHereRect;
    private InventoryEditorLayout.Rect previewRect;
    private InventoryEditorLayout.Rect submitRect;

    /**
     * The sheet's pages: 0 is who made it and what happened to it, 1 is the build's Submit for Review
     * questions and what its author answered — there for every build, answered or not.
     */
    private int sheetPage;
    private int sheetPageCount = 1;
    /** Which build {@link #sheetPage} belongs to; a new pick starts back on the first page. */
    private int sheetPageFor;
    private InventoryEditorLayout.Rect sheetRect;
    private InventoryEditorLayout.Rect pagerRect;
    /** The answers page's Edit button as last drawn; null when not on screen. */
    private InventoryEditorLayout.Rect editNoteRect;
    /** The server's word on the picked build's questions and whether this player may edit them. */
    private BuilderSubmitHintsRequests.Answer submitAnswer = BuilderSubmitHintsRequests.Answer.UNKNOWN;

    public void render(GuiGraphics g, Font font, InventoryEditorLayout layout,
                       EditorScreenTheme theme, BuilderProfilePacket.Entry entry, float yaw,
                       String note, boolean asCopy, EditorCreatorBuilds.Landed landed,
                       boolean going, boolean loading, int seq, int mouseX, int mouseY) {

        drawHeader(g, font, layout.header(), theme, entry, landed, going, mouseX, mouseY);
        drawSubmit(g, font, layout.icons(), entry, mouseX, mouseY);

        previewRect = layout.preview();
        TemplateArt art = entry == null ? null : EditorCreatorBuilds.artOf(entry);
        PreviewPane.draw(g, font, previewRect, art, entry == null ? "" : EditorCreatorBuilds.label(entry),
            yaw, theme, entry == null ? 0 : entry.relayId(), seq);
        versions.draw(g, font, previewRect, entry == null ? 0 : entry.relayId(), seq, mouseX, mouseY);
        // The review colour rings the picture rather than sitting in the sheet as a fourth word:
        // it is the one fact a reviewer scans for, and My Builds already teaches the colours.
        if (entry != null) {
            int border = BuilderReviewState.borderColourFor(entry.review());
            if (border != BuilderReviewState.BORDER_NONE) {
                g.renderOutline(previewRect.x(), previewRect.y(), previewRect.w(), previewRect.h(), border);
            }
        }

        InventoryEditorLayout.Rect s = layout.sheet();
        sheetRect = s;
        int relayId = entry == null ? 0 : entry.relayId();
        if (relayId != sheetPageFor) sheetPage = 0;
        sheetPageFor = relayId;
        sheetPageCount = entry == null ? 1 : 2;
        sheetPage = Math.min(sheetPage, sheetPageCount - 1);
        submitAnswer = entry == null ? BuilderSubmitHintsRequests.Answer.UNKNOWN
            : BuilderSubmitHintsRequests.peek(entry.relayId(), EditorCreatorBuilds.ownerOf(entry),
                BuilderProfileState.live());
        editNoteRect = null;
        // The pager takes the sheet's last line when there is a second page to turn to.
        pagerRect = entry != null ? new InventoryEditorLayout.Rect(s.x(), s.bottom() - LINE_H - 2, s.w(), LINE_H + 2) : null;
        InventoryEditorLayout.Rect body = pagerRect == null ? s
            : new InventoryEditorLayout.Rect(s.x(), s.y(), s.w(), Math.max(0, pagerRect.y() - s.y()));
        if (sheetPage == 1) {
            editNoteRect = SubmissionPage.draw(g, font, body, entry.note(), submitAnswer.hints(),
                submitAnswer.canEdit(), mouseX, mouseY);
        } else {
            int y = body.y();
            for (String[] line : lines(entry, seq)) {
                if (y + LINE_H > body.bottom()) break;
                g.drawString(font, line[0], body.x() + 2, y, MenuRowPainter.TEXT_HEADER, false);
                g.drawString(font, font.plainSubstrByWidth(line[1], body.w() - LABEL_W - 6),
                    body.x() + LABEL_W, y, 0xFFFFFFFF, false);
                y += LINE_H;
            }
        }
        if (pagerRect != null) {
            EditorPager.draw(g, font, pagerRect, sheetPage, sheetPageCount,
                EditorPager.hit(pagerRect, sheetPage, sheetPageCount, mouseX, mouseY));
        }

        // Why the toolbar is missing, said once rather than as eight disabled buttons — OR whatever
        // the last press of Load came back with. One or the other: the strip is a single line tall
        // at every GUI scale, and the note used to be drawn beneath the boilerplate, where it never
        // fit — every "Loaded into your editor." and "You already have a build by that name here."
        // was set and never seen. The boilerplate is the same every frame; the note is the news.
        InventoryEditorLayout.Rect notes = layout.settings();
        if (note != null && !note.isEmpty()) {
            // Wrapped, not clipped: these say what happened and why, and half a sentence
            // ("You already have a build by t") is worse than no sentence.
            int y2 = notes.y();
            for (FormattedCharSequence row : font.split(Component.literal(note), notes.w() - 4)) {
                // The first row always goes down, as the boilerplate always did — the strip can be
                // shorter than a line at the floor size and one clipped row beats nothing.
                if (y2 > notes.y() && y2 + LINE_H > notes.bottom()) break;
                g.drawString(font, row, notes.x() + 2, y2, 0xFFFFEEBB, false);
                y2 += LINE_H;
            }
        } else {
            g.drawString(font, font.plainSubstrByWidth(
                    EditorScreenLang.text(EditorScreenLang.CREATOR_READ_ONLY), notes.w() - 4),
                notes.x() + 2, notes.y(), EditorDetailPane.DIM_TEXT, false);
        }

        drawLoad(g, font, layout.test(), entry, landed, asCopy, loading, mouseX, mouseY);
    }

    /**
     * The header: what the build is called, and — once it is here — the way to go and stand in it.
     *
     * <p>The button appears where a template's own "you are here" line does, because it answers the
     * same question in the same place: the build is somewhere, and this is how you get to it.</p>
     */
    private void drawHeader(GuiGraphics g, Font font, InventoryEditorLayout.Rect h, EditorScreenTheme theme,
                            BuilderProfilePacket.Entry entry, EditorCreatorBuilds.Landed landed,
                            boolean going, int mouseX, int mouseY) {
        int ty = h.y() + (h.h() - font.lineHeight) / 2;
        String title = entry == null
            ? EditorScreenLang.text(EditorScreenLang.CREATOR_NOTHING_SELECTED)
            : EditorCreatorBuilds.label(entry);

        goHereRect = null;
        // Only a build the editor has a home for: a carriage group is authored in the Train Builder
        // and there is nowhere in here to send anybody.
        boolean canGo = landed != null
            && EditorTemplateJumpBridge.hasHome(landed.kind(), landed.subKind());
        int nameWidth = h.w() - 4;
        if (canGo) {
            // Under way: the same button, greyed and saying so. A walk is a teleport plus a
            // category restamp and can take a moment, and a button that looks pressable while it
            // is happening invites a second press that would restamp everything again.
            String label = EditorScreenLang.text(going
                ? EditorScreenLang.CREATOR_GOING : EditorScreenLang.GO_HERE);
            int w = font.width(label) + 8;
            InventoryEditorLayout.Rect rect =
                new InventoryEditorLayout.Rect(h.right() - w - 1, h.y() + 1, w, h.h() - 2);
            goHereRect = going ? null : rect;
            nameWidth = Math.max(0, rect.x() - h.x() - 6);
            boolean hot = !going && rect.contains(mouseX, mouseY);
            g.fill(rect.x(), rect.y(), rect.right(), rect.bottom(),
                going ? EditorDetailPane.DISABLED : hot ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
            g.drawString(font, label, rect.x() + 4, ty,
                going ? 0x80FFFFFF : hot ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
        }
        g.drawString(font, font.plainSubstrByWidth(title, nameWidth), h.x() + 2, ty,
            theme.panelText(), !theme.isLight());
    }

    /**
     * <b>Load into editor</b>, where a template has <b>Test the Carriage</b> — until it has been
     * loaded, after which the slot says so instead of offering it again.
     *
     * <p>Loading writes the build into this install's library, after which it is an ordinary
     * template: it appears in the roster, and the header's <b>Go here</b> walks the player to it. A
     * name already taken here turns the button into the copy, because that is the only answer left
     * that does not overwrite somebody's work.</p>
     *
     * <p>For a kind with sub-variants the slot is two buttons: the load, and to its right the
     * variant parent the build will land under — <b>User builds</b> until the reviewer picks another.
     * The parent is chosen here and not after the fact because after the fact is a second trip to
     * the roster per build, and a reviewer loads them by the dozen.</p>
     *
     * <p>While the press is out, both buttons are greyed and the load one says <b>Loading…</b> —
     * the same treatment as <b>Going now…</b>, for the same reason: a big build takes seconds to
     * fetch, install and stamp, and a button that still looks pressable during that invites a
     * second press that would fetch it all again.</p>
     *
     * <p>Once loaded, <b>Shift</b> turns the slot back into <b>Load as a copy</b>: the editor holds
     * one template per name, so a second copy — or another relay row wearing the same name — can
     * only come down under a free one, and the server's LOAD_AS_NEW is exactly that.</p>
     */
    private void drawLoad(GuiGraphics g, Font font, InventoryEditorLayout.Rect r,
                          BuilderProfilePacket.Entry entry, EditorCreatorBuilds.Landed landed,
                          boolean asCopy, boolean loading, int mouseX, int mouseY) {
        loadRect = null;
        parentRect = null;
        // Already here, and Shift is not down: not a button. Pressing it again would fetch the same
        // build and be told the name is taken — by the copy it just made. Shift is the way to ask
        // for that copy on purpose (the editor's usual "the other thing this control does"), and
        // the slot says so when the mouse is over it. A copy attempt that was itself refused
        // (asCopy) keeps the button up so the next free name can be tried; a Load in flight keeps
        // the greyed "Loading…" whether or not one copy is already down.
        if (landed != null && !loading && !asCopy && !Screen.hasShiftDown()) {
            String done = EditorScreenLang.text(r.contains(mouseX, mouseY)
                ? EditorScreenLang.CREATOR_LOADED_SHIFT_HINT : EditorScreenLang.CREATOR_LOADED);
            g.drawString(font, font.plainSubstrByWidth(done, r.w() - 4),
                r.x() + (r.w() - font.width(done)) / 2, r.y() + (r.h() - font.lineHeight) / 2 + 1,
                LOADED_TEXT, false);
            return;
        }
        drawLoadButtons(g, font, r, entry, asCopy || landed != null, loading, mouseX, mouseY);
    }

    /** The load button and, for a kind with sub-variants, the parent picker beside it. */
    private void drawLoadButtons(GuiGraphics g, Font font, InventoryEditorLayout.Rect r,
                                 BuilderProfilePacket.Entry entry, boolean asCopy, boolean loading,
                                 int mouseX, int mouseY) {
        boolean enabled = entry != null && !loading;
        boolean underParent = entry != null && CreatorLoadParent.supports(entry.kind());
        InventoryEditorLayout.Rect load = r;
        if (underParent) {
            int parentW = (int) (r.w() * PARENT_SHARE);
            load = new InventoryEditorLayout.Rect(r.x(), r.y(), r.w() - parentW - BUTTON_GAP, r.h());
            parentRect = new InventoryEditorLayout.Rect(r.right() - parentW, r.y(), parentW, r.h());
            PlotCategory category = EditorCreatorBuilds.categoryOf(entry.kind());
            String parent = "\u25B8 " + CreatorLoadParent.labelFor(category, EditorRosterClient.index());
            button(g, font, parentRect, parent, enabled, mouseX, mouseY);
            if (!enabled) parentRect = null;
        }
        loadRect = enabled ? load : null;
        String label = EditorScreenLang.text(loading ? EditorScreenLang.CREATOR_LOAD_PENDING
            : asCopy ? EditorScreenLang.CREATOR_LOAD_COPY
            : underParent ? EditorScreenLang.CREATOR_LOAD_SUB_VARIANT : EditorScreenLang.CREATOR_LOAD);
        button(g, font, load, label, enabled, mouseX, mouseY);
    }

    /** One slot-height button, centred label, greyed when it cannot be pressed. */
    private static void button(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, String label,
                               boolean enabled, int mouseX, int mouseY) {
        boolean hot = enabled && r.contains(mouseX, mouseY);
        g.fill(r.x(), r.y(), r.right(), r.bottom(), !enabled ? EditorDetailPane.DISABLED
            : hot ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        String shown = font.plainSubstrByWidth(label, r.w() - 6);
        g.drawString(font, shown, r.x() + (r.w() - font.width(shown)) / 2,
            r.y() + (r.h() - font.lineHeight) / 2 + 1,
            !enabled ? 0x80FFFFFF : hot ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
    }

    /**
     * <b>Submit to the train</b> / <b>Withdraw</b>, in the row a template's toolbar occupies — which
     * is the one control on a relay build that changes it rather than reads it.
     *
     * <p>Offered on your OWN builds only, and only for a kind that can be offered for review. The
     * decision to put a build in front of the operator is its author's, and the relay agrees: the
     * action is authorised by the owner secret this world holds, so somebody else's build would be
     * refused there too. Disabled rather than hidden, so the button is where a reviewer expects it
     * and says why it cannot be pressed.</p>
     */
    private void drawSubmit(GuiGraphics g, Font font, InventoryEditorLayout.Rect r,
                            BuilderProfilePacket.Entry entry, int mouseX, int mouseY) {
        submitRect = null;
        if (entry == null) return;
        boolean mine = isMine(entry);
        boolean enabled = mine && BuilderRelayKinds.canSubmitForReview(entry.kind());
        submitRect = enabled ? r : null;
        boolean hot = enabled && r.contains(mouseX, mouseY);
        g.fill(r.x(), r.y(), r.right(), r.bottom(), !enabled ? EditorDetailPane.DISABLED
            : hot ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        String label = EditorScreenLang.text(!mine ? EditorScreenLang.CREATOR_NOT_YOURS
            : entry.published() ? EditorScreenLang.CREATOR_WITHDRAW : EditorScreenLang.CREATOR_SUBMIT);
        g.drawString(font, font.plainSubstrByWidth(label, r.w() - 6),
            r.x() + (r.w() - font.width(label)) / 2, r.y() + (r.h() - font.lineHeight) / 2 + 1,
            !enabled ? 0x80FFFFFF : hot ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
    }

    /**
     * Whether this build is the viewer's own.
     *
     * <p>By uuid rather than by which listing it came from: the pooled grid holds everybody's builds
     * including this player's, and those are exactly the ones they may still submit or withdraw.</p>
     */
    private static boolean isMine(BuilderProfilePacket.Entry entry) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || entry.ownerUuid().isEmpty()) return false;
        return mc.player.getUUID().toString().equals(entry.ownerUuid());
    }

    /** Open the picked build's answers for editing — the answers page's Edit button. */
    public void openNoteEditor(BuilderProfilePacket.Entry entry) {
        if (entry == null || !submitAnswer.canEdit()) return;
        BuilderSubmitNoteScreen.openEditor(entry.relayId(), EditorCreatorBuilds.ownerOf(entry),
            BuilderProfileState.live(), Component.literal(EditorCreatorBuilds.label(entry)),
            submitAnswer.hints(), entry.note());
    }

    /** Turn the sheet's page — the pager's arrows or the wheel. False when there is nowhere to turn. */
    public boolean turnSheet(int dir) {
        int next = Math.max(0, Math.min(sheetPageCount - 1, sheetPage + dir));
        boolean moved = next != sheetPage;
        sheetPage = next;
        return moved;
    }

    /** True when the point is over the sheet — where the wheel turns its pages. */
    public boolean overSheet(double mx, double my) {
        return sheetRect != null && sheetPageCount > 1 && sheetRect.contains(mx, my);
    }

    /** What a click at this point means. Reads back the geometry of the last frame. */
    public HitKind hitTest(double mx, double my) {
        switch (versions.hit(mx, my)) {
            case OLDER -> { return HitKind.OLDER; }
            case NEWER -> { return HitKind.NEWER; }
            case NONE -> { }
        }
        if (goHereRect != null && goHereRect.contains(mx, my)) return HitKind.GO_HERE;
        if (editNoteRect != null && sheetPage == 1 && editNoteRect.contains(mx, my)) return HitKind.EDIT_NOTE;
        switch (EditorPager.hit(pagerRect, sheetPage, sheetPageCount, mx, my)) {
            case PREV -> { return HitKind.PAGE_PREV; }
            case NEXT -> { return HitKind.PAGE_NEXT; }
            case NONE -> { }
        }
        if (submitRect != null && submitRect.contains(mx, my)) return HitKind.SUBMIT;
        if (loadRect != null && loadRect.contains(mx, my)) return HitKind.LOAD;
        if (parentRect != null && parentRect.contains(mx, my)) return HitKind.PARENT;
        if (previewRect != null && previewRect.contains(mx, my)) return HitKind.PREVIEW;
        return HitKind.NONE;
    }

    /** The sheet: who made it, what it is, and what has happened to it. */
    static List<String[]> lines(BuilderProfilePacket.Entry entry) {
        return lines(entry, 0);
    }

    static List<String[]> lines(BuilderProfilePacket.Entry entry, int seq) {
        List<String[]> out = new ArrayList<>();
        if (entry == null) return out;
        out.add(new String[] {EditorScreenLang.text(EditorScreenLang.CREATOR_BY),
            entry.ownerName().isEmpty() ? EditorCreatorBuilds.viewedName() : entry.ownerName()});
        out.add(new String[] {EditorScreenLang.text(EditorScreenLang.CREATOR_KIND),
            EditorScreenLang.text(EditorCreatorBuilds.kindKey(entry.kind()))});
        if (!entry.stage().isEmpty()) {
            out.add(new String[] {EditorScreenLang.text(EditorScreenLang.SHEET_STAGE), entry.stage()});
        }
        // The numbers come from the blocks the relay sent for the picture, so they appear with it
        // rather than being asked for separately.
        TemplateSummary summary = RelayBuildPreviews.summary(entry.relayId(), seq);
        if (summary != null) {
            out.add(new String[] {EditorScreenLang.text(EditorScreenLang.SHEET_SIZE),
                summary.declaredSize().getX() + " × " + summary.declaredSize().getY()
                    + " × " + summary.declaredSize().getZ()});
            out.add(new String[] {EditorScreenLang.text(EditorScreenLang.SHEET_BLOCKS),
                Integer.toString(summary.blocks())});
        }
        out.add(new String[] {EditorScreenLang.text(EditorScreenLang.CREATOR_CHANGES),
            Integer.toString(entry.changes())});
        out.add(new String[] {EditorScreenLang.text(EditorScreenLang.CREATOR_STATUS),
            EditorScreenLang.text(EditorCreatorBuilds.reviewKey(entry.review()))});
        return out;
    }
}
