package games.brennan.dungeontrain.client.credits;

import games.brennan.dungeontrain.client.chat.RelayChatClient;
import games.brennan.dungeontrain.client.localization.edit.TranslatorRenameClient;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * The small form behind the Credits page's <b>Edit</b> button: one box holding the name a
 * translator is credited under, Save, Cancel, and a line saying what happened.
 *
 * <p>Deliberately not a settings option. The credited name is the translator's, not the game's
 * ({@code TranslatorName}), and the place to change it is beside where it is shown. Save is a
 * relay call — the rename has to happen where the credit lives — so this is consent-gated and
 * says so, the way the submit screen does.</p>
 */
public final class TranslatorRenameScreen extends Screen {

    /** Mirrors the submit screen's cap and the relay's {@code MAX_TRANSLATOR_CHARS}. */
    private static final int MAX_NAME_CHARS = 80;
    private static final int FORM_W = 220;
    private static final int ROW_H = 20;
    private static final int GAP = 6;

    private final Screen parent;
    private final String from;
    /** Called on the render thread with (old name, new name) once the relay has accepted it. */
    private final BiConsumer<String, String> onRenamed;

    private EditBox nameBox;
    private Button saveButton;
    private Component status = Component.empty();
    private List<FormattedCharSequence> hintLines = List.of();
    private boolean sending;

    public TranslatorRenameScreen(Screen parent, String from, BiConsumer<String, String> onRenamed) {
        super(Component.translatable("gui.dungeontrain.credits.rename.title"));
        this.parent = parent;
        this.from = from;
        this.onRenamed = onRenamed;
    }

    @Override
    protected void init() {
        int formW = Math.min(FORM_W, this.width - 40);
        int left = (this.width - formW) / 2;
        int y = this.height / 2 - 30;

        nameBox = new EditBox(font, left, y, formW, ROW_H,
            Component.translatable("gui.dungeontrain.credits.rename.name"));
        nameBox.setHint(Component.translatable("gui.dungeontrain.credits.rename.name"));
        nameBox.setMaxLength(MAX_NAME_CHARS);
        nameBox.setValue(from);
        nameBox.setResponder(v -> updateSaveState());
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);
        y += ROW_H + GAP;

        int half = (formW - GAP) / 2;
        saveButton = addRenderableWidget(new DarkTintedButton(left, y, half, ROW_H,
            Component.translatable("gui.dungeontrain.credits.rename.save"), b -> save()));
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
            .bounds(left + half + GAP, y, formW - half - GAP, ROW_H).build());

        Component hint = RelayChatClient.canConnect()
            ? Component.translatable("gui.dungeontrain.credits.rename.hint")
            : Component.translatable("gui.dungeontrain.credits.rename.no_consent");
        hintLines = font.split(FormattedText.of(hint.getString()), formW);
        updateSaveState();
    }

    /** The trimmed name in the box — what would be saved. */
    private String proposed() {
        return nameBox == null ? "" : nameBox.getValue().trim();
    }

    private void updateSaveState() {
        if (saveButton == null) {
            return;
        }
        String to = proposed();
        saveButton.active = !sending && !to.isEmpty() && !to.equals(from);
    }

    private void save() {
        String to = proposed();
        if (to.isEmpty() || to.equals(from)) {
            status = Component.translatable("gui.dungeontrain.credits.rename.unchanged")
                .withStyle(ChatFormatting.GOLD);
            return;
        }
        if (!RelayChatClient.canConnect()) {
            status = Component.translatable("gui.dungeontrain.credits.rename.no_consent")
                .withStyle(ChatFormatting.RED);
            return;
        }
        sending = true;
        updateSaveState();
        status = Component.translatable("gui.dungeontrain.credits.rename.sending")
            .withStyle(ChatFormatting.GRAY);
        TranslatorRenameClient.send(from, to).whenComplete((result, err) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return;
            }
            mc.execute(() -> finish(to, err != null ? null : result));
        });
    }

    /** Back on the render thread with the relay's answer. */
    private void finish(String to, TranslatorRenameClient.Result result) {
        sending = false;
        updateSaveState();
        if (result != null && result.ok()) {
            onRenamed.accept(from, to);
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
        g.drawCenteredString(font, title, this.width / 2, this.height / 2 - 30 - 16, 0xFFFFFFFF);

        int y = this.height / 2 - 30 + ROW_H + GAP + ROW_H + GAP + 2;
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
