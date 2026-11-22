package games.brennan.dungeontrain.mixin.client;

import games.brennan.discordpresence.client.SurveyScreen;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import games.brennan.dungeontrain.client.BugLogReporter;
import games.brennan.dungeontrain.client.perf.LagReportFormHandoff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Opens Discord Presence's bug form on Lag when a lag prompt's [Yes with info] asked for it
 * ({@link LagReportFormHandoff}): after the bug-report question lays out, picks its "Lag" option the
 * way a click would, so the player only has to type their note and Submit.
 *
 * <p>Found by label, not index, so a reordered {@code bug_report.json} still lands on Lag. One-shot: a
 * resize re-runs {@code init()} and DP's own state applies from then on; a manual {@code /bug} opens
 * as it always has.</p>
 */
@Mixin(SurveyScreen.class)
public abstract class SurveyScreenLagPreselectMixin {

    @Shadow
    private SurveyQuestionPayload.Entry current() {
        throw new AssertionError();
    }

    @Shadow
    private void selectScore(int value) {
        throw new AssertionError();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void dungeontrain$preselectLag(CallbackInfo ci) {
        SurveyQuestionPayload.Entry q = current();
        if (q == null || !BugLogReporter.BUG_REPORT_ID.equals(q.id())) return;
        int lag = BugLogReporter.lagOptionIndex(q.options());
        if (lag < 0 || !LagReportFormHandoff.takePreselect(System.currentTimeMillis())) return;
        selectScore(q.scaleMin() + lag);
    }
}
