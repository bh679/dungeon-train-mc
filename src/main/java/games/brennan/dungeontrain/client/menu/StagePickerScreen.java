package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PartAssignmentEditPacket;
import games.brennan.dungeontrain.train.CarriagePartKind;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Stage / Custom" picker — the popup behind the {@code Stage ▾} / {@code ◆?} affordance at every
 * gate-edit site. Lists {@code Custom} (detach to a hand-set inline gate, exactly as today) plus every
 * existing {@link ClientStages.Info Stage}; clicking one links the target to it (or detaches) and
 * closes. The row matching the current link is highlighted.
 *
 * <p>Two dispatch modes share the same list:</p>
 * <ul>
 *   <li><b>Command mode</b> (carriages / contents / tracks template rows + the keyboard editor menu):
 *       each row is a {@link CommandMenuEntry.Run} dispatching
 *       {@link EditorPlotTeleport#stageApplyCommandFor}.</li>
 *   <li><b>Parts mode</b> ({@link #forParts}): each row is a {@link CommandMenuEntry.ClientAction}
 *       that sends a {@link PartAssignmentEditPacket.Op#SET_STAGE} edit for the (variant, kind, entry)
 *       and closes the menu — the parts menu has no slash-command apply path.</li>
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

    // Group-member mode (Sub-Variants companion).
    private final boolean groupMemberMode;
    private final String groupParentId;
    private final String groupMemberId;

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

    private StagePickerScreen(String parentId, String memberId, String currentStageId) {
        this.groupMemberMode = true;
        this.groupParentId = parentId == null ? "" : parentId;
        this.groupMemberId = memberId == null ? "" : memberId;
        this.currentStageId = currentStageId == null ? "" : currentStageId;
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
     * Picker for a contents <em>group member</em> (Sub-Variants companion row) — links it via the
     * {@code stage apply contents-group <parent> <member> <token>} command on pick.
     */
    public static StagePickerScreen forGroupMember(String parentId, String memberId, String currentStageId) {
        return new StagePickerScreen(parentId, memberId, currentStageId);
    }

    @Override
    public String title() {
        return "Select Stage";
    }

    @Override
    public List<CommandMenuEntry> entries() {
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

    /** Build one picker row for {@code token} ({@code "custom"} = detach), in the active dispatch mode. */
    private CommandMenuEntry pickEntry(String label, String token, boolean highlighted) {
        if (partsMode) {
            String stageId = "custom".equals(token) ? "" : token;
            return new CommandMenuEntry.ClientAction(label, () -> {
                DungeonTrainNet.sendToServer(new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.SET_STAGE, partsVariantId, partsKind, partsName, 0, stageId));
                CommandMenuState.close();
            });
        }
        if (groupMemberMode) {
            String cmd = EditorPlotTeleport.groupMemberStageApplyCommandFor(groupParentId, groupMemberId, token);
            return new CommandMenuEntry.Run(label, cmd, highlighted);
        }
        String cmd = EditorPlotTeleport.stageApplyCommandFor(category, modelId, modelName, token);
        return new CommandMenuEntry.Run(label, cmd, highlighted);
    }
}
