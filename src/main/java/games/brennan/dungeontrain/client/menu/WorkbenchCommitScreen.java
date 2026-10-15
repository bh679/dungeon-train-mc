package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.builder.relay.BuilderRelaySubVariant;
import games.brennan.dungeontrain.builder.relay.WorkbenchCommit;
import games.brennan.dungeontrain.client.menu.editorscreen.CreatorLoadParent;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorRosterClient;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorScreenLang;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Commit as…" for a Workbench build: pick the kind, its sub kind where the kind has one, the variant
 * parent where the kind has those, whether to write over a taken name — then type the name and go.
 *
 * <p>The last row is a {@link CommandMenuEntry.TypeArg} that runs
 * {@code /dungeontrain editor workbench commit [replace] <id> <kind> <sub|-> <name> [<parent>]}, so
 * the X menu and the slash command are one path with one set of refusals. The choices above it are
 * client state on this screen alone; nothing is sent until the name is confirmed.</p>
 *
 * <p>Defaults are the relay's own answers — its kind and sub kind, and its build name — carried in
 * the roster row's label as {@code <buildName>  ·  <kind>[/<sub>]} ({@code EditorTypeMenus.workbenchRows}).</p>
 */
public final class WorkbenchCommitScreen implements MenuScreen {

    /** The label separator {@code EditorTypeMenus.workbenchRows} writes — the one place it is parsed. */
    public static final String LABEL_SEPARATOR = "  ·  ";

    private final String stagedId;
    private final String defaultName;
    private BuilderPhotoPaths.Kind kind;
    private String subKind;
    private boolean replace;

    /** @param rowLabel the roster row's display label, or null/blank to start from the staged id alone */
    public WorkbenchCommitScreen(String stagedId, String rowLabel) {
        this.stagedId = stagedId == null ? "" : stagedId.toLowerCase(Locale.ROOT);
        Parsed parsed = parseLabel(rowLabel, this.stagedId);
        this.defaultName = parsed.name();
        this.kind = parsed.kind();
        this.subKind = parsed.subKind();
    }

    record Parsed(String name, BuilderPhotoPaths.Kind kind, String subKind) {}

    /** {@code <buildName>  ·  <kind>[/<sub>]} → its parts; anything else → the staged id and the first kind. */
    static Parsed parseLabel(String label, String stagedId) {
        BuilderPhotoPaths.Kind first = kinds().get(0);
        if (label == null || !label.contains(LABEL_SEPARATOR)) return new Parsed(stagedId, first, "");
        int at = label.lastIndexOf(LABEL_SEPARATOR);
        String name = label.substring(0, at).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
        String kindPart = label.substring(at + LABEL_SEPARATOR.length()).trim();
        String sub = "";
        int slash = kindPart.indexOf('/');
        if (slash >= 0) {
            sub = kindPart.substring(slash + 1);
            kindPart = kindPart.substring(0, slash);
        }
        BuilderPhotoPaths.Kind kind = BuilderRelayKinds.kindOf(kindPart);
        if (kind == null || !WorkbenchCommit.commitsAs(kind)) kind = first;
        if (!WorkbenchCommit.subKindValid(kind, sub)) sub = firstSubKind(kind);
        return new Parsed(name.isEmpty() ? stagedId : name, kind, sub);
    }

    /** The kinds a staged build may become, in the order the cycle row offers them. */
    static List<BuilderPhotoPaths.Kind> kinds() {
        List<BuilderPhotoPaths.Kind> out = new ArrayList<>();
        for (BuilderPhotoPaths.Kind k : BuilderPhotoPaths.Kind.values()) {
            if (WorkbenchCommit.commitsAs(k)) out.add(k);
        }
        return out;
    }

    /** The sub kinds {@code kind} offers, or empty for a kind without any. */
    static List<String> subKinds(BuilderPhotoPaths.Kind kind) {
        List<String> out = new ArrayList<>();
        switch (kind) {
            case PART -> { for (CarriagePartKind p : CarriagePartKind.values()) out.add(p.id()); }
            case TRACK -> { for (TrackKind t : TrackKind.values()) if (t != TrackKind.PORTAL_ROOM) out.add(t.id()); }
            default -> { }
        }
        return out;
    }

    static String firstSubKind(BuilderPhotoPaths.Kind kind) {
        List<String> subs = subKinds(kind);
        return subs.isEmpty() ? "" : subs.get(0);
    }

    /** The editor category {@code kind} commits into — where its parent picker looks. */
    static PlotCategory categoryOf(BuilderPhotoPaths.Kind kind) {
        return PlotCategory.fromId(BuilderRelayKinds.categoryIdFor(kind, "")).orElse(null);
    }

    @Override
    public String title() {
        return EditorScreenLang.text(EditorScreenLang.COMMIT_TITLE, stagedId);
    }

    @Override
    public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>();
        out.add(new CommandMenuEntry.ClientAction(
            EditorScreenLang.text(EditorScreenLang.COMMIT_KIND, kind.id()), this::nextKind, false));
        if (!subKinds(kind).isEmpty()) {
            out.add(new CommandMenuEntry.ClientAction(
                EditorScreenLang.text(EditorScreenLang.COMMIT_SUB_KIND, subKind), this::nextSubKind, false));
        }
        PlotCategory category = categoryOf(kind);
        if (category != null && (BuilderRelaySubVariant.supports(kind) || CreatorLoadParent.offersWholeRoom(category))) {
            String parent = CreatorLoadParent.labelFor(category, EditorRosterClient.index());
            out.add(new CommandMenuEntry.DrillIn(EditorScreenLang.text(EditorScreenLang.COMMIT_PARENT, parent),
                new CreatorParentPickerScreen(category, () -> { }), false));
        }
        out.add(new CommandMenuEntry.ClientAction(
            EditorScreenLang.text(replace ? EditorScreenLang.COMMIT_REPLACE_ON : EditorScreenLang.COMMIT_REPLACE_OFF),
            () -> replace = !replace, replace));
        out.add(new CommandMenuEntry.TypeArg(EditorScreenLang.text(EditorScreenLang.COMMIT_GO), "name",
            commandPrefix(), commandSuffix(category), defaultName));
        out.add(new CommandMenuEntry.Back(EditorScreenLang.text(EditorScreenLang.MOVE_BACK)));
        return out;
    }

    private void nextKind() {
        List<BuilderPhotoPaths.Kind> all = kinds();
        kind = all.get((all.indexOf(kind) + 1) % all.size());
        subKind = firstSubKind(kind);
    }

    private void nextSubKind() {
        List<String> subs = subKinds(kind);
        if (subs.isEmpty()) return;
        subKind = subs.get((subs.indexOf(subKind) + 1) % subs.size());
    }

    /** Everything before the typed name: {@code dungeontrain editor workbench commit [replace] <id> <kind> <sub|-> }. */
    String commandPrefix() {
        return "dungeontrain editor workbench commit " + (replace ? "replace " : "") + stagedId + " "
            + BuilderRelayKinds.idOf(kind) + " " + (subKind.isEmpty() ? "-" : subKind) + " ";
    }

    /** The parent after the name, when one is chosen; nothing otherwise. */
    String commandSuffix(PlotCategory category) {
        if (category == null) return "";
        String parent = CreatorLoadParent.parentFor(category);
        return parent == null || parent.isEmpty() ? "" : " " + parent;
    }
}
