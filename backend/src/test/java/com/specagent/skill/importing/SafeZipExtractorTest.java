package com.specagent.skill.importing;

import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.domain.SkillPackageFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ZIP import security: traversal, absolute paths, special entries, depth and
 * size bounds — every rule must fail closed before any install.
 */
class SafeZipExtractorTest {

    private SkillProperties properties;
    private SafeZipExtractor extractor;

    private static final String SKILL_MD = """
            ---
            name: safe-skill
            description: safe extraction test
            ---
            instructions
            """;

    @BeforeEach
    void setUp() {
        properties = SkillProperties.defaults();
        extractor = new SafeZipExtractor(properties);
    }

    @Test
    void extractsValidZippedSkill() {
        byte[] archive = zip(
                entry("SKILL.md", SKILL_MD),
                entry("references/guide.md", "# Guide content\n"));

        SafeZipExtractor.ExtractedPackage extracted = extractor.extract(archive);

        assertThat(extracted.files()).hasSize(2);
        assertThat(extracted.files()).extracting(SkillSourceFile::relativePath)
                .contains("SKILL.md", "references/guide.md");
        assertThat(new String(extracted.skillMarkdown(), StandardCharsets.UTF_8))
                .contains("name: safe-skill");
        SkillSourceFile guide = extracted.files().stream()
                .filter(f -> f.relativePath().equals("references/guide.md"))
                .findFirst().orElseThrow();
        assertThat(guide.kind()).isEqualTo(SkillPackageFile.FileKind.TEXT);
    }

    @Test
    void missingSkillMdFailsClosed() {
        byte[] archive = zip(entry("readme.txt", "not a skill"));
        assertThatThrownBy(() -> extractor.extract(archive))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    void traversalEntryIsRejected() {
        byte[] archive = zip(
                entry("SKILL.md", SKILL_MD),
                entry("../escape.sh", "rm -rf /"));
        assertThatThrownBy(() -> extractor.extract(archive))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    void nestedTraversalIsRejected() {
        byte[] archive = zip(
                entry("SKILL.md", SKILL_MD),
                entry("references/../../outside", "x"));
        assertThatThrownBy(() -> extractor.extract(archive))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    void absolutePathIsRejected() {
        byte[] archive = zip(
                entry("SKILL.md", SKILL_MD),
                entry("/etc/passwd", "root:x"));
        assertThatThrownBy(() -> extractor.extract(archive))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void windowsDrivePathIsRejected() {
        byte[] archive = zip(
                entry("SKILL.md", SKILL_MD),
                entry("C:/Windows/system32/x", "x"));
        assertThatThrownBy(() -> extractor.extract(archive))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void backslashNormalizedTraversalIsRejected() {
        byte[] archive = zip(
                entry("SKILL.md", SKILL_MD),
                entry("..\\..\\evil", "x"));
        assertThatThrownBy(() -> extractor.extract(archive))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    void oversizeArchiveIsRejected() {
        properties.setMaxArchiveBytes(100);
        byte[] archive = zip(entry("SKILL.md", SKILL_MD), entry("big.txt", "padding padding"));
        assertThatThrownBy(() -> extractor.extract(archive))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("byte limit");
    }

    @Test
    void tooManyFilesIsRejected() {
        properties.setMaxFiles(2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            for (int i = 0; i < 4; i++) {
                ZipArchiveEntry e = new ZipArchiveEntry("file" + i + ".txt");
                byte[] data = "x".getBytes(StandardCharsets.UTF_8);
                CRC32 crc = new CRC32();
                crc.update(data);
                e.setSize(data.length);
                e.setCrc(crc.getValue());
                zip.putArchiveEntry(e);
                zip.write(data);
                zip.closeArchiveEntry();
            }
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("test zip write failed", ex);
        }
        byte[] archive = out.toByteArray();
        assertThatThrownBy(() -> extractor.extract(archive))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("more than");
    }

    @Test
    void excessiveDepthIsRejected() {
        properties.setMaxDepth(2);
        byte[] archive = zip(
                entry("SKILL.md", SKILL_MD),
                entry("a/b/c/d.txt", "deep"));
        assertThatThrownBy(() -> extractor.extract(archive))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("depth");
    }

    @Test
    void symlinkEntryKindIsRejected() {
        // Unix symlink entry: mode bits 0xA1FF (symlink + perms).
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            ZipArchiveEntry md = new ZipArchiveEntry("SKILL.md");
            byte[] mdData = SKILL_MD.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(mdData);
            md.setSize(mdData.length);
            md.setCrc(crc.getValue());
            zip.putArchiveEntry(md);
            zip.write(mdData);
            zip.closeArchiveEntry();

            ZipArchiveEntry link = new ZipArchiveEntry("references/evil-link");
            byte[] target = "/etc/passwd".getBytes(StandardCharsets.UTF_8);
            CRC32 linkCrc = new CRC32();
            linkCrc.update(target);
            link.setSize(target.length);
            link.setCrc(linkCrc.getValue());
            link.setUnixMode(0xA1FF); // S_IFLNK | rwxr-xr-x
            zip.putArchiveEntry(link);
            zip.write(target);
            zip.closeArchiveEntry();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("test zip write failed", ex);
        }
        byte[] archive = out.toByteArray();
        assertThatThrownBy(() -> extractor.extract(archive))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("not a regular file");
    }

    @Test
    void duplicatePathIsRejected() {
        byte[] archive = zip(
                entry("SKILL.md", SKILL_MD),
                entry("a.txt", "first"),
                entry("a.txt", "second"));
        assertThatThrownBy(() -> extractor.extract(archive))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("Duplicate file");
    }

    @Test
    void contentKindsAreClassified() {
        byte[] archive = zip(
                entry("SKILL.md", SKILL_MD),
                entry("assets/logo.png", new byte[]{0x00, 0x01, 0x02, (byte) 0xFF}),
                entry("scripts/run.sh", "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8)));
        SafeZipExtractor.ExtractedPackage extracted = extractor.extract(archive);

        assertThat(extracted.files()).filteredOn(SkillSourceFile::relativePath,
                "assets/logo.png").singleElement()
                .extracting(SkillSourceFile::kind)
                .isEqualTo(SkillPackageFile.FileKind.BINARY);
        assertThat(extracted.files()).filteredOn(SkillSourceFile::relativePath,
                "scripts/run.sh").singleElement()
                .extracting(SkillSourceFile::kind)
                .isEqualTo(SkillPackageFile.FileKind.TEXT);
    }

    private byte[] zip(EntrySpec... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            for (EntrySpec spec : entries) {
                byte[] data = spec.content();
                CRC32 crc = new CRC32();
                crc.update(data);
                ZipArchiveEntry entry = new ZipArchiveEntry(spec.path());
                entry.setSize(data.length);
                entry.setCrc(crc.getValue());
                zip.putArchiveEntry(entry);
                zip.write(data);
                zip.closeArchiveEntry();
            }
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("test zip write failed", ex);
        }
        return out.toByteArray();
    }

    private EntrySpec entry(String path, String content) {
        return new EntrySpec(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private EntrySpec entry(String path, byte[] content) {
        return new EntrySpec(path, content);
    }

    private record EntrySpec(String path, byte[] content) {
    }
}