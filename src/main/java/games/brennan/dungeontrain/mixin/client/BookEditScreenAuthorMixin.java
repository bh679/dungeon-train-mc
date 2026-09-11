package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.client.EditorBookAuthorClient;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Editor prop books — lets an author signing a book <b>inside an editor plot</b> credit it to a
 * custom name ("by The Conductor") instead of their Minecraft username.
 *
 * <p>Vanilla's sign screen draws a fixed "by &lt;player&gt;" line under the title and its edit
 * packet carries no author at all — the server stamps {@code player.getName()}. So the name is
 * edited here, mirroring vanilla's own title editing (a {@link TextFieldHelper} driven straight
 * from {@code keyPressed}/{@code charTyped} — no {@code EditBox}, because {@code render()} calls
 * {@code setFocused(null)} every frame), sent ahead of the edit packet by
 * {@link EditorBookAuthorClient#sendForNextSign}, and applied server-side by
 * {@code ServerGamePacketListenerImplSignBookMixin} on its editor-plot branch only.</p>
 *
 * <p><b>Only in a plot.</b> Everything here is gated on {@link EditorStatusHudOverlay#isActive()}
 * — "the server says I am standing in an editor plot" — the same signal
 * {@link BookEditScreenSuspensionMixin} uses. Out on the live train the screen is untouched:
 * community books, letters and Notes keep the real name, and the server ignores a stray packet.</p>
 *
 * <p>The author line starts <b>blank</b> every time and is <b>required</b>: Finalize stays inactive
 * and Enter is swallowed (moving the cursor to the author line) until both title and author are
 * non-blank — a prop book credited to the builder's username by accident is the thing being
 * prevented. Controls: <b>Tab</b> or a click on the author line moves the cursor between title and
 * author; Backspace edits, Enter finalizes from either field (falls through to vanilla). The author
 * line is drawn black with a blinking cursor while it has the cursor, vanilla's dark grey otherwise,
 * and a one-line hint sits under the finalize warning. The "by" line itself is swapped by a
 * {@code WrapOperation} on vanilla's own {@code drawString} call (matched by identity against the
 * {@code ownerText} field), so nothing else in the layout moves.</p>
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenAuthorMixin {

    @Shadow private boolean isSigning;
    @Shadow private String title;
    @Shadow private int frameTick;
    @Shadow @Final private Component ownerText;
    @Shadow private Button finalizeButton;

    @Shadow protected abstract void updateButtonVisibility();

    @Unique private static final FormattedCharSequence DUNGEONTRAIN$BLACK_CURSOR =
            FormattedCharSequence.forward("_", Style.EMPTY.withColor(ChatFormatting.BLACK));
    @Unique private static final FormattedCharSequence DUNGEONTRAIN$GRAY_CURSOR =
            FormattedCharSequence.forward("_", Style.EMPTY.withColor(ChatFormatting.GRAY));

    /** Vanilla's signing layout: the page's left inner edge is {@code (width-192)/2 + 36}, 114 wide. */
    @Unique private static final int DUNGEONTRAIN$PAGE_INNER_X = 36;
    @Unique private static final int DUNGEONTRAIN$PAGE_INNER_W = 114;
    @Unique private static final int DUNGEONTRAIN$TITLE_Y = 50;
    @Unique private static final int DUNGEONTRAIN$AUTHOR_Y = 60;
    @Unique private static final int DUNGEONTRAIN$HINT_Y = 140;
    @Unique private static final int DUNGEONTRAIN$LINE_H = 10;
    @Unique private static final int DUNGEONTRAIN$DARK_GRAY = 0x55_55_55;

    @Unique private String dungeontrain$author;
    @Unique private TextFieldHelper dungeontrain$authorEdit;
    @Unique private boolean dungeontrain$editingAuthor;

    /** True while the sign form is up AND the player is standing in an editor plot. */
    @Unique
    private boolean dungeontrain$editorMode() {
        if (!this.isSigning) return false;
        try {
            return EditorStatusHudOverlay.isActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the author line holds a usable name. */
    @Unique
    private boolean dungeontrain$hasAuthor() {
        return this.dungeontrain$author != null && !this.dungeontrain$author.isBlank();
    }

    /** Lazily built on first use so the constructor-time field order never matters. Starts blank. */
    @Unique
    private TextFieldHelper dungeontrain$authorEdit() {
        if (this.dungeontrain$authorEdit == null) {
            this.dungeontrain$author = "";
            int max = EditorBookAuthorClient.maxLength();
            this.dungeontrain$authorEdit = new TextFieldHelper(
                    () -> this.dungeontrain$author,
                    s -> this.dungeontrain$author = s,
                    () -> "", s -> {},
                    s -> s.length() <= max);
            this.dungeontrain$authorEdit.setCursorToEnd();
        }
        return this.dungeontrain$authorEdit;
    }

    // ---- input -------------------------------------------------------------------------------

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$authorKeyPressed(int keyCode, int scanCode, int modifiers,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (!dungeontrain$editorMode()) return;
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            this.dungeontrain$editingAuthor = !this.dungeontrain$editingAuthor;
            dungeontrain$authorEdit().setCursorToEnd();
            cir.setReturnValue(true);
            return;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            // No author yet: refuse to finalize and put the cursor where the missing text goes.
            // Otherwise fall through to vanilla's titleKeyPressed, which finalizes the book.
            if (!dungeontrain$hasAuthor()) {
                this.dungeontrain$editingAuthor = true;
                dungeontrain$authorEdit().setCursorToEnd();
                cir.setReturnValue(true);
            }
            return;
        }
        if (!this.dungeontrain$editingAuthor) return;
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            dungeontrain$authorEdit().removeCharsFromCursor(-1);
            this.updateButtonVisibility();
            cir.setReturnValue(true);
            return;
        }
        // Swallow everything else so a keystroke aimed at the author line never edits the title.
        cir.setReturnValue(true);
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$authorCharTyped(char codePoint, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (!dungeontrain$editorMode() || !this.dungeontrain$editingAuthor) return;
        boolean typed = dungeontrain$authorEdit().charTyped(codePoint);
        if (typed) this.updateButtonVisibility();
        cir.setReturnValue(typed);
    }

    /** A click on the author line takes the cursor; a click on the title line gives it back. */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void dungeontrain$authorMouseClicked(double mouseX, double mouseY, int button,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (button != 0 || !dungeontrain$editorMode()) return;
        int left = dungeontrain$pageLeft();
        if (mouseX < left || mouseX >= left + DUNGEONTRAIN$PAGE_INNER_W) return;
        if (mouseY >= DUNGEONTRAIN$AUTHOR_Y && mouseY < DUNGEONTRAIN$AUTHOR_Y + DUNGEONTRAIN$LINE_H) {
            this.dungeontrain$editingAuthor = true;
            dungeontrain$authorEdit().setCursorToEnd();
        } else if (mouseY >= DUNGEONTRAIN$TITLE_Y && mouseY < DUNGEONTRAIN$TITLE_Y + DUNGEONTRAIN$LINE_H) {
            this.dungeontrain$editingAuthor = false;
        }
    }

    /**
     * Finalize needs BOTH a title and an author in editor mode — vanilla has just set
     * {@code active} from the title alone, so this only ever turns the button OFF, never on.
     * Leaving signing mode (Cancel) hands the cursor back to the title for next time.
     */
    @Inject(method = "updateButtonVisibility", at = @At("RETURN"))
    private void dungeontrain$requireAuthor(CallbackInfo ci) {
        if (!this.isSigning) {
            this.dungeontrain$editingAuthor = false;
            return;
        }
        if (dungeontrain$editorMode() && !dungeontrain$hasAuthor() && this.finalizeButton != null) {
            this.finalizeButton.active = false;
        }
    }

    // ---- sign ---------------------------------------------------------------------------------

    /** Sends the name BEFORE vanilla's own edit packet goes out in the body of saveChanges. */
    @Inject(method = "saveChanges", at = @At("HEAD"))
    private void dungeontrain$sendAuthorOnSign(boolean publish, CallbackInfo ci) {
        if (!publish || !dungeontrain$editorMode() || !dungeontrain$hasAuthor()) return;
        EditorBookAuthorClient.sendForNextSign(this.dungeontrain$author);
    }

    // ---- render -------------------------------------------------------------------------------

    /**
     * Swap vanilla's fixed "by &lt;player&gt;" line for the editable one. Vanilla also routes the
     * "Enter Book Title:" label and the page counter through this overload; those are passed
     * through untouched — only the call drawing {@code ownerText} is replaced.
     */
    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"))
    private int dungeontrain$drawAuthorLine(GuiGraphics graphics, Font font, Component text, int x, int y,
                                            int color, boolean shadow, Operation<Integer> original) {
        if (text != this.ownerText || !dungeontrain$editorMode()) {
            return original.call(graphics, font, text, x, y, color, shadow);
        }
        dungeontrain$authorEdit(); // make sure the author is initialised before first draw
        Component byLine = Component.translatable("book.byAuthor", this.dungeontrain$author);
        FormattedCharSequence line;
        int lineColor;
        if (this.dungeontrain$editingAuthor) {
            boolean blink = this.frameTick / 6 % 2 == 0;
            line = FormattedCharSequence.composite(byLine.getVisualOrderText(),
                    blink ? DUNGEONTRAIN$BLACK_CURSOR : DUNGEONTRAIN$GRAY_CURSOR);
            lineColor = 0;
        } else {
            line = byLine.getVisualOrderText();
            lineColor = DUNGEONTRAIN$DARK_GRAY;
        }
        int width = font.width(line);
        int left = dungeontrain$pageLeft();
        return graphics.drawString(font, line, left + (DUNGEONTRAIN$PAGE_INNER_W - width) / 2, y, lineColor, false);
    }

    /** One-line hint under the finalize warning, editor mode only. */
    @Inject(method = "render", at = @At("RETURN"))
    private void dungeontrain$drawAuthorHint(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                             CallbackInfo ci) {
        if (!dungeontrain$editorMode()) return;
        Font font = Minecraft.getInstance().font;
        graphics.drawWordWrap(font, Component.translatable("gui.dungeontrain.editor_book.author_hint"),
                dungeontrain$pageLeft(), DUNGEONTRAIN$HINT_Y, DUNGEONTRAIN$PAGE_INNER_W, DUNGEONTRAIN$DARK_GRAY);
    }

    @Unique
    private int dungeontrain$pageLeft() {
        return (((BookEditScreen) (Object) this).width - 192) / 2 + DUNGEONTRAIN$PAGE_INNER_X;
    }
}
