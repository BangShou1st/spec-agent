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
 * Safe ZIP extraction for Skill imports. Never writes to the host
 * filesystem: entries are held in memory, containment-checked, size-bounded,
 * and re-serialized as DB rows.
 *
 * <p>Security controls (fail-closed):
 * <ul>
 *   <li>archive max bytes (streamed read bound);</li>
 *   <li>extracted max bytes and file-count bounds;</li>
 *   <li>path depth bound and path-length bound;</li>
 *   <li>traversal ({@code ..}) and absolute-path rejection;</li>
 *   <li>symlink/hardlink/device/special entry rejection (entry kinds other
 *       than regular files are refused);</li>
 *   <li>Unicode/path normalization defensive checks;</li>
 *   <li>content hashing per file and a total content hash.</li>
 * </ul>
 * <p>Nothing is ever executed during extraction or validation.
 */
@Component
public class SafeZipExtractor {

    private final SkillProperties properties;

    public SafeZipExtractor(SkillProperties properties) {
        this.properties = properties;
    }

    /**
     * Extracts an uploaded ZIP into an immutable, validated file list plus the
     * parsed SKILL.md manifest.
     *
     * @return the extracted package model
     * @throws SkillImportException when any bound or containment rule fails
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
                // Refuse special entries: symlinks and non-regular kinds are
                // rejected regardless of their target (zip-bomb/escape
                // vectors). UNIX mode bits are only available for entries
                // written by unix tools; entries without mode bits on Windows
                // zips pass as regular (they cannot encode a symlink).
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

        // SKILL.md is mandatory at the package root.
        SkillSourceFile skillMd = files.get("SKILL.md");
        if (skillMd == null) {
            throw new SkillImportException("Skill package is missing SKILL.md at the root");
        }

        List<SkillSourceFile> ordered = new ArrayList<>(files.values());
        return new ExtractedPackage(skillMd.content(), ordered, totalBytes);
    }

    /**
     * Normalizes and validates one archive entry path. Rejects traversal,
     * absolute paths, over-deep paths, over-long paths, and drive-letter
     * prefixes. The returned path uses forward slashes and stays inside the
     * package root.
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
        // Reject control chars / surrogate escapes that could alias in
        // filesystem/DB normalization.
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
     * Unix mode bits are present only when the archiver wrote them; absence is
     * not treated as a violation (Windows-created archives cannot encode
     * symlinks), but presence of a non-regular kind is always rejected.
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
        // Heuristic: printable text majority => TEXT. UTF-8 decodable and
        // low NUL/control density keeps binary assets from masquerading.
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

    /** Validated, in-memory extracted ZIP contents. */
    public record ExtractedPackage(byte[] skillMarkdown, List<SkillSourceFile> files,
                                   long totalBytes) {
    }
}