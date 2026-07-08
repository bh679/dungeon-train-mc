package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PartAssignmentEditPacket;
import games.brennan.dungeontrain.train.CarriagePartKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The "Stage / Custom" picker — the popup behind the {@code Stage ▾} / {@code ◆?} affordance at every
 * gate-edit site. Lists {@code Custom} (detach to a hand-set inline gate, exactly as today) plus every
 * existing {@link ClientStages.Info Stage}; clicking one links the target to it (or detaches) and
 * closes. The row matching the current link is highlighted.
 *
 * <p>Three dispatch modes share the same list:</p>
 * <ul>
 *   <li><b>Command mode</b> (carriages / contents / tracks template rows + the keyboard editor menu):
 *       <b>single-select</b> — each row is a {@link CommandMenuEntry.Run} dispatching
 *       {@link EditorPlotTeleport#stageApplyCommandFor} and closing.</li>
 *   <li><b>Parts mode</b> ({@link #forParts}): <b>single-select</b> — each row is a
 *       {@link CommandMenuEntry.ClientAction} that sends a
 *       {@link PartAssignmentEditPacket.Op#SET_STAGE} edit for the (variant, kind, entry) and closes
 *       the menu — the parts menu has no slash-command apply path.</li>
 *   <li><b>Group-member mode</b> ({@link #forGroupMember}, the Sub-Variants companion):
 *       <b>multi-select</b> — a sub-variant may be linked to more than one Stage (the union of their
 *       gates applies), so each Stage row is a stay-open <em>toggle</em> ({@code [x]}/{@code [ ]})
 *       that flips the link server-side and keeps the picker open. A "Custom (clear all)" row detaches
 *       every link; "&lt; Done" closes.</li>
 * </ul>
 */
public final class StagePickerScreen implements MenuScreen {

    private final String currentStageId;

    // Command mode (templates).
    private final String category;
    private final String modelId;
    private final String modelName;

    // Parts mode.
    private final boolean partsMode;
    private final String partsVariantId;
    private final CarriagePartKind partsKind;
    private final String partsName;

    // Group-member mode (Sub-Variants companion). Multi-select: the working set is mutated in place
    // as the player toggles rows, so entries() (rebuilt each tick) reflects toggles immediately.
    private final boolean groupMemberMode;
    private final String groupParentId;
    private final String groupMemberId;
    private final LinkedHashSet<String> groupSelected = new LinkedHashSet<>();

    public StagePickerScreen(String category, String modelId, String modelName, String currentStageId) {
        this.category = category == null ? "" : category;
        this.modelId = modelId == null ? "" : modelId;
        this.modelName = modelName == null ? "" : modelName;
        this.currentStageId = currentStageId == null ? "" : currentStageId;
        this.partsMode = false;
        this.partsVariantId = "";
        this.partsKind = null;
        this.partsName = "";
        this.groupMemberMode = false;
        this.groupParentId = "";
        this.groupMemberId = "";
    }

    private StagePickerScreen(String variantId, CarriagePartKind kind, String name, String currentStageId) {
        this.partsMode = true;
        this.partsVariantId = variantId == null ? "" : variantId;
        this.partsKind = kind;
        this.partsName = name == null ? "" : name;
        this.currentStageId = currentStageId == null ? "" : currentStageId;
        this.category = "";
        this.modelId = "";
        this.modelName = "";
        this.groupMemberMode = false;
        this.groupParentId = "";
        this.groupMemberId = "";
    }

    private StagePickerScreen(String parentId, String memberId, Collection<String> currentStageIds) {
        this.groupMemberMode = true;
        this.groupParentId = parentId == null ? "" : parentId;
        this.groupMemberId = memberId == null ? "" : memberId;
        if (currentStageIds != null) {
            for (String s : currentStageIds) {
                if (s == null) continue;
                String n = s.trim().toLowerCase(Locale.ROOT);
                if (!n.isEmpty()) groupSelected.add(n);
            }
        }
        this.currentStageId = "";
        this.partsMode = false;
        this.partsVariantId = "";
        this.partsKind = null;
        this.partsName = "";
        this.category = "";
        this.modelId = "";
        this.modelName = "";
    }

    /** Picker for a carriage-part entry — links it via a {@link PartAssignmentEditPacket} on pick. */
    public static StagePickerScreen forParts(String variantId, CarriagePartKind kind, String name, String currentStageId) {
        return new StagePickerScreen(variantId, kind, name, currentStageId);
    }

    /**
     * Multi-select picker for a contents <em>group member</em> (Sub-Variants companion row) — each
     * Stage row toggles the link via the {@code stage apply contents-group <parent> <member> <token>}
     * command; {@code currentStageIds} seeds which rows start ticked.
     */
    public static StagePickerScreen forGroupMember(String parentId, String memberId, Collection<String> currentStageIds) {
        return new StagePickerScreen(parentId, memberId, currentStageIds);
    }

    @Override
    public String title() {
        return groupMemberMode ? "Select Stages" : "Select Stage";
    }

    @Override
    public List<CommandMenuEntry> entries() {
        return groupMemberMode ? groupEntries() : singleEntries();
    }

    /** Single-select list (command + parts modes): Custom + one row per Stage, click links + closes. */
    private List<CommandMenuEntry> singleEntries() {
        List<CommandMenuEntry> out = new ArrayList<>();
        boolean linked = !currentStageId.isEmpty();

        out.add(pickEntry("Custom (set by hand)", "custom", !linked));
        for (ClientStages.Info s : ClientStages.all()) {
            String label = s.name() + "  [" + ClientStages.gateSummary(s) + "]";
            out.add(pickEntry(label, s.id(), s.id().equals(currentStageId)));
        }
        if (ClientStages.isEmpty()) {
            out.add(new CommandMenuEntry.Label("No stages yet — add one in the Stages window."));
        }
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }

    /**
     * Multi-select list (group-member mode): a "clear all" row plus a stay-open {@code [x]}/{@code [ ]}
     * toggle per Stage. Each toggle flips the working set locally (so the checkbox updates on the next
     * tick's rebuild) and dispatches the server-side toggle command. "&lt; Done" closes.
     */
    private List<CommandMenuEntry> groupEntries() {
        List<CommandMenuEntry> out = new ArrayList<>();

        out.add(new CommandMenuEntry.ClientAction(
            checkbox(groupSelected.isEmpty()) + "Custom (no Stage — clear all)",
            () -> {
                groupSelected.clear();
                CommandRunner.run(EditorPlotTeleport.groupMemberStageApplyCommandFor(
                    groupParentId, groupMemberId, "custom"));
            }));

        for (ClientStages.Info s : ClientStages.all()) {
            String stageId = s.id();
            boolean on = groupSelected.contains(stageId);
            String label = checkbox(on) + s.name() + "  [" + ClientStages.gateSummary(s) + "]";
            out.add(new CommandMenuEntry.ClientAction(label, () -> {
                if (!groupSelected.remove(stageId)) groupSelected.add(stageId);
                CommandRunner.run(EditorPlotTeleport.groupMemberStageApplyCommandFor(
                    groupParentId, groupMemberId, stageId));
            }));
        }
        if (ClientStages.isEmpty()) {
            out.add(new CommandMenuEntry.Label("No stages yet — add one in the Stages window."));
        }
        out.add(new CommandMenuEntry.Back("< Done"));
        return out;
    }

    private static String checkbox(boolean on) {
        return on ? "[x] " : "[ ] ";
    }

    /** Build one single-select picker row for {@code token} ({@code "custom"} = detach). */
    private CommandMenuEntry pickEntry(String label, String token, boolean highlighted) {
        if (partsMode) {
            String stageId = "custom".equals(token) ? "" : token;
            return new CommandMenuEntry.ClientAction(label, () -> {
                DungeonTrainNet.sendToServer(new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.SET_STAGE, partsVariantId, partsKind, partsName, 0, stageId));
                CommandMenuState.close();
            });
        }
        String cmd = EditorPlotTeleport.stageApplyCommandFor(category, modelId, modelName, token);
        return new CommandMenuEntry.Run(label, cmd, highlighted);
    }
}
