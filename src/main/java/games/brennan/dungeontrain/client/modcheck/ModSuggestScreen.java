package games.brennan.dungeontrain.client.modcheck;

import games.brennan.dungeontrain.cheat.ModSuggestClient;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Tell us about one unsupported mod, from the {@link UnsupportedModsScreen}. Two stages:
 * <ol>
 *   <li><b>Pick</b> — nothing is selected yet. Under an instruction heading, the three choices sit in
 *       columns, each button with its one-line meaning underneath: <b>Whitelist</b> (doesn't impact
 *       gameplay unfairly), <b>Modpack</b> (should be in the modpack), <b>Cheat</b> (gives unfair
 *       advantages). No comment box until one is picked.</li>
 *   <li><b>Tell us why</b> — the choice stays as a button row on top (so it can be changed), then the
 *       comment box; the comment is required, so Submit stays disabled until something is written.</li>
 * </ol>
 *
 * <p>Whitelist starts a suggestion, or backs one somebody already made; Modpack and Cheat land in the
 * relay's queue under those categories. Sending runs off-thread ({@link ModSuggestProof}); the answer
 * comes back on the render thread. A result the player can't fix by rewording returns to the list;
 * anything else stays here with the reason shown, so the comment isn't lost.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ModSuggestScreen extends Screen {

    /** The relay's comment cap ({@code mod-votes-store.MAX_COMMENT_CHARS}). */
    static final int MAX_COMMENT = 280;

    private static final int FIELD_W = 300;
    private static final int BOX_H = 70;
    private static final int BUTTON_H = 20;
    private static final int GAP = 6;

    // Pick stage: heading, then per column a button with its meaning wrapped underneath.
    private static final int PICK_HEADING_Y = 32;
    private static final int PICK_BUTTON_Y = PICK_HEADING_Y + 16;
    private static final int PICK_DESC_Y = PICK_BUTTON_Y + BUTTON_H + 5;
    private static final int PICK_DESC_LINES = 4;

    // Vertical layout, from the top of the content block.
    private static final int KIND_Y = 28;
    private static final int DESC_Y = KIND_Y + BUTTON_H + 5;
    private static final int WHY_Y = DESC_Y + 16;
    private static final int BOX_Y = WHY_Y + 12;

    /** Selected choice tint per kind: green allow, blue modpack, red cheat. Unselected stays plain. */
    private static final float[][] KIND_TINT = {
        {0.45F, 1.10F, 0.45F},
        {0.45F, 0.60F, 1.25F},
        {1.15F, 0.35F, 0.35F},
    };
    private static final int[] KIND_COLOUR = {0xFF8FE08F, 0xFF8FB0FF, 0xFFE08080};

    private final UnsupportedModsScreen parent;
    private final UnsupportedModsScreen.UnsupportedMod mod;
    private final Consumer<ModSuggestClient.Result> onResult;

    /** Null until the player picks one — the pick stage shows until then. */
    private ModSuggestClient.Kind kind;
    private String comment = "";
    private boolean sending;
    private ModSuggestClient.Result lastError;
    private Button submit;
    private int top;

    public ModSuggestScreen(UnsupportedModsScreen parent, UnsupportedModsScreen.UnsupportedMod mod,
                            Consumer<ModSuggestClient.Result> onResult) {
        super(Component.literal(mod.displayName()));
        this.parent = parent;
        this.mod = mod;
        this.onResult = onResult;
    }

    @Override
    protected void init() {
        if (kind == null) {
            initPick();
            return;
        }
        int w = Math.min(FIELD_W, this.width - 32);
        int x = this.width / 2 - w / 2;
        int content = BOX_Y + BOX_H + GAP + 12 + GAP + BUTTON_H;
        this.top = Math.max(8, (this.height - content) / 2);

        // Whitelist | Modpack | Cheat — the selected one is tinted in its colour.
        ModSuggestClient.Kind[] kinds = ModSuggestClient.Kind.values();
        int third = (w - GAP * (kinds.length - 1)) / kinds.length;
        for (int i = 0; i < kinds.length; i++) {
            ModSuggestClient.Kind k = kinds[i];
            boolean selected = k == kind;
            Component label = Component.translatable(kindKey(k));
            addRenderableWidget(selected
                ? new DarkTintedButton(x + i * (third + GAP), top + KIND_Y, third, BUTTON_H,
                    label, b -> {}, KIND_TINT[i][0], KIND_TINT[i][1], KIND_TINT[i][2])
                : new DarkTintedButton(x + i * (third + GAP), top + KIND_Y, third, BUTTON_H,
                    label, b -> select(k)));
        }

        MultiLineEditBox box = new MultiLineEditBox(this.font, x, top + BOX_Y, w, BOX_H,
            Component.translatable("gui.dungeontrain.unsupported_mods.suggest.hint"),
            Component.translatable("gui.dungeontrain.unsupported_mods.suggest.why"));
        box.setCharacterLimit(MAX_COMMENT);
        box.setValue(comment);
        box.setValueListener(v -> {
            comment = v;
            refreshSubmit();
        });
        addRenderableWidget(box);
        setInitialFocus(box);

        int y = top + BOX_Y + BOX_H + GAP + 12 + GAP;
        int half = (w - GAP) / 2;
        addRenderableWidget(new DarkTintedButton(x, y, half, BUTTON_H, CommonComponents.GUI_CANCEL, b -> onClose()));
        submit = addRenderableWidget(new DarkTintedButton(x + w - half, y, half, BUTTON_H,
            Component.translatable(sending ? "gui.dungeontrain.unsupported_mods.suggest.sending"
                : "gui.dungeontrain.unsupported_mods.suggest.submit"),
            b -> send()));
        refreshSubmit();
    }

    /** The pick stage: three columns (button, meaning underneath) and Cancel. */
    private void initPick() {
        int w = Math.min(FIELD_W, this.width - 32);
        int x = this.width / 2 - w / 2;
        int content = pickCancelY() + BUTTON_H;
        this.top = Math.max(8, (this.height - content) / 2);
        ModSuggestClient.Kind[] kinds = ModSuggestClient.Kind.values();
        int col = columnWidth(w, kinds.length);
        for (int i = 0; i < kinds.length; i++) {
            ModSuggestClient.Kind k = kinds[i];
            addRenderableWidget(new DarkTintedButton(x + i * (col + GAP), top + PICK_BUTTON_Y, col, BUTTON_H,
                Component.translatable(kindKey(k)), b -> select(k),
                KIND_TINT[i][0], KIND_TINT[i][1], KIND_TINT[i][2]));
        }
        int cancelW = Math.min(120, w);
        addRenderableWidget(new DarkTintedButton(this.width / 2 - cancelW / 2, top + pickCancelY(), cancelW, BUTTON_H,
            CommonComponents.GUI_CANCEL, b -> onClose()));
    }

    private int pickCancelY() {
        return PICK_DESC_Y + PICK_DESC_LINES * (this.font.lineHeight + 1) + GAP * 2;
    }

    private static int columnWidth(int w, int columns) {
        return (w - GAP * (columns - 1)) / columns;
    }

    private static String kindKey(ModSuggestClient.Kind k) {
        return "gui.dungeontrain.unsupported_mods.kind." + k.name().toLowerCase(Locale.ROOT);
    }

    private void select(ModSuggestClient.Kind k) {
        if (sending) return;
        kind = k;
        lastError = null;
        rebuildWidgets();
    }

    private void refreshSubmit() {
        if (submit != null) submit.active = !sending && !comment.isBlank();
    }

    private void send() {
        if (sending || comment.isBlank()) return;
        sending = true;
        lastError = null;
        rebuildWidgets();
        String text = comment.trim();
        ModSuggestProof.suggest(mod.modId(), kind, text).thenAccept(result ->
            this.minecraft.execute(() -> onAnswer(result)));
    }

    private void onAnswer(ModSuggestClient.Result result) {
        sending = false;
        onResult.accept(result);
        if (result.isFinal()) {
            if (this.minecraft.screen == this) this.minecraft.setScreen(parent);
            return;
        }
        lastError = result;
        if (this.minecraft.screen == this) rebuildWidgets();
    }

    /**
     * The colour the list shows a result in: a sent result takes its choice's colour (green
     * whitelist, blue modpack, red cheat); a refusal the player can fix is red; anything else settled
     * (already approved / already decided) is the whitelist green.
     */
    static int resultColour(ModSuggestClient.Result r) {
        return switch (r) {
            case CREATED, BACKED, ALREADY_LISTED, ALREADY_DECIDED -> KIND_COLOUR[ModSuggestClient.Kind.WHITELIST.ordinal()];
            case MODPACK -> KIND_COLOUR[ModSuggestClient.Kind.MODPACK.ordinal()];
            case REPORTED -> KIND_COLOUR[ModSuggestClient.Kind.CHEAT.ordinal()];
            default -> 0xFFE08080;
        };
    }

    /** The player-facing line for a result — shared with the list, which shows it under the mod. */
    static Component resultMessage(ModSuggestClient.Result r) {
        return Component.translatable("gui.dungeontrain.unsupported_mods.result."
            + r.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int w = Math.min(FIELD_W, this.width - 32);
        int x = this.width / 2 - w / 2;
        g.drawCenteredString(this.font, this.title.copy().withStyle(ChatFormatting.BOLD), this.width / 2, top, 0xFFFFFFFF);
        g.drawCenteredString(this.font, Component.literal(mod.modId()).withStyle(ChatFormatting.GRAY),
            this.width / 2, top + 13, 0xFFFFFFFF);
        if (kind == null) {
            renderPick(g, x, w);
            return;
        }
        // One line under the choices saying what the selected one means.
        g.drawCenteredString(this.font, Component.translatable(kindKey(kind) + ".desc"),
            this.width / 2, top + DESC_Y, KIND_COLOUR[kind.ordinal()]);
        g.drawString(this.font, Component.translatable("gui.dungeontrain.unsupported_mods.suggest.why"),
            x, top + WHY_Y, 0xFFA0A0A0, false);
        // The box draws its own "n/280" counter at the right of this line; the error sits left of it.
        int y = top + BOX_Y + BOX_H + GAP;
        if (lastError != null) {
            int counterW = this.font.width(MAX_COMMENT + "/" + MAX_COMMENT);
            List<FormattedCharSequence> lines = this.font.split(resultMessage(lastError), w - counterW - 8);
            if (!lines.isEmpty()) g.drawString(this.font, lines.get(0), x, y, 0xFFE08080, false);
        }
    }

    /** Pick stage: the instruction heading, and each choice's meaning wrapped under its button. */
    private void renderPick(GuiGraphics g, int x, int w) {
        g.drawCenteredString(this.font, Component.translatable("gui.dungeontrain.unsupported_mods.kind.prompt"),
            this.width / 2, top + PICK_HEADING_Y, 0xFFFFD060);
        ModSuggestClient.Kind[] kinds = ModSuggestClient.Kind.values();
        int col = columnWidth(w, kinds.length);
        for (int i = 0; i < kinds.length; i++) {
            int cx = x + i * (col + GAP) + col / 2;
            int y = top + PICK_DESC_Y;
            List<FormattedCharSequence> lines =
                this.font.split(Component.translatable(kindKey(kinds[i]) + ".desc"), col);
            for (int l = 0; l < Math.min(PICK_DESC_LINES, lines.size()); l++) {
                g.drawCenteredString(this.font, lines.get(l), cx, y, KIND_COLOUR[i]);
                y += this.font.lineHeight + 1;
            }
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
