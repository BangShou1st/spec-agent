package com.specagent.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Append-only first-run ledger. A partial claim/receipt also permanently consumes the run. */
public final class RetrievalExecutionClaimManager {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Set<String> TERMINAL = Set.of("COMPLETED_PASS", "COMPLETED_FAIL", "INTERRUPTED_OR_ERROR");
    public record ExecutionClaim(String runId, String datasetSha256, String freezeSha256,
            String claimedAt, String status, String outcomeDetails, String evaluationFamilyId) {}
    public static class ClaimCollisionException extends IllegalStateException {
        public ClaimCollisionException(String message) { super(message); }
    }
    private RetrievalExecutionClaimManager() {}
    public static ExecutionClaim claimFirstRun(String datasetSha, String freezeSha, Path directory) {
        return claimFirstRun(datasetSha, datasetSha, freezeSha, directory);
    }
    public static ExecutionClaim claimFirstRun(String family, String datasetSha, String freezeSha, Path directory) {
        safe(family); safe(datasetSha); safe(freezeSha);
        Objects.requireNonNull(directory);
        var claim = new ExecutionClaim(UUID.randomUUID().toString(), datasetSha, freezeSha,
                Instant.now().toString(), "CLAIMED", "First run consumed, including interruption", family);
        try {
            Files.createDirectories(directory);
            if (family.startsWith("rag-d3-")) {
                try (var files = Files.list(directory)) {
                    if (files.anyMatch(path -> path.getFileName().toString().endsWith(".claim.json")))
                        throw new ClaimCollisionException("Existing candidate execution history requires reconciliation");
                }
            }
            if (Files.exists(directory.resolve(".claim.json")))
                throw new ClaimCollisionException("Legacy execution claim exists; history requires reconciliation");
            append(directory.resolve(family + ".claim.json"), claim);
            return claim;
        } catch (FileAlreadyExistsException exists) {
            throw new ClaimCollisionException("Execution has already been claimed for family " + family);
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    public static ExecutionClaim recordOutcome(ExecutionClaim claim, String status, String details, Path directory) {
        Objects.requireNonNull(claim);
        if (!TERMINAL.contains(status)) throw new IllegalArgumentException("Unknown terminal outcome: " + status);
        try {
            var current = MAPPER.readValue(directory.resolve(claim.evaluationFamilyId() + ".claim.json").toFile(), ExecutionClaim.class);
            if (!current.equals(claim) || !"CLAIMED".equals(current.status()))
                throw new IllegalStateException("Claim snapshot mismatch");
            var outcome = new ExecutionClaim(claim.runId(), claim.datasetSha256(), claim.freezeSha256(),
                    claim.claimedAt(), status, Objects.requireNonNullElse(details, ""), claim.evaluationFamilyId());
            // One CREATE_NEW, rather than a read/check/truncate race. Claim is never rewritten.
            append(receiptPath(claim, directory), outcome);
            return outcome;
        } catch (FileAlreadyExistsException exists) {
            throw new IllegalStateException("Terminal outcome cannot be overwritten", exists);
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    /** Stable eligibility key; a fresh random runId never renews an interrupted recovery. */
    public static String recoveryFamily(ExecutionClaim parent) {
        return "recovery-"+com.specagent.common.Hashes.sha256Hex(parent.datasetSha256()+":"+parent.freezeSha256()+":"+parent.runId()+":1");
    }
    public static ExecutionClaim claimRecovery(ExecutionClaim parent,String manifestSha,Path directory) {
        safe(manifestSha);
        try {
            var original=MAPPER.readValue(directory.resolve(parent.evaluationFamilyId()+".claim.json").toFile(),ExecutionClaim.class);
            var terminal=MAPPER.readValue(receiptPath(parent,directory).toFile(),ExecutionClaim.class);
            if(!parent.equals(original) || !"CLAIMED".equals(parent.status()) || !"INTERRUPTED_OR_ERROR".equals(terminal.status())
                    || !terminal.runId().equals(parent.runId()) || !terminal.datasetSha256().equals(parent.datasetSha256())
                    || !terminal.freezeSha256().equals(parent.freezeSha256()) || !terminal.evaluationFamilyId().equals(parent.evaluationFamilyId()))
                throw new IllegalStateException("Recovery requires the original consumed claim and interrupted receipt");
            String family=recoveryFamily(parent);
            var recovery=new ExecutionClaim(UUID.randomUUID().toString(),parent.datasetSha256(),parent.freezeSha256(),
                Instant.now().toString(),"CLAIMED",MAPPER.writeValueAsString(Map.of("mode","RECOVERY_RUN","parentRunId",parent.runId(),
                    "manifestSha256",manifestSha,"recoveryOrdinal",1)),family);
            append(directory.resolve(family+".claim.json"),recovery);
            return recovery;
        } catch(FileAlreadyExistsException exists) { throw new ClaimCollisionException("Recovery attempt already consumed"); }
        catch(IOException ex) { throw new UncheckedIOException(ex); }
    }
    public static boolean hasOutcome(ExecutionClaim claim, Path directory) {
        return Files.exists(receiptPath(claim, directory));
    }
    private static Path receiptPath(ExecutionClaim claim, Path directory) {
        return directory.resolve(claim.datasetSha256() + "." + claim.runId() + ".receipt.json");
    }
    public static void writeResult(ExecutionClaim claim, String report, Path directory) {
        try {
            writeOnce(directory.resolve(claim.runId() + ".result.json"), report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    private static void append(Path path, ExecutionClaim value) throws IOException {
        writeOnce(path, MAPPER.writeValueAsBytes(value));
    }
    private static void writeOnce(Path path, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
    private static void safe(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid ledger identity");
    }
}
