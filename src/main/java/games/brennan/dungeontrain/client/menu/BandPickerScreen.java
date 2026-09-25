package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.plot.BandGroupToggle;
import games.brennan.dungeontrain.worldgen.BandGroup;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntFunction;

/**
 * The band picker — the popup behind the "▸" cell at the end of a gated type-menu row. Lists every
 * {@link TrainPhase band} by its full name under its {@link BandGroup} header, with All / Invert.
 *
 * <p>Same stay-open multi-select shape as {@link StagePickerScreen}'s group-member mode: a local
 * working mask updates the checkboxes on the next tick's rebuild, and every click sends the whole
 * mask as one {@code … phase <id> mask <n>} command (built by {@code maskCommand}, so the one screen
 * serves plain templates, Sub-Variants members, portal-room members and Stages alike). A click that
 * would leave no band selected is refused — the gate reads an empty set as "every band".</p>
 */
public final class BandPickerScreen implements MenuScreen {

    private final String targetName;
    private final IntFunction<String> maskCommand;
    private int mask;

    /**
     * @param targetName  the template / stage shown in the title
     * @param currentMask its band mask as last synced ({@link TrainPhase#bit()} per band)
     * @param maskCommand builds the command that sets the target's bands to a mask
     */
    public BandPickerScreen(String targetName, int currentMask, IntFunction<String> maskCommand) {
        this.targetName = targetName == null ? "" : targetName;
        this.maskCommand = maskCommand;
        int m = currentMask & TrainPhase.ALL_MASK;
        this.mask = m == 0 ? TrainPhase.ALL_MASK : m;
    }

    @Override
    public String title() {
        return MenuLang.t("band_picker.title", targetName);
    }

    @Override
    public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>();
        out.add(new CommandMenuEntry.Split(
            new CommandMenuEntry.ClientAction(MenuLang.t("band_picker.all"), () -> apply(OptionalInt.of(TrainPhase.ALL_MASK))),
            new CommandMenuEntry.ClientAction(MenuLang.t("band_picker.invert"), () -> apply(BandGroupToggle.invert(mask))),
            0.5));
        for (BandGroup group : BandGroup.values()) {
            String header = glyph(group.state(mask)) + " " + MenuLang.t("band_group." + group.token());
            out.add(new CommandMenuEntry.ClientAction(header,
                () -> apply(BandGroupToggle.clickGroup(mask, group, false)), true));
            for (TrainPhase phase : group.members()) {
                boolean on = (mask & phase.bit()) != 0;
                String label = "    " + (on ? "[x] " : "[ ] ") + MenuLang.named("phase", phase.token(), phase.displayName());
                out.add(new CommandMenuEntry.ClientAction(label, () -> apply(BandGroupToggle.toggleBand(mask, phase))));
            }
        }
        out.add(new CommandMenuEntry.Back(MenuLang.t("common.done")));
        return out;
    }

    /** Adopt {@code next} and send it, or explain why a click that empties the set did nothing. */
    private void apply(OptionalInt next) {
        if (next.isEmpty()) {
            notifyNeedOneBand();
            return;
        }
        if (next.getAsInt() == mask) return;
        mask = next.getAsInt();
        CommandRunner.run(maskCommand.apply(mask));
    }

    /** Tri-state glyph for a group: {@code ●} all on, {@code ◐} some, {@code ○} none. */
    public static String glyph(BandGroup.State state) {
        return switch (state) {
            case ALL -> "●";
            case SOME -> "◐";
            case NONE -> "○";
        };
    }

    /** Action-bar note shown when a band toggle would leave a template with no bands. */
    public static void notifyNeedOneBand() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(MenuLang.t("bands.need_one")), true);
        }
    }
}
