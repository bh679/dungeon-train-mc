package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One submission as the "sent in" list shows it: when it went, who it credits, how many strings
 * it carried, and where it got to.
 *
 * <p>Two kinds of row end up here. The relay's {@code /translations/mine} supplies everything it
 * has seen, grouped one row per submission. Anything still sitting in the local outbox is folded
 * in as {@link #queued}, because a translator who has just pressed Submit while offline should
 * see their work in the list rather than an empty panel that reads as "nothing was sent".</p>
 *
 * @param submittedAtMs when it was sent (relay timestamp, or the local queue time)
 * @param locale        the language it was for
 * @param translator    the credited name, or {@code ""} for an anonymous submission
 * @param units         how many strings it carried
 * @param approved      how many have been accepted
 * @param pending       how many are still waiting on review
 * @param flagged       how many were held by moderation
 * @param rejected      how many were turned down
 * @param queued        true when this has not reached the relay yet
 * @param unsubmitted   true for the working batch — edits not yet handed to anyone
 */
public record TranslationSubmission(long submittedAtMs, String locale, String translator,
                                    int units, int approved, int pending, int flagged,
                                    int rejected, boolean queued, boolean unsubmitted) {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public TranslationSubmission {
        if (queued && unsubmitted) {
            // The two are exclusive by meaning: queued has been handed over and is waiting on the
            // network, unsubmitted has not been handed to anybody. A row claiming both would render
            // one status while behaving as the other.
            throw new IllegalArgumentException("a submission cannot be both queued and unsubmitted");
        }
    }

    /** A submission still in the outbox — the relay has never seen it. */
    public static TranslationSubmission queued(long submittedAtMs, String locale, String translator,
                                               int units) {
        return new TranslationSubmission(submittedAtMs, locale, translator, units,
            0, 0, 0, 0, true, false);
    }

    /**
     * The current working batch: edits made since the last Submit, which nobody has been asked to
     * do anything with yet.
     *
     * <p>Not a submission at all, strictly — but it is the row a translator looks for first, and
     * giving it the same shape as the rest is what lets it sit at the top of the same list instead
     * of needing a second widget. It carries no timestamp because it has not happened yet.</p>
     *
     * <p>Always present, empty or not: it is where "what am I working on" lives, and a first row
     * that comes and goes is harder to read than one that always reports. Empty, it says so — and
     * Submit is what disappears instead, since that is the thing with nothing to do.</p>
     */
    public static TranslationSubmission unsubmitted(String locale, int units) {
        return new TranslationSubmission(0L, locale, "", units, 0, 0, 0, 0, false, true);
    }

    /**
     * ISO date rather than a localized one: it is unambiguous everywhere, and this list is read by
     * people whose whole reason for being here is that the localization is not trustworthy yet.
     */
    public String date() {
        if (unsubmitted) {
            return ""; // it has not been sent, so there is no date to tell — the row says so instead
        }
        try {
            return DATE.format(Instant.ofEpochMilli(submittedAtMs).atZone(ZoneId.systemDefault()));
        } catch (Exception e) {
            return "";
        }
    }

    /** The credited name, or the "anonymous" placeholder when they declined credit. */
    public Component creditLine() {
        if (unsubmitted) {
            // Nothing has been credited to anybody yet — who gets the credit is chosen at Submit.
            return Component.empty();
        }
        return translator == null || translator.isBlank()
            ? Component.translatable("gui.dungeontrain.translate.sent.anonymous")
            : Component.literal(translator);
    }

    /**
     * Where this submission got to, in one line.
     *
     * <p>Partial outcomes are reported as a fraction rather than collapsed to a single word:
     * approval is per string, so "3/12 accepted" is a real and common state, and rounding it to
     * either "accepted" or "waiting" would be a lie in one direction or the other.</p>
     */
    public Component statusLine() {
        if (unsubmitted) {
            return units > 0
                ? Component.translatable("gui.dungeontrain.translate.sent.unsubmitted")
                : Component.translatable("gui.dungeontrain.translate.sent.nothing_to_send");
        }
        if (queued) {
            return Component.translatable("gui.dungeontrain.translate.sent.queued");
        }
        if (approved >= units && units > 0) {
            return Component.translatable("gui.dungeontrain.translate.sent.accepted");
        }
        if (approved > 0) {
            return Component.translatable("gui.dungeontrain.translate.sent.partial", approved, units);
        }
        if (rejected >= units && units > 0) {
            return Component.translatable("gui.dungeontrain.translate.sent.rejected");
        }
        if (flagged > 0) {
            return Component.translatable("gui.dungeontrain.translate.sent.held");
        }
        return Component.translatable("gui.dungeontrain.translate.sent.waiting");
    }

    /** Green once anything has been accepted, amber while it waits, red when nothing was used. */
    public ChatFormatting statusColour() {
        if (unsubmitted) {
            // Deliberately not amber: nothing here is waiting on anyone else, and colouring it like
            // work under review would put the translator's own drafts in the reviewer's queue.
            return units > 0 ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY;
        }
        if (queued) {
            return ChatFormatting.GRAY;
        }
        if (approved > 0) {
            return ChatFormatting.GREEN;
        }
        if (rejected >= units && units > 0) {
            return ChatFormatting.RED;
        }
        return ChatFormatting.GOLD;
    }
}
