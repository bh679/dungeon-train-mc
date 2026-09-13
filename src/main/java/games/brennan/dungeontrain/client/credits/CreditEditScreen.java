package games.brennan.dungeontrain.client.credits;

import games.brennan.dungeontrain.client.chat.RelayChatClient;
import games.brennan.dungeontrain.client.credits.CreditEditClient.Action;
import games.brennan.dungeontrain.client.credits.CreditEditClient.Section;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The small form behind the Credits page's <b>Edit</b> button, on any of its three cards: one box
 * holding the name the player is credited under, Save, <b>Remove</b> (be listed as Anonymous —
 * behind a confirm), Cancel, and a line saying what happened. A line that is already anonymous
 * offers <b>Restore</b> instead of the box. Whichever card it was opened from, the edit is to the
 * player's <b>one</b> identity — every card follows.
 *
 * <p>Deliberately not a settings option. The credited name is the player's, not the game's, and
 * the place to change it is beside where it is shown. Every button is a relay call — the change has
 * to happen where the credit lives — so this is consent-gated and says so, the way the submit screen
 * does. Nothing here deletes anything: Remove keeps the count and drops the name, and Restore is
 * one click away.</p>
 */
public final class CreditEditScreen extends Screen {

    /** What the page does once the relay has accepted an edit; runs on the render thread. */
    @FunctionalInterface
    public interface OnEdited {
        void accept(Section section, Action action, String from, String to);
    }

    /** Mirrors the submit screen's cap and the relay's {@code MAX_NAME_CHARS}. */
    private static final int MAX_NAME_CHARS = 80;
    private static final int FORM_W = 220;
    private static final int ROW_H = 20;
    private static final int GAP = 6;

    private final Screen parent;
    private final Section section;
    private final String from;
    /** The line is already anonymous — offer Restore rather than a name box. */
    private final boolean hidden;
    private final OnEdited onEdited;

    private EditBox nameBox;
    private Button saveButton;
    private Button removeButton;
    private Button restoreButton;
    private Component status = Component.empty();
    private List<FormattedCharSequence> hintLines = List.of();
    private boolean sending;

    public CreditEditScreen(Screen parent, Section section, String from, boolean hidden, OnEdited onEdited) {
        super(Component.translatable("gui.dungeontrain.credits.rename.title"));
        this.parent = parent;
        this.section = section;
        this.from = from == null ? "" : from;
        this.hidden = hidden;
        this.onEdited = onEdited;
    }

    @Override
    protected void init() {
        int formW = Math.min(FORM_W, this.width - 40);
        int left = (this.width - formW) / 2;
        int y = formTop();

        if (!hidden) {
            nameBox = new EditBox(font, left, y, formW, ROW_H,
                Component.translatable("gui.dungeontrain.credits.rename.name"));
            nameBox.setHint(Component.translatable("gui.dungeontrain.credits.rename.name"));
            nameBox.setMaxLength(MAX_NAME_CHARS);
            nameBox.setValue(from);
            nameBox.setResponder(v -> updateButtons());
            addRenderableWidget(nameBox);
            setInitialFocus(nameBox);
            y += ROW_H + GAP;
        }

        int half = (formW - GAP) / 2;
        if (hidden) {
            restoreButton = addRenderableWidget(new DarkTintedButton(left, y, half, ROW_H,
                Component.translatable("gui.dungeontrain.credits.rename.restore"), b -> restore()));
        } else {
            saveButton = addRenderableWidget(new DarkTintedButton(left, y, half, ROW_H,
                Component.translatable("gui.dungeontrain.credits.rename.save"), b -> save()));
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
            .bounds(left + half + GAP, y, formW - half - GAP, ROW_H).build());
        y += ROW_H + GAP;

        if (!hidden) {
            removeButton = addRenderableWidget(new DarkTintedButton(left, y, formW, ROW_H,
                Component.translatable("gui.dungeontrain.credits.rename.remove"), b -> confirmRemove()));
        }

        Component hint = !RelayChatClient.canConnect()
            ? Component.translatable("gui.dungeontrain.credits.rename.no_consent")
            : hidden ? Component.translatable("gui.dungeontrain.credits.rename.hint.hidden")
            : Component.translatable("gui.dungeontrain.credits.rename.hint");
        hintLines = font.split(FormattedText.of(hint.getString()), formW);
        updateButtons();
    }

    private int formTop() {
        return this.height / 2 - 30;
    }

    /** The trimmed name in the box — what would be saved. */
    private String proposed() {
        return nameBox == null ? "" : nameBox.getValue().trim();
    }

    private void updateButtons() {
        if (saveButton != null) {
            String to = proposed();
            saveButton.active = !sending && !to.isEmpty() && !to.equals(from);
        }
        if (removeButton != null) removeButton.active = !sending;
        if (restoreButton != null) restoreButton.active = !sending;
    }

    private boolean consentOk() {
        if (RelayChatClient.canConnect()) return true;
        status = Component.translatable("gui.dungeontrain.credits.rename.no_consent")
            .withStyle(ChatFormatting.RED);
        return false;
    }

    private void save() {
        String to = proposed();
        if (to.isEmpty() || to.equals(from)) {
            status = Component.translatable("gui.dungeontrain.credits.rename.unchanged")
                .withStyle(ChatFormatting.GOLD);
            return;
        }
        if (!consentOk()) return;
        begin(Action.RENAME, to, CreditEditClient.rename(from, to));
    }

    /** Remove is the one action that changes what everybody sees without a name to type — confirm it. */
    private void confirmRemove() {
        if (!consentOk()) return;
        Minecraft.getInstance().setScreen(new ConfirmScreen(yes -> {
            Minecraft.getInstance().setScreen(this);
            if (yes) begin(Action.REMOVE, "", CreditEditClient.remove());
        },
            Component.translatable("gui.dungeontrain.credits.rename.remove.confirm_title"),
            Component.translatable("gui.dungeontrain.credits.rename.remove.confirm_body")));
    }

    private void restore() {
        if (!consentOk()) return;
        begin(Action.RESTORE, "", CreditEditClient.restore());
    }

    private void begin(Action action, String to, java.util.concurrent.CompletableFuture<CreditEditClient.Result> call) {
        sending = true;
        updateButtons();
        status = Component.translatable("gui.dungeontrain.credits.rename.sending")
            .withStyle(ChatFormatting.GRAY);
        call.whenComplete((result, err) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.execute(() -> finish(action, to, err != null ? null : result));
        });
    }

    /** Back on the render thread with the relay's answer. */
    private void finish(Action action, String to, CreditEditClient.Result result) {
        sending = false;
        updateButtons();
        if (result != null && result.ok()) {
            onEdited.accept(section, action, from, to);
            return;
        }
        String key = result == null ? "failed" : switch (result.error()) {
            case NO_CONSENT -> "no_consent";
            case NOT_YOURS -> "not_yours";
            case NAME_TAKEN -> "name_taken";
            case RATE_LIMITED -> "rate_limited";
            case UNSUPPORTED -> "unsupported";
            default -> "failed";
        };
        status = Component.translatable("gui.dungeontrain.credits.rename." + key)
            .withStyle(ChatFormatting.RED);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int formW = Math.min(FORM_W, this.width - 40);
        int left = (this.width - formW) / 2;
        g.drawCenteredString(font, title, this.width / 2, formTop() - 16, 0xFFFFFFFF);

        // Below the last row of buttons: name box + Save/Cancel + Remove, or Restore/Cancel alone.
        int rows = hidden ? 1 : 3;
        int y = formTop() + rows * (ROW_H + GAP) + 2;
        for (FormattedCharSequence line : hintLines) {
            g.drawString(font, line, left, y, 0xFFA0A0A0, false);
            y += font.lineHeight;
        }
        if (!status.getString().isEmpty()) {
            y += 2;
            for (FormattedCharSequence line : font.split(status, formW)) {
                g.drawString(font, line, left, y, 0xFFFFFFFF, false);
                y += font.lineHeight;
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter saves, like any one-field form.
        if ((keyCode == 257 || keyCode == 335) && saveButton != null && saveButton.active) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
