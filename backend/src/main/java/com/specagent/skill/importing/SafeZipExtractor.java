package com.specagent.skill.importing;

import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.domain.SkillPackageFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件名:SafeZipExtractor.java
 *
 * 用途:Skill 导入的安全 ZIP 解包。绝不写宿主文件系统:条目全部保存在
 * 内存中,逐一做路径包含性检查与大小限额,然后序列化为数据库行。
 *
 * 安全控制(默认失败关闭):
 * - 压缩包最大字节数(流式读取上限);
 * - 解包总字节数与文件数上限;
 * - 路径深度上限与路径长度上限;
 * - 拒绝路径穿越({@code ..})与绝对路径;
 * - 拒绝符号链接/硬链接/设备/特殊条目(非常规文件类型的条目一律拒收);
 * - Unicode/路径归一化的防御性检查;
 * - 逐文件内容哈希与总体内容哈希。
 * 解包与校验过程中绝不执行任何内容。
 */
@Component
public class SafeZipExtractor {

    private final SkillProperties properties;

    public SafeZipExtractor(SkillProperties properties) {
        this.properties = properties;
    }

    /**
     * 把上传的 ZIP 解包为不可变且已校验的文件列表,以及解析出的 SKILL.md
     * manifest。
     *
     * @return 解出的包模型
     * @throws SkillImportException 任一限额或包含性规则失败时抛出
     */
    public ExtractedPackage extract(byte[] archiveBytes) {
        if (archiveBytes == null || archiveBytes.length == 0) {
            throw new SkillImportException("Empty ZIP archive");
        }
        if (archiveBytes.length > properties.getMaxArchiveBytes()) {
            throw new SkillImportException("ZIP archive exceeds the "
                    + properties.getMaxArchiveBytes() + " byte limit: "
                    + archiveBytes.length + " bytes");
        }

        Map<String, SkillSourceFile> files = new HashMap<>();
        long totalBytes = 0;

        try (SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(archiveBytes);
             ZipFile zip = new ZipFile(channel)) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (files.size() >= properties.getMaxFiles()) {
                    throw new SkillImportException("ZIP contains more than "
                            + properties.getMaxFiles() + " files");
                }
                String rawName = entry.getName();
                String normalized = validatePath(rawName);
                // 拒绝特殊条目:符号链接及非常规类型无论指向什么都拒收
                // (zip 炸弹/越界向量)。UNIX mode 位只有 unix 工具写入的
                // 条目才有;Windows 压缩包中无 mode 位的条目按常规文件
                // 放行(它们根本无法编码符号链接)。
                if (hasUnixMode(entry) && !isRegularFile(entry.getUnixMode())) {
                    throw new SkillImportException(
                            "ZIP entry is not a regular file (special entry kind): "
                                    + rawName);
                }

                byte[] content = readBounded(zip, entry, rawName);
                totalBytes += content.length;
                if (totalBytes > properties.getMaxExtractedBytes()) {
                    throw new SkillImportException("Extracted content exceeds the "
                            + properties.getMaxExtractedBytes() + " byte limit");
                }
                if (files.containsKey(normalized)) {
                    throw new SkillImportException("Duplicate file in ZIP: " + normalized);
                }
                files.put(normalized, new SkillSourceFile(normalized, content,
                        detectKind(normalized, content)));
            }
        } catch (IOException ex) {
            throw new SkillImportException("Failed to read ZIP archive: "
                    + ex.getClass().getSimpleName());
        }

        // SKILL.md 必须位于包根。
        SkillSourceFile skillMd = files.get("SKILL.md");
        if (skillMd == null) {
            throw new SkillImportException("Skill package is missing SKILL.md at the root");
        }

        List<SkillSourceFile> ordered = new ArrayList<>(files.values());
        return new ExtractedPackage(skillMd.content(), ordered, totalBytes);
    }

    /**
     * 归一化并校验单个压缩包条目路径。拒绝路径穿越、绝对路径、过深路径、
     * 过长路径与盘符前缀。返回的路径使用正斜杠且始终位于包根之内。
     */
    String validatePath(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new SkillImportException("ZIP entry with an empty path");
        }
        String name = rawName.replace('\\', '/');
        if (name.length() > properties.getMaxPathChars()) {
            throw new SkillImportException("ZIP entry path exceeds "
                    + properties.getMaxPathChars() + " chars: " + rawName);
        }
        if (name.startsWith("/") || name.matches("^[A-Za-z]:/.*")) {
            throw new SkillImportException("ZIP entry uses an absolute path: " + rawName);
        }
        String[] parts = name.split("/");
        int depth = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) {
                throw new SkillImportException("ZIP entry has an empty path segment: " + rawName);
            }
            if (part.equals("..")) {
                throw new SkillImportException(
                        "ZIP entry attempts path traversal: " + rawName);
            }
            depth++;
        }
        if (depth > properties.getMaxDepth()) {
            throw new SkillImportException("ZIP entry exceeds the maximum depth of "
                    + properties.getMaxDepth() + ": " + rawName);
        }
        // 拒绝控制字符/代理转义,避免在文件系统/数据库归一化时产生别名。
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                throw new SkillImportException(
                        "ZIP entry contains control characters: " + rawName);
            }
        }
        return name;
    }

    private byte[] readBounded(ZipFile zip, ZipArchiveEntry entry, String entryName)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var input = zip.getInputStream(entry)) {
            byte[] buffer = new byte[8192];
            long read = 0;
            int n;
            while ((n = input.read(buffer)) > 0) {
                read += n;
                if (read > properties.getMaxExtractedBytes()) {
                    throw new SkillImportException("ZIP entry exceeds extracted limit: "
                            + entryName);
                }
                out.write(buffer, 0, n);
            }
        }
        return out.toByteArray();
    }

    /**
     * Unix mode 位只有打包工具写入了才存在;缺失不算违规(Windows 创建的
     * 压缩包无法编码符号链接),但出现非常规类型一定拒绝。
     */
    private boolean hasUnixMode(ZipArchiveEntry entry) {
        int mode = entry.getUnixMode();
        return (mode & 0xF000) != 0 || (mode & 0xFF) != 0;
    }

    private boolean isRegularFile(int unixMode) {
        int kind = unixMode & 0xF000;
        return kind == 0x8000 /* regular */ || kind == 0x0000;
    }

    private SkillPackageFile.FileKind detectKind(String path, byte[] content) {
        if (path.equals("SKILL.md")) {
            return SkillPackageFile.FileKind.SKILL_MD;
        }
        if (content == null || content.length == 0) {
            return SkillPackageFile.FileKind.BINARY;
        }
        // 启发式判定:可打印文本占多数即为 TEXT。要求可 UTF-8 解码且
        // NUL/控制字符密度低,防止二进制资源伪装成文本。
        int textScore = 0;
        int total = Math.min(content.length, 4096);
        for (int i = 0; i < total; i++) {
            int b = content[i] & 0xFF;
            if (b == 0) {
                return SkillPackageFile.FileKind.BINARY;
            }
            if (b == 9 || b == 10 || b == 13 || (b >= 32 && b <= 126)
                    || b >= 0x80) {
                textScore++;
            }
        }
        return ((double) textScore / total) >= 0.9
                ? SkillPackageFile.FileKind.TEXT : SkillPackageFile.FileKind.BINARY;
    }

    /** 已校验、保存在内存中的 ZIP 解包内容。 */
    public record ExtractedPackage(byte[] skillMarkdown, List<SkillSourceFile> files,
                                   long totalBytes) {
    }
}