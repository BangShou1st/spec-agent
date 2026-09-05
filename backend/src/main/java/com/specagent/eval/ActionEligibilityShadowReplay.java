package com.specagent.eval;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.eligibility.ActionEligibility;
import com.specagent.agent.eligibility.ActionEligibilityEvaluator;
import com.specagent.agent.eligibility.ActionEligibilityReasonCode;
import com.specagent.agent.eligibility.ActionEligibilityValidator;
import com.specagent.agent.eligibility.ActionIneligibleException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Offline replay of the production Java eligibility evaluator over eval traces. */
public final class ActionEligibilityShadowReplay {

    private ActionEligibilityShadowReplay() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: <output-dir> <results.jsonl> [results.jsonl ...]");
        }
        Path output = Path.of(args[0]);
        List<Path> inputs = java.util.Arrays.stream(args).skip(1).map(Path::of).toList();
        Report report = analyze(inputs);
        Files.createDirectories(output);
        Files.writeString(output.resolve("eligibility-shadow-report.json"),
                EvalArtifactWriter.toJson(report.toMap()) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(output.resolve("eligibility-shadow-report.txt"), report.toText(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        System.out.print(report.toText());
    }

    public static Report analyze(List<Path> inputs) throws IOException {
        ActionEligibilityEvaluator evaluator = new ActionEligibilityEvaluator();
        ActionEligibilityValidator validator = new ActionEligibilityValidator();
        List<Result> results = new ArrayList<>();
        int skipped = 0;
        for (Path input : inputs) {
            for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                ObservationEnvelope observation = EvalArtifactWriter.fromJsonl(line);
                Map<String, Map<String, Object>> stages = observation.semanticTrace().stages();
                Map<String, Object> inputStage = stages.get("DECISION_INPUT");
                Map<String, Object> outputStage = stages.get("DECISION_OUTPUT");
                if (inputStage == null || outputStage == null
                        || !(inputStage.get("runtime_request") instanceof Map<?, ?> requestMap)
                        || !(outputStage.get("normalized_output") instanceof Map<?, ?> proposalMap)) {
                    skipped++;
                    continue;
                }
                AgentRequestEnvelope request = AgentContracts.read(
                        EvalArtifactWriter.toJson(stringMap(requestMap)),
                        AgentRequestEnvelope.class);
                ActionProposal proposal = AgentContracts.read(
                        EvalArtifactWriter.toJson(stringMap(proposalMap)), ActionProposal.class);
                ActionEligibility eligibility = evaluator.evaluate(request);
                List<ActionEligibilityReasonCode> reasons = List.of();
                boolean veto = false;
                try {
                    validator.validateSelection(request, proposal, eligibility);
                } catch (ActionIneligibleException ex) {
                    veto = true;
                    reasons = List.of(ex.reasonCode());
                }
                results.add(new Result(input.toString(), observation.scenarioId(),
                        observation.variantId(), observation.repetition(),
                        observation.actualPrimaryAction(), observation.passed(), veto, reasons));
            }
        }
        return new Report(inputs.stream().map(Path::toString).toList(), results, skipped);
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    public record Result(String artifact,
                         String scenario,
                         String variant,
                         int repetition,
                         String action,
                         boolean passed,
                         boolean wouldVeto,
                         List<ActionEligibilityReasonCode> reasons) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("artifact", artifact);
            map.put("scenario", scenario);
            map.put("variant", variant);
            map.put("repetition", repetition);
            map.put("action", action);
            map.put("passed", passed);
            map.put("would_veto", wouldVeto);
            map.put("reason_codes", reasons.stream().map(Enum::name).toList());
            return map;
        }
    }

    public record Report(List<String> artifacts, List<Result> results, int skipped) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("schema_version", "eligibility-shadow-report.v1");
            map.put("artifacts", artifacts);
            map.put("analyzed", results.size());
            map.put("skipped", skipped);
            map.put("would_veto", results.stream().filter(Result::wouldVeto).count());
            map.put("vetoed_failures", results.stream()
                    .filter(result -> result.wouldVeto() && !result.passed()).count());
            map.put("vetoed_passes", results.stream()
                    .filter(result -> result.wouldVeto() && result.passed()).count());
            map.put("wrong_create_node", count("CREATE_NODE", false, false));
            map.put("vetoed_wrong_create_node", count("CREATE_NODE", false, true));
            map.put("correct_create_node", count("CREATE_NODE", true, false));
            map.put("vetoed_correct_create_node", count("CREATE_NODE", true, true));
            map.put("correct_action_impact", correctActionImpact());
            map.put("reason_distribution", reasonDistribution());
            map.put("scenario_summary", scenarioSummary());
            map.put("results", results.stream().map(Result::toMap).toList());
            return map;
        }

        private long count(String action, boolean passed, boolean vetoOnly) {
            return results.stream().filter(result -> action.equals(result.action()))
                    .filter(result -> result.passed() == passed)
                    .filter(result -> !vetoOnly || result.wouldVeto()).count();
        }

        private Map<String, Object> correctActionImpact() {
            Map<String, Object> impact = new TreeMap<>();
            for (String action : results.stream().filter(Result::passed)
                    .map(Result::action).distinct().sorted().toList()) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("correct", results.stream().filter(Result::passed)
                        .filter(result -> action.equals(result.action())).count());
                values.put("would_veto", results.stream().filter(Result::passed)
                        .filter(result -> action.equals(result.action()))
                        .filter(Result::wouldVeto).count());
                impact.put(action, values);
            }
            return impact;
        }

        private Map<String, Long> reasonDistribution() {
            Map<String, Long> counts = new TreeMap<>();
            results.stream().flatMap(result -> result.reasons().stream()).forEach(reason ->
                    counts.merge(reason.name(), 1L, Long::sum));
            return counts;
        }

        private Map<String, Object> scenarioSummary() {
            Map<String, Object> summary = new TreeMap<>();
            for (String scenario : results.stream().map(Result::scenario)
                    .distinct().sorted().toList()) {
                List<Result> group = results.stream()
                        .filter(result -> scenario.equals(result.scenario())).toList();
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("analyzed", group.size());
                values.put("passed", group.stream().filter(Result::passed).count());
                values.put("would_veto", group.stream().filter(Result::wouldVeto).count());
                values.put("vetoed_passes", group.stream()
                        .filter(result -> result.passed() && result.wouldVeto()).count());
                values.put("actions", group.stream().collect(java.util.stream.Collectors.groupingBy(
                        Result::action, TreeMap::new, java.util.stream.Collectors.counting())));
                summary.put(scenario, values);
            }
            return summary;
        }

        String toText() {
            Map<String, Object> map = toMap();
            StringBuilder text = new StringBuilder();
            text.append("Action Eligibility Shadow Replay\n");
            text.append("artifacts: ").append(artifacts.size()).append('\n');
            text.append("analyzed: ").append(map.get("analyzed"))
                    .append("; skipped: ").append(skipped).append('\n');
            text.append("would-veto: ").append(map.get("would_veto"))
                    .append("; vetoed failures: ").append(map.get("vetoed_failures"))
                    .append("; vetoed passes: ").append(map.get("vetoed_passes")).append('\n');
            text.append("wrong CREATE_NODE: ").append(map.get("wrong_create_node"))
                    .append("; vetoed: ").append(map.get("vetoed_wrong_create_node")).append('\n');
            text.append("correct CREATE_NODE: ").append(map.get("correct_create_node"))
                    .append("; vetoed: ").append(map.get("vetoed_correct_create_node")).append('\n');
            text.append("correct action impact: ").append(map.get("correct_action_impact")).append('\n');
            text.append("reason distribution: ").append(map.get("reason_distribution")).append('\n');
            text.append("scenario summary: ").append(map.get("scenario_summary")).append('\n');
            return text.toString();
        }
    }
}
