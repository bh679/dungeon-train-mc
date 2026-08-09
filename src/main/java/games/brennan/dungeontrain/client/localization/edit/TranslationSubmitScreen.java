package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.client.chat.RelayChatClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sends this player's edits to the relay so they can be reviewed and, once approved, shipped to
 * everyone else playing in this language.
 *
 * <p>Crediting is a yes/no, not a text box to remember to fill in: the checkbox is on and the name
 * is already filled from the player's Minecraft username. It stays editable because a community
 * translator's credited name is usually not their username — {@code 老本願} and {@code 阿世xAsh}
 * are the names on the Credits page, and those are what land in
 * {@code localization/authors.json}. Unticking submits genuinely anonymously (an empty
 * {@code translator}), not a blank name field.</p>
 *
 * <p>Submitting is gated on the same network consent as menu chat, since it carries
 * player-authored text and a uuid. Editing and applying locally are not gated — so when Send is
 * unavailable the screen names the reason rather than leaving the player to guess at a greyed-out
 * button.</p>
 */
public final class TranslationSubmitScreen extends Screen {

    private static final int MARGIN = 16;
    private static final int ROW_W = 260;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 24;
    private static final int LABEL_COLOUR = 0xFFA0A0A0;
    private static final int NOTE_COLOUR = 0xFFD8A657;
    private static final int OK_COLOUR = 0xFF7FDD7F;
    /** Matches the relay's TRANSLATIONS_MAX_UNITS — the server rejects a bigger batch outright. */
    private static final int MAX_UNITS_PER_SUBMISSION = 200;
    private static final int MAX_NAME_CHARS = 80;

    private final TranslationScreen parent;
    private final String locale;

    private Checkbox creditBox;
    private EditBox nameBox;
    private Button submitButton;
    private Component status = CommonComponents.EMPTY;
    private List<FormattedCharSequence> noteLines = List.of();

    public TranslationSubmitScreen(TranslationScreen parent, String locale) {
        super(Component.translatable("gui.dungeontrain.translate.submit.title"));
        this.parent = parent;
        this.locale = locale;
    }

    @Override
    protected void init() {
        int left = width / 2 - ROW_W / 2;
        int y = height / 3;

        creditBox = Checkbox.builder(
                Component.translatable("gui.dungeontrain.translate.submit.credit"), this.font)
            .pos(left, y)
            .selected(true)
            .onValueChange((box, checked) -> updateNameBoxState())
            .build();
        addRenderableWidget(creditBox);
        y += ROW_GAP;

        nameBox = new EditBox(font, left, y, ROW_W, ROW_H,
            Component.translatable("gui.dungeontrain.translate.submit.name"));
        nameBox.setHint(Component.translatable("gui.dungeontrain.translate.submit.name"));
        nameBox.setMaxLength(MAX_NAME_CHARS);
        nameBox.setValue(defaultName());
        addRenderableWidget(nameBox);
        y += ROW_GAP;

        submitButton = addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.submit.send"), b -> send())
            .bounds(left, y, ROW_W, ROW_H).build());
        y += ROW_GAP + ROW_GAP;

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(left, y, ROW_W, ROW_H).build());

        updateNameBoxState();
        updateSubmitState();

        Component note = RelayChatClient.canConnect()
            ? Component.translatable("gui.dungeontrain.translate.submit.note")
            : Component.translatable("gui.dungeontrain.translate.submit.no_consent");
        noteLines = font.split(FormattedText.of(note.getString()), ROW_W + 60);
    }

    /**
     * The name to credit: the one they used last, else their Minecraft username. Prefilling is
     * what stops an "(optional)" field reading as a required one.
     */
    private String defaultName() {
        String remembered = TranslatorName.get();
        if (!remembered.isEmpty()) {
            return remembered;
        }
        return minecraft.getUser() != null ? minecraft.getUser().getName() : "";
    }

    private void updateNameBoxState() {
        boolean credit = creditBox.selected();
        nameBox.setEditable(credit);
        nameBox.active = credit;
        nameBox.visible = credit;
    }

    /**
     * Enable Send, or say why not. The reported confusion was a disabled button with an
     * "(optional)" field next to it, which reads as "the field is the problem" — it never was.
     */
    private void updateSubmitState() {
        boolean hasEdits = !TranslationOverrides.localFor(locale).isEmpty();
        boolean consent = RelayChatClient.canConnect();
        submitButton.active = hasEdits && consent;
        if (submitButton.active) {
            submitButton.setTooltip(null);
        } else {
            submitButton.setTooltip(Tooltip.create(hasEdits
                ? Component.translatable("gui.dungeontrain.translate.submit.blocked.consent")
                : Component.translatable("gui.dungeontrain.translate.submit.blocked.no_edits")));
            status = hasEdits
                ? Component.translatable("gui.dungeontrain.translate.submit.blocked.consent")
                : Component.translatable("gui.dungeontrain.translate.submit.blocked.no_edits");
        }
    }

    /**
     * Turn the player's local overrides into units. Only their own edits go — the relay-approved
     * layer is other people's work already in the system, and resubmitting it would spam the queue
     * with duplicates of things that are already live.
     */
    private List<TranslationSubmitClient.Unit> buildUnits() {
        TranslationEdits local = TranslationOverrides.localFor(locale);
        List<TranslationSubmitClient.Unit> units = new ArrayList<>();
        for (TranslationUnit unit : TranslationCatalog.forLocale(locale)) {
            if (units.size() >= MAX_UNITS_PER_SUBMISSION) {
                break;
            }
            String value = unit.type() == TranslationUnit.Type.BOOK
                ? local.books().get(unit.id()) : local.lang().get(unit.id());
            if (value == null) {
                continue;
            }
            units.add(new TranslationSubmitClient.Unit(
                unit.type() == TranslationUnit.Type.BOOK ? "book" : "lang",
                unit.namespace(), unit.id(), unit.source(), value));
        }
        return units;
    }

    private void send() {
        UUID uuid = minecraft.getUser() != null ? minecraft.getUser().getProfileId() : null;
        List<TranslationSubmitClient.Unit> units = buildUnits();
        if (uuid == null || units.isEmpty()) {
            status = Component.translatable("gui.dungeontrain.translate.submit.blocked.no_edits");
            return;
        }
        // Unticked means anonymous: an empty translator, which the relay stores as-is. The name is
        // still remembered, so ticking the box next time brings it back rather than starting over.
        String name = creditBox.selected() ? nameBox.getValue().trim() : "";
        if (creditBox.selected()) {
            TranslatorName.set(name);
        }
        TranslationOutbox.get().submit(TranslationSubmitClient
            .buildPayload(uuid.toString().replace("-", ""), name, locale, units).toString());

        // Queued, not delivered — the outbox owns delivery and retries, so the honest report is
        // "sent for review", with the count the player can check against their edits.
        status = Component.translatable("gui.dungeontrain.translate.submit.queued", units.size());
        submitButton.active = false;
        submitButton.setTooltip(null);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, MARGIN, 0xFFFFFFFF);
        g.drawCenteredString(font,
            Component.translatable("gui.dungeontrain.translate.submit.subtitle",
                locale, TranslationOverrides.localFor(locale).size()),
            width / 2, MARGIN + font.lineHeight + 2, LABEL_COLOUR);

        int y = height / 3 + ROW_GAP * 3;
        for (FormattedCharSequence line : noteLines) {
            g.drawCenteredString(font, line, width / 2, y, NOTE_COLOUR);
            y += font.lineHeight;
        }
        if (status != CommonComponents.EMPTY) {
            g.drawCenteredString(font, status, width / 2, height - MARGIN - font.lineHeight * 2,
                OK_COLOUR);
        }
        int queued = TranslationOutbox.get().pendingCount();
        if (queued > 0) {
            g.drawCenteredString(font,
                Component.translatable("gui.dungeontrain.translate.submit.pending", queued),
                width / 2, height - MARGIN - font.lineHeight, LABEL_COLOUR);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
