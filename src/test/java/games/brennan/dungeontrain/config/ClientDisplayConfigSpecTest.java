package games.brennan.dungeontrain.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against a config-correction loop in the CLIENT spec.
 *
 * <p>The failure this exists to catch: FML corrects a config it considers incorrect, writes the
 * file, its own file watcher sees the write and re-loads, and the spec judges the just-corrected
 * result incorrect <i>again</i> — so the file is rewritten every couple of seconds forever, and any
 * value set at runtime is reset to its default on the next pass. It is invisible except as a WARN
 * line ("is not correct. Correcting") repeating in the log.</p>
 *
 * <p>The invariant is simply: correcting a config must reach a fixed point. Correct a fresh config
 * once, and the result must be correct.</p>
 *
 * <p><b>Written after chasing exactly that log signature to a different cause</b>, which is worth
 * recording because the next person will see the same WARN and reach for the same suspicion: the
 * loop was <i>two dev clients sharing one {@code run/config} directory</i>. A client built before a
 * new config key existed kept stripping that key back out of the file, and the newer client kept
 * putting it back — two seconds apart, forever, with the new setting silently reverting to its
 * default in between. If you are staring at that WARN, check {@code ps} for a second client before
 * suspecting the spec. This test rules the spec in or out in a second, which is the point.</p>
 */
final class ClientDisplayConfigSpecTest {

    @Test
    @DisplayName("client spec: correcting an empty config reaches a fixed point (no correction loop)")
    void correctionIsStable() {
        ModConfigSpec spec = ClientDisplayConfig.SPEC;

        CommentedConfig config = CommentedConfig.inMemory();
        spec.correct(config);

        List<String> offenders = incorrectPaths(spec, config);
        assertTrue(offenders.isEmpty(),
                "spec still reports these paths incorrect immediately after correcting them, so FML "
                        + "will correct → write → re-load → correct forever: " + offenders);
    }

    @Test
    @DisplayName("client spec: a corrected config stays correct after a second correction pass")
    void secondPassIsANoOp() {
        ModConfigSpec spec = ClientDisplayConfig.SPEC;

        CommentedConfig config = CommentedConfig.inMemory();
        spec.correct(config);
        spec.correct(config);

        assertTrue(spec.isCorrect(config),
                "a twice-corrected config is still judged incorrect — the spec never settles, "
                        + "which is the correction loop: " + incorrectPaths(spec, config));
    }

    /**
     * Walk the spec's own value tree and report every path whose stored value the spec rejects.
     * {@code isCorrect} alone only says yes/no; the loop is impossible to diagnose from a log
     * without the offending path, which is the whole reason this walks the tree.
     */
    private static List<String> incorrectPaths(ModConfigSpec spec, CommentedConfig config) {
        List<String> offenders = new ArrayList<>();
        collect(spec.getSpec(), config, new ArrayList<>(), offenders);
        return offenders;
    }

    private static void collect(UnmodifiableConfig specNode, Config configNode, List<String> path, List<String> out) {
        for (UnmodifiableConfig.Entry entry : specNode.entrySet()) {
            List<String> here = new ArrayList<>(path);
            here.add(entry.getKey());
            Object specValue = entry.getRawValue();
            if (specValue instanceof UnmodifiableConfig child) {
                Object sub = configNode == null ? null : configNode.getRaw(entry.getKey());
                collect(child, sub instanceof Config c ? c : null, here, out);
            } else if (specValue instanceof ModConfigSpec.ValueSpec valueSpec) {
                Object actual = configNode == null ? null : configNode.getRaw(entry.getKey());
                if (!valueSpec.test(actual)) {
                    out.add(String.join(".", here) + " = " + actual
                            + " (" + (actual == null ? "null" : actual.getClass().getSimpleName()) + ")");
                }
            }
        }
    }
}
