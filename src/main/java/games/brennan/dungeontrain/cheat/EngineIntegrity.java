package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.DtModVerifyBridge;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Is the file-verification engine the one DT embeds, and does nothing else on disk pose as DT, the
 * engine or the loader? Every file carrying one of those ids is exempt from hashing, so a top-level
 * jar declaring {@code dtmodverify} would both REPLACE DT's nested engine (NeoForge drops a jar-in-jar
 * mod a top-level jar also provides) and escape the hash check — answering VERIFIED for everything.
 *
 * <p>The rule: the engine's outermost file must be DT's own jar, its SHA-1 and version must be the
 * ones the build baked ({@code engineSha1}/{@code engineVersion} in {@code approved_mods.json}), and
 * no other outermost file may declare {@code dungeontrain}, {@code dtmodverify} or {@code forge}. DT
 * hashes exactly one file here — the engine jar it shipped — never the rest of the mods folder.</p>
 *
 * <p>Judged once per JVM (the mod list does not change after loading). Without the engine there is
 * nothing to check and the answer is clean: the id-only fallback is unchanged. In a dev run
 * ({@code !FMLEnvironment.production}) the engine sits on the classpath, not inside DT's jar, so the
 * check is skipped.</p>
 */
public final class EngineIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ids no file but DT's own jar (and, for the loader, NeoForge's) may declare. */
    static final Set<String> GUARDED_IDS = Set.of(DungeonTrain.MOD_ID, DtModVerifyBridge.MOD_ID, "forge");

    /** One installed mod file as the loader sees it: its outermost file on disk and the ids it declares. */
    record FileClaim(Path outermost, Set<String> modIds) {}

    private static volatile List<String> judged;

    private EngineIntegrity() {}

    /** The tamper findings as display strings, e.g. {@code "x.jar (dtmodverify replaced)"}; empty when clean. */
    public static List<String> tampered() {
        List<String> out = judged;
        if (out != null) return out;
        synchronized (EngineIntegrity.class) {
            if (judged == null) judged = judgeLive();
            return judged;
        }
    }

    private static List<String> judgeLive() {
        if (!DtModVerifyBridge.isPresent()) return List.of();
        if (!FMLEnvironment.production) {
            LOGGER.info("[DungeonTrain] Dev run: the verification engine is not embedded, its origin is not checked");
            return List.of();
        }
        try {
            ModList mods = ModList.get();
            Path dtPath = outermost(mods.getModFileById(DungeonTrain.MOD_ID).getFile()).getFilePath();
            IModFile engine = mods.getModFileById(DtModVerifyBridge.MOD_ID).getFile();
            Path engineOutermost = outermost(engine).getFilePath();
            String engineSha1 = sha1(engine.getFilePath());
            List<FileClaim> claims = new ArrayList<>();
            for (var info : mods.getModFiles()) {
                Set<String> ids = new TreeSet<>();
                for (IModInfo m : info.getMods()) ids.add(m.getModId());
                claims.add(new FileClaim(outermost(info.getFile()).getFilePath(), ids));
            }
            List<String> found = tamperedFrom(dtPath, engineOutermost, engineSha1, ApprovedModList.bakedEngineSha1(),
                DtModVerifyBridge.version(), ApprovedModList.bakedEngineVersion(), claims);
            if (found.isEmpty()) {
                LOGGER.info("[DungeonTrain] Verification engine v{} is DT's embedded build", DtModVerifyBridge.version());
            } else {
                LOGGER.warn("[DungeonTrain] Verification engine or DT files were tampered with — the hash check "
                    + "cannot be trusted this launch: {}", String.join(", ", found));
            }
            return found;
        } catch (Throwable t) {
            // The engine is present but its origin cannot be shown: that is not DT's embedded engine.
            LOGGER.warn("[DungeonTrain] Could not confirm the verification engine is DT's own: {}", t.toString());
            return List.of("dtmodverify (origin unknown)");
        }
    }

    /**
     * Pure: the tamper findings, sorted. A blank baked SHA-1/version skips that comparison (a build
     * without the engine baked). Package-visible for unit tests — no live mod list.
     */
    static List<String> tamperedFrom(Path dtPath, Path engineOutermost, String engineSha1, String bakedSha1,
                                     String engineVersion, String bakedVersion, Collection<FileClaim> claims) {
        Map<String, Boolean> found = new LinkedHashMap<>();
        String engineName = name(engineOutermost);
        if (!same(dtPath, engineOutermost)) {
            found.put(engineName + " (dtmodverify replaced)", true);
        } else {
            if (!blank(bakedSha1) && !bakedSha1.equalsIgnoreCase(engineSha1 == null ? "" : engineSha1)) {
                found.put("dtmodverify (engine modified)", true);
            }
            if (!blank(bakedVersion) && !bakedVersion.equals(engineVersion)) {
                found.put("dtmodverify v" + engineVersion + " (engine replaced)", true);
            }
        }
        for (FileClaim c : claims) {
            if (same(dtPath, c.outermost())) continue;
            boolean loader = c.modIds().contains("neoforge");
            for (String id : c.modIds()) {
                String norm = id == null ? "" : id.toLowerCase(Locale.ROOT);
                if (!GUARDED_IDS.contains(norm) || (loader && "forge".equals(norm))) continue;
                String what = DtModVerifyBridge.MOD_ID.equals(norm) ? " (dtmodverify replaced)" : " (claims " + norm + ")";
                found.put(name(c.outermost()) + what, true);
            }
        }
        List<String> out = new ArrayList<>(found.keySet());
        out.sort(String::compareTo);
        return List.copyOf(out);
    }

    static IModFile outermost(IModFile f) {
        IModFile cur = f;
        for (int i = 0; i < 16; i++) {
            IModFile parent = cur.getDiscoveryAttributes().parent();
            if (parent == null) return cur;
            cur = parent;
        }
        return cur;
    }

    static String sha1(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static boolean same(Path a, Path b) {
        if (a == null || b == null) return false;
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    private static String name(Path p) {
        return p == null || p.getFileName() == null ? "?" : p.getFileName().toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
