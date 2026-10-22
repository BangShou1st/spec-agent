package com.specagent.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * 首次验收运行原子凭证与事务声明管理器 (Retrieval Execution Claim Manager)。
 *
 * 依据审查发现 F22，防止历史执行记录被静默覆写，并确保首次独立验收具有不可伪造、不可并发重复认领的事实凭证。
 * 核心机制：
 * 1. 同一不可变快照绑定：绑定 datasetSha256、metricContractSha256 与 freezeSha256。
 * 2. 原子文件 CAS 认领：利用 OS 文件系统 CREATE_NEW 语义，并发认领时仅允许首个进程成功，后续进程被拒绝。
 * 3. 失败/中断状态持久化：执行异常、质量失败或中断时保留失败回执，禁止重置为 NOT_RUN。
 * 4. 报告隔离：正式结果输出路径绑定唯一 runId，严禁固定路径覆盖历史。
 */
public final class RetrievalExecutionClaimManager {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public record ExecutionClaim(
            String runId,
            String datasetSha256,
            String freezeSha256,
            String claimedAt,
            String status, // "CLAIMED", "COMPLETED_PASS", "COMPLETED_FAIL", "INTERRUPTED_OR_ERROR"
            String outcomeDetails
    ) {}

    public static class ClaimCollisionException extends IllegalStateException {
        public ClaimCollisionException(String message) {
            super(message);
        }
    }

    private RetrievalExecutionClaimManager() {
    }

    public static ExecutionClaim claimFirstRun(
            String datasetSha256,
            String freezeSha256,
            Path claimsDir
    ) {
        Objects.requireNonNull(datasetSha256, "datasetSha256 must not be null");
        Objects.requireNonNull(freezeSha256, "freezeSha256 must not be null");
        Objects.requireNonNull(claimsDir, "claimsDir must not be null");

        try {
            Files.createDirectories(claimsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create claims directory: " + claimsDir, e);
        }

        Path claimFile = claimsDir.resolve(datasetSha256 + ".claim.json");

        if (Files.exists(claimFile)) {
            try {
                ExecutionClaim existing = MAPPER.readValue(claimFile.toFile(), ExecutionClaim.class);
                throw new ClaimCollisionException(
                        "Execution for dataset " + datasetSha256 + " has already been claimed! " +
                        "Existing runId=" + existing.runId() + ", status=" + existing.status() + ", claimedAt=" + existing.claimedAt()
                );
            } catch (IOException e) {
                throw new ClaimCollisionException("Execution claim file already exists for dataset " + datasetSha256);
            }
        }

        String runId = UUID.randomUUID().toString();
        ExecutionClaim newClaim = new ExecutionClaim(
                runId,
                datasetSha256,
                freezeSha256,
                Instant.now().toString(),
                "CLAIMED",
                "Initial execution claim acquired; run pending"
        );

        byte[] bytes = serialize(newClaim);

        // 利用 CREATE_NEW 实现原子文件锁
        try (FileChannel channel = FileChannel.open(claimFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(bytes));
        } catch (FileAlreadyExistsException e) {
            throw new ClaimCollisionException(
                    "Concurrent claim collision detected! Another process claimed dataset " + datasetSha256
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write execution claim", e);
        }

        return newClaim;
    }

    public static ExecutionClaim recordOutcome(
            ExecutionClaim claim,
            String outcomeStatus,
            String outcomeDetails,
            Path claimsDir
    ) {
        Objects.requireNonNull(claim, "claim must not be null");
        Objects.requireNonNull(outcomeStatus, "outcomeStatus must not be null");
        Objects.requireNonNull(claimsDir, "claimsDir must not be null");

        Path claimFile = claimsDir.resolve(claim.datasetSha256() + ".claim.json");
        if (!Files.exists(claimFile)) {
            throw new IllegalStateException("Execution claim file does not exist for dataset " + claim.datasetSha256());
        }

        ExecutionClaim current;
        try {
            current = MAPPER.readValue(claimFile.toFile(), ExecutionClaim.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read current claim file: " + claimFile, e);
        }

        if (!current.runId().equals(claim.runId())) {
            throw new IllegalStateException("Claim runId mismatch: current=" + current.runId() + ", requested=" + claim.runId());
        }

        // 终态不可覆盖：若当前状态已经是终态，严禁覆写为其他状态（例如 COMPLETED_FAIL 被覆写为 COMPLETED_PASS）
        Set<String> terminalStates = Set.of("COMPLETED_PASS", "COMPLETED_FAIL", "INTERRUPTED_OR_ERROR");
        if (terminalStates.contains(current.status())) {
            throw new IllegalStateException(
                    "Terminal claim status '" + current.status() + "' for dataset " + claim.datasetSha256() +
                    " cannot be overwritten with '" + outcomeStatus + "'"
            );
        }

        ExecutionClaim updated = new ExecutionClaim(
                claim.runId(),
                claim.datasetSha256(),
                claim.freezeSha256(),
                claim.claimedAt(),
                outcomeStatus,
                outcomeDetails == null ? "" : outcomeDetails
        );

        byte[] bytes = serialize(updated);
        try {
            Files.write(claimFile, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to update claim outcome: " + claimFile, e);
        }

        // 同时以 runId 独立路径生成不可覆写的独立执行回执 (Receipt)
        Path receiptFile = claimsDir.resolve(claim.datasetSha256() + "." + claim.runId() + ".receipt.json");
        try {
            Files.write(receiptFile, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            // runId receipt 已经存在
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write claim receipt: " + receiptFile, e);
        }

        return updated;
    }

    private static byte[] serialize(ExecutionClaim claim) {
        try {
            return MAPPER.writeValueAsString(claim).getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
