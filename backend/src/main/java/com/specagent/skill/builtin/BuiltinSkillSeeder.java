package com.specagent.skill.builtin;

import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillPackageFile;
import com.specagent.skill.domain.SkillSourceKind;
import com.specagent.skill.importing.SkillSourceFile;
import com.specagent.skill.persistence.SkillRepository;
import com.specagent.skill.registry.SkillImportService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Registers the Skill packages that ship inside the application.
 *
 * <p>Built-in only changes <em>where the bytes come from</em>: every package
 * goes through the same staging, install and enable pipeline as an uploaded
 * ZIP. There is therefore exactly one install path, and a built-in package can
 * never bypass a package rule that an uploaded one has to satisfy.
 *
 * <p>Two properties matter for testability:
 * <ul>
 *   <li><b>Idempotent</b> — a package whose source identity is already enabled
 *       is skipped, so restarts do not accumulate staged rows or versions;</li>
 *   <li><b>Never fatal</b> — a broken package is logged and skipped. Shipping
 *       content must not be able to stop the application from starting, so a
 *       malformed built-in degrades to "that one skill is missing".</li>
 * </ul>
 *
 * <p>Enabled by {@code spec.agent.skill.builtin.seed-enabled} (default true);
 * the test profile turns it off because the skill integration tests assert
 * exact catalog states.
 */
@Component
@ConditionalOnProperty(name = "spec.agent.skill.builtin.seed-enabled",
        havingValue = "true", matchIfMissing = true)
public class BuiltinSkillSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BuiltinSkillSeeder.class);

    private static final String ROOT = "builtin-skills/";
    private static final String INDEX = ROOT + "index.txt";
    private static final String MANIFEST = "SKILL.md";
    private static final String IDENTITY_PREFIX = "builtin:";

    /** Extensions whose package files are searchable text rather than a blob. */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".md", ".markdown", ".txt", ".json", ".yaml", ".yml", ".csv");

    private final SkillImportService importService;
    private final SkillRepository repository;

    public BuiltinSkillSeeder(SkillImportService importService, SkillRepository repository) {
        this.importService = importService;
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> seeded = seed();
            if (!seeded.isEmpty()) {
                LOG.info("Installed built-in skills: {}", seeded);
            }
        } catch (RuntimeException ex) {
            LOG.warn("Built-in skill seeding skipped: {}", ex.getMessage());
        }
    }

    /**
     * Installs every package listed in the index and returns the names that
     * were newly enabled (already-enabled packages are not reported).
     */
    public List<String> seed() {
        List<String> newlyEnabled = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : readIndex().entrySet()) {
            String packageName = entry.getKey();
            if (alreadyEnabled(packageName)) {
                continue;
            }
            try {
                install(packageName, entry.getValue());
                newlyEnabled.add(packageName);
            } catch (RuntimeException ex) {
                LOG.warn("Built-in skill '{}' was not installed: {}", packageName, ex.getMessage());
            }
        }
        return newlyEnabled;
    }

    private void install(String packageName, List<String> packagePaths) {
        String markdown = new String(readBytes(ROOT + packageName + "/" + MANIFEST),
                StandardCharsets.UTF_8);
        List<SkillSourceFile> files = new ArrayList<>();
        long totalBytes = 0;
        for (String path : packagePaths) {
            String relative = path.substring(packageName.length() + 1);
            byte[] content = readBytes(ROOT + path);
            totalBytes += content.length;
            files.add(new SkillSourceFile(relative, content, kindOf(relative)));
        }
        // Same staging -> install -> enable path an uploaded package takes.
        SkillImportService.StagedResult staged =
                importService.stageBuiltin(packageName, markdown, files, totalBytes);
        SkillImportService.InstalledResult installed =
                importService.install(staged.stagedImportId());
        importService.enable(installed.skillRowId());
    }

    private boolean alreadyEnabled(String packageName) {
        String identity = IDENTITY_PREFIX + packageName;
        return repository.listSkills().stream().anyMatch((Skill skill) -> skill.enabled()
                && skill.sourceKind() == SkillSourceKind.BUILTIN
                && identity.equals(skill.sourceIdentity()));
    }

    /**
     * Reads the explicit index, preserving order and grouping by package.
     *
     * <p>The index is explicit rather than a runtime classpath directory scan:
     * once packaged into a jar, directory enumeration depends on the concrete
     * URL protocol, so an explicit list keeps dev and jar on one code path.
     *
     * <p>Package-private so the grouping can be unit-tested directly: it is the
     * only input to {@link #install}, and a wrong grouping would make a package
     * silently unseeded rather than fail loudly.
     */
    Map<String, List<String>> readIndex() {
        String text = new String(readBytes(INDEX), StandardCharsets.UTF_8);
        Map<String, List<String>> packages = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int slash = line.indexOf('/');
            if (slash <= 0 || slash == line.length() - 1) {
                LOG.warn("Ignoring malformed built-in skill index entry: {}", line);
                continue;
            }
            packages.computeIfAbsent(line.substring(0, slash), (key) -> new ArrayList<>()).add(line);
        }
        return packages;
    }

    private static SkillPackageFile.FileKind kindOf(String relativePath) {
        String lower = relativePath.toLowerCase();
        for (String extension : TEXT_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return SkillPackageFile.FileKind.TEXT;
            }
        }
        return SkillPackageFile.FileKind.BINARY;
    }

    private static byte[] readBytes(String classpathLocation) {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("Missing built-in skill resource: " + classpathLocation);
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to read built-in skill resource: " + classpathLocation, ex);
        }
    }
}
