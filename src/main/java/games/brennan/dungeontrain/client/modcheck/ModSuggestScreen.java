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
import java.util.function.Consumer;

/**
 * Suggest one mod for the whitelist, from the {@link UnsupportedModsScreen}. The comment is required
 * — Submit stays disabled until something is written — and goes to the relay with the suggestion.
 * If nobody has suggested the mod yet this starts a suggestion; if somebody has, it backs theirs.
 *
 * <p>Sending runs off-thread ({@link ModSuggestProof}); the answer comes back on the render thread.
 * A result the player can't fix by rewording (sent, already decided, already listed) returns to the
 * list; anything else stays here with the reason shown, so the comment isn't lost.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ModSuggestScreen extends Screen {

    /** The relay's comment cap ({@code mod-votes-store.MAX_COMMENT_CHARS}). */
    static final int MAX_COMMENT = 280;

    private static final int FIELD_W = 300;
    private static final int BOX_H = 80;
    private static final int BUTTON_H = 20;
    private static final int GAP = 6;

    private final UnsupportedModsScreen parent;
    private final UnsupportedModsScreen.UnsupportedMod mod;
    private final Consumer<ModSuggestClient.Result> onResult;

    private String comment = "";
    private boolean sending;
    private ModSuggestClient.Result lastError;
    private MultiLineEditBox box;
    private Button submit;
    private int top;

    public ModSuggestScreen(UnsupportedModsScreen parent, UnsupportedModsScreen.UnsupportedMod mod,
                            Consumer<ModSuggestClient.Result> onResult) {
        super(Component.translatable("gui.dungeontrain.unsupported_mods.suggest.title", mod.displayName()));
        this.parent = parent;
        this.mod = mod;
        this.onResult = onResult;
    }

    @Override
    protected void init() {
        int w = Math.min(FIELD_W, this.width - 32);
        int x = this.width / 2 - w / 2;
        int content = 40 + BOX_H + GAP + 12 + GAP + BUTTON_H;
        this.top = Math.max(8, (this.height - content) / 2);

        box = new MultiLineEditBox(this.font, x, top + 40, w, BOX_H,
            Component.translatable("gui.dungeontrain.unsupported_mods.suggest.hint"),
            Component.translatable("gui.dungeontrain.unsupported_mods.suggest.label"));
        box.setCharacterLimit(MAX_COMMENT);
        box.setValue(comment);
        box.setValueListener(v -> {
            comment = v;
            refreshSubmit();
        });
        addRenderableWidget(box);
        setInitialFocus(box);

        int y = top + 40 + BOX_H + GAP + 12 + GAP;
        int half = (w - GAP) / 2;
        addRenderableWidget(new DarkTintedButton(x, y, half, BUTTON_H, CommonComponents.GUI_CANCEL, b -> onClose()));
        submit = addRenderableWidget(new DarkTintedButton(x + w - half, y, half, BUTTON_H,
            Component.translatable(sending ? "gui.dungeontrain.unsupported_mods.suggest.sending"
                : "gui.dungeontrain.unsupported_mods.suggest.submit"),
            b -> send()));
        refreshSubmit();
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
        ModSuggestProof.suggest(mod.modId(), text).thenAccept(result ->
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

    /** The player-facing line for a result — shared with the list, which shows it under the mod. */
    static Component resultMessage(ModSuggestClient.Result r) {
        return Component.translatable("gui.dungeontrain.unsupported_mods.result."
            + r.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int w = Math.min(FIELD_W, this.width - 32);
        int x = this.width / 2 - w / 2;
        g.drawCenteredString(this.font, this.title.copy().withStyle(ChatFormatting.BOLD), this.width / 2, top, 0xFFFFFFFF);
        g.drawCenteredString(this.font, Component.literal(mod.modId()).withStyle(ChatFormatting.GRAY),
            this.width / 2, top + 13, 0xFFFFFFFF);
        g.drawString(this.font, Component.translatable("gui.dungeontrain.unsupported_mods.suggest.label"),
            x, top + 28, 0xFFA0A0A0, false);
        int y = top + 40 + BOX_H + GAP;
        String count = comment.length() + " / " + MAX_COMMENT;
        g.drawString(this.font, count, x + w - this.font.width(count), y, 0xFF707070, false);
        if (lastError != null) {
            List<FormattedCharSequence> lines = this.font.split(resultMessage(lastError), w - this.font.width(count) - 8);
            if (!lines.isEmpty()) g.drawString(this.font, lines.get(0), x, y, 0xFFE08080, false);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
