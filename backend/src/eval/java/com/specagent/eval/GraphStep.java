package com.specagent.eval;

import java.util.List;
import java.util.Map;

/**
 * 文件名:GraphStep.java
 *
 * 用途:场景 {@code given} 块中声明式图搭建步骤的密封接口(sealed)。
 * 步骤使用稳定种子(questionSeed、answerSeed 等)而非固定 UUID 或固定的
 * 自然语言句子:运行器从场景 id、变体种子和改写索引确定性地推导出具体
 * 文本,因此变体改写永远不会把场景绑定到某一个固定句子。
 *
 * 协作:由 {@link GivenSpec} 持有,{@link ScenarioRunner} 逐步执行搭建
 * 初始工作区图;{@code canonical()} 参与场景哈希。
 */
public sealed interface GraphStep
        permits GraphStep.CreateRootQuestion,
                GraphStep.CreateRootQuestionText,
                GraphStep.CreateChildQuestion,
                GraphStep.AttachResource,
                GraphStep.ForkFromNode,
                GraphStep.SetFocus,
                GraphStep.SetKnowledgeStatus {

    /** 在活动路由上创建根 INTERACTION/QUESTION。 */
    record CreateRootQuestion(String questionSeed, boolean allowFreeAnswer) implements GraphStep {
    }

    /**
     * 在活动路由上创建根 INTERACTION/QUESTION,**直接给出字面问题文本**。
     *
     * 种子渲染(见 {@link #renderText})刻意产出占位串,适合确定性 B-fast;
     * 但真实模型评测需要模型看到真实自然语言,否则测到的是占位符而不是理解
     * 能力。这条变体只服务于 live 评测,语义与 {@link CreateRootQuestion} 相同。
     *
     * <p>{@code questionTexts} 是一组**语义等价、措辞不同**的字面文本:变体的
     * {@code paraphraseIndex} 选中其中一条(与 {@link #renderText} 相同的取模规则),
     * 于是 base 与 holdout 可以各自看到真实但不同的中文,而不是同一句被复制。
     * 只要一条文本时行为与旧的单串构造完全一致。
     */
    record CreateRootQuestionText(List<String> questionTexts, boolean allowFreeAnswer)
            implements GraphStep {

        public CreateRootQuestionText {
            if (questionTexts == null || questionTexts.isEmpty()) {
                throw new IllegalArgumentException(
                        "CreateRootQuestionText requires at least one literal question text");
            }
            questionTexts = List.copyOf(questionTexts);
        }

        /** 旧的单串构造:一条文本,所有变体看到同一句。 */
        public CreateRootQuestionText(String questionText, boolean allowFreeAnswer) {
            this(List.of(questionText), allowFreeAnswer);
        }

        /** 按变体的改写索引选择字面文本(取模,与 {@link #renderText} 一致)。 */
        String questionText(int paraphraseIndex) {
            return questionTexts.get(Math.floorMod(paraphraseIndex, questionTexts.size()));
        }
    }

    /** 在前序步骤构建的节点下创建子 INTERACTION/QUESTION。 */
    record CreateChildQuestion(String parentStepRef, String questionSeed,
                               boolean allowFreeAnswer) implements GraphStep {
    }

    /** 在当前末端(或空路由上)附加一个 TEXT 资源。 */
    record AttachResource(String textSeed) implements GraphStep {
    }

    /** 从前序步骤构建的节点分叉出新路由。 */
    record ForkFromNode(String sourceStepRef, String labelSeed) implements GraphStep {
    }

    /** 移动工作焦点,不改变活动路由。 */
    record SetFocus(String targetStepRef) implements GraphStep {
    }

    /** 对工作区节点施加一次显式的知识状态迁移。 */
    record SetKnowledgeStatus(String targetStepRef, String status) implements GraphStep {
    }

    /** 场景哈希用的规范化渲染。 */
    static String canonical(List<GraphStep> steps) {
        StringBuilder rendered = new StringBuilder("[");
        for (GraphStep step : steps) {
            if (step instanceof CreateRootQuestion root) {
                rendered.append("root(").append(root.questionSeed())
                        .append(",").append(root.allowFreeAnswer()).append(");");
            } else if (step instanceof CreateRootQuestionText rootText) {
                // 字面文本直接进入哈希:live 评测的语义就是这些真实问题(全部变体)。
                rendered.append("rootText(").append(String.join("|", rootText.questionTexts()))
                        .append(",").append(rootText.allowFreeAnswer()).append(");");
            } else if (step instanceof CreateChildQuestion child) {
                rendered.append("child(").append(child.parentStepRef())
                        .append(",").append(child.questionSeed())
                        .append(",").append(child.allowFreeAnswer()).append(");");
            } else if (step instanceof AttachResource resource) {
                rendered.append("resource(").append(resource.textSeed()).append(");");
            } else if (step instanceof ForkFromNode fork) {
                rendered.append("fork(").append(fork.sourceStepRef())
                        .append(",").append(fork.labelSeed()).append(");");
            } else if (step instanceof SetFocus focus) {
                rendered.append("focus(").append(focus.targetStepRef()).append(");");
            } else if (step instanceof SetKnowledgeStatus status) {
                rendered.append("status(").append(status.targetStepRef())
                        .append(",").append(status.status()).append(");");
            } else {
                rendered.append("unknown;");
            }
        }
        return rendered.append("]").toString();
    }

    /** 用户可见文本种子的占位渲染(断言永远不会逐字比较这些文本)。 */
    static String renderText(String scenarioId, String seed, int paraphraseIndex,
                             Map<String, List<String>> paraphrases) {
        List<String> options = paraphrases.get(seed);
        if (options != null && !options.isEmpty()) {
            return options.get(Math.floorMod(paraphraseIndex, options.size()));
        }
        return "[" + scenarioId + ":" + seed + "#p" + paraphraseIndex + "]";
    }
}
