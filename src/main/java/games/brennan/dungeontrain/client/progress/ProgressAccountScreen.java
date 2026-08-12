package games.brennan.dungeontrain.client.progress;

import games.brennan.dungeontrain.progress.ProgressClient;
import games.brennan.dungeontrain.progress.ProgressCredential;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Shows this install's progress account and lets the player move it to another machine.
 *
 * <p>The account is per-install by design — there is no login, no password, and nothing to sign up
 * for. That leaves exactly one thing a player needs from a screen: a <b>recovery code</b> they can
 * carry to a second machine (or back, after a reinstall). The code IS the account's bearer secret, so
 * the screen is also the one place it is ever shown.</p>
 *
 * <p>Pasting a code links this install to that account. A wrong code reports as wrong rather than
 * quietly creating a new empty account, because "my progress is gone" and "I typed the code wrong" are
 * very different problems and the player should be told which one they have.</p>
 */
public final class ProgressAccountScreen extends Screen {

    private static final int ROW_W = 240;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 24;

    private final Screen parent;

    private EditBox codeInput;
    private Component status = Component.empty();

    public ProgressAccountScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.progress.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - ROW_W / 2;
        int y = this.height / 3;

        ProgressCredential credential = ProgressAccountClient.current();

        // Copy the recovery code. Only meaningful once an account exists.
        Button copy = Button.builder(Component.translatable("gui.dungeontrain.progress.copy"), b -> copyCode())
                .bounds(left, y, ROW_W, ROW_H).build();
        copy.active = credential != null && credential.isUsable();
        addRenderableWidget(copy);
        y += ROW_GAP;

        codeInput = new EditBox(this.font, left, y, ROW_W, ROW_H,
                Component.translatable("gui.dungeontrain.progress.paste"));
        codeInput.setMaxLength(128);
        codeInput.setHint(Component.translatable("gui.dungeontrain.progress.paste"));
        addRenderableWidget(codeInput);
        y += ROW_GAP;

        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.progress.link"), b -> link())
                .bounds(left, y, ROW_W, ROW_H).build());
        y += ROW_GAP + ROW_GAP;

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(left, y, ROW_W, ROW_H).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.title, cx, this.height / 3 - 40, 0xFFFFFF);
        g.drawCenteredString(this.font, accountLine(), cx, this.height / 3 - 24, 0xA0A0A0);
        if (!status.getString().isEmpty()) {
            g.drawCenteredString(this.font, status, cx, this.height - 60, 0xFFFF80);
        }
    }

    /** One line describing which account this install is on, in the player's language. */
    private Component accountLine() {
        ProgressCredential credential = ProgressAccountClient.current();
        if (credential == null || !credential.isUsable()) {
            return Component.translatable("gui.dungeontrain.progress.none");
        }
        return Component.translatable(credential.isVerified()
                ? "gui.dungeontrain.progress.account.verified"
                : "gui.dungeontrain.progress.account.local", credential.accountId());
    }

    private void copyCode() {
        ProgressCredential credential = ProgressAccountClient.current();
        if (credential == null || !credential.isUsable() || this.minecraft == null) return;
        this.minecraft.keyboardHandler.setClipboard(credential.secret());
        status = Component.translatable("gui.dungeontrain.progress.copied");
    }

    /** Link this install to the account behind the pasted code. */
    private void link() {
        String code = codeInput == null ? "" : codeInput.getValue().trim();
        if (code.isEmpty()) return;
        status = Component.translatable("gui.dungeontrain.progress.linking");
        ProgressClient.attach(code).thenAccept(credential -> {
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
                if (credential == null || !credential.isUsable()) {
                    status = Component.translatable("gui.dungeontrain.progress.link_failed");
                    return;
                }
                ProgressAccountClient.adopt(credential);
                status = Component.translatable("gui.dungeontrain.progress.linked");
                // The account line above is read live, so it updates on the next frame.
            });
        });
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
