package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorTemplateLists;
import games.brennan.dungeontrain.template.Template;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drilldown reached from {@link EnterCategoryMenuScreen} when the player
 * picks the category they're already inside. Lists every template in the
 * category as a {@link CommandMenuEntry.Run} that dispatches the existing
 * per-template enter slash command — so a single click teleports straight
 * to that template's plot without the {@code /dt editor &lt;cat&gt;} clear-and-
 * restamp cycle.
 *
 * <p>The row matching the player's current model (per
 * {@link EditorStatusHudOverlay#modelId()}) is rendered with the highlighted
 * tint so the player can see at a glance where they are in the list.</p>
 *
 * <p>Sources used per category — carriages and contents come from
 * {@link EditorTemplateLists}, which the Train Builder's New screen also reads so the two can't
 * disagree about what exists:
 * <ul>
 *   <li>{@code carriages} — {@link EditorTemplateLists#carriages()},
 *       dispatched via {@code /dt editor enter &lt;variant&gt;}.</li>
 *   <li>{@code contents} — {@link EditorTemplateLists#contents()}, which is
 *       already minus group members (sub-variants), dispatched via
 *       {@code /dt editor contents enter &lt;contents&gt;}. A group parent
 *       drills into {@link ContentsSubVariantScreen} listing its members
 *       instead of dispatching directly.</li>
 *   <li>{@code tracks} — {@link EditorCategory#models()} for TRACKS, with
 *       per-{@link Template} subtype routing because each track-side
 *       enter command takes a different shape (track / pillar / tunnel).</li>
 * </ul>
 */
public final class CategoryTemplatesScreen implements MenuScreen {

    private final String categoryId;

    public CategoryTemplatesScreen(String categoryId) {
        this.categoryId = categoryId == null ? "" : categoryId.toLowerCase(Locale.ROOT);
    }

    @Override
    public String title() {
        return "Templates";
    }

    @Override
    public List<CommandMenuEntry> entries() {
        String activeId = EditorStatusHudOverlay.modelId();
        List<CommandMenuEntry> out = new ArrayList<>();
        switch (categoryId) {
            case "carriages" -> {
                for (String id : EditorTemplateLists.carriages()) {
                    out.add(new CommandMenuEntry.Run(
                        id,
                        "dungeontrain editor enter " + id,
                        id.equals(activeId)));
                }
            }
            case "contents" -> {
                // Top-level parents/leaves only — group members (sub-variants) are reached by
                // drilling into their parent. That filtering rule lives in EditorTemplateLists so
                // the Train Builder's picker applies the same one.
                for (String id : EditorTemplateLists.contents()) {
                    if (EditorTemplateLists.isContentsGroup(id)) {
                        // Group parent (e.g. maze) — drill into its sub-variants.
                        out.add(new CommandMenuEntry.DrillIn(
                            id,
                            new ContentsSubVariantScreen(id),
                            id.equals(activeId)));
                    } else {
                        out.add(new CommandMenuEntry.Run(
                            id,
                            "dungeontrain editor contents enter " + id,
                            id.equals(activeId)));
                    }
                }
            }
            case "tracks" -> {
                for (Template model : EditorCategory.TRACKS.models()) {
                    String command = trackEnterCommandFor(model);
                    if (command == null) continue;
                    out.add(new CommandMenuEntry.Run(
                        model.displayName(),
                        command,
                        model.id().equals(activeId)));
                }
            }
            default -> {
                // Unknown category — nothing to list, just show Back.
            }
        }
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }

    /**
     * Map a track-side {@link Template} to the slash command that
     * teleports the player to that specific plot. Each track model type has
     * its own command shape — see EditorCommand.build for the route table.
     */
    static String trackEnterCommandFor(Template model) {
        if (model instanceof Template.Track) {
            return "dungeontrain editor track enter";
        }
        if (model instanceof Template.Pillar pm) {
            return "dungeontrain editor pillar enter " + pm.section().id();
        }
        if (model instanceof Template.Adjunct am) {
            return "dungeontrain editor pillar enter " + am.adjunct().id();
        }
        if (model instanceof Template.Tunnel tm) {
            return "dungeontrain editor enter tunnel_"
                + tm.variant().name().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** Visible-for-test accessor — the category id this screen targets. */
    public String categoryId() {
        return categoryId;
    }
}
