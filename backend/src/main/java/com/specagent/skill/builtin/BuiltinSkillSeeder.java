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
 * 文件名:BuiltinSkillSeeder.java
 *
 * 用途:把随应用一起打包内置的 Skill 包注册进系统(应用启动时自动播种)。
 *
 * "内置"只改变字节来源,不改变流程:每个包都走与上传 ZIP 完全相同的
 * 暂存、安装、启用管线。因此全系统只有一条安装路径,内置包不可能绕过
 * 上传包必须满足的任何包规则。
 *
 * 两条对可测试性很重要的约束:
 * - 【幂等】 —— 源身份已处于启用状态的包直接跳过,重启不会累积暂存
 *       记录或新版本;
 * - 【绝不致命】 —— 损坏的包只记录日志并跳过。内置内容不允许阻止应用
 *       启动,格式错误的内置包退化为"缺少那一个 skill"。 *
 * 由 {@code spec.agent.skill.builtin.seed-enabled} 开关控制(默认 true);
 * test profile 会关闭它,因为 skill 集成测试需要断言目录的精确状态。
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

    /** 包文件按这些扩展名视为可搜索文本,否则视为二进制。 */
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
     * 安装索引中列出的所有包,返回本次新启用的包名(已启用的包不计入)。
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
        // 与上传包完全相同的 暂存 -> 安装 -> 启用 路径。
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
     * 读取显式维护的索引文件,保持顺序并按包分组。
     *
     * 索引用显式清单而不是运行时扫描 classpath 目录:打包成 jar 后目录枚举
     * 依赖具体的 URL 协议,显式列表能让 dev 与 jar 环境走同一条代码路径。
     *
     * 包级可见(便于单测直接验证分组):它是 {@link #install} 的唯一输入,
     * 一旦分组错误,某个包会被静默漏装而不是大声失败。
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
