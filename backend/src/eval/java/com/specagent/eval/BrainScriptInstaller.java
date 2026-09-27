package com.specagent.eval;

import java.util.List;

/**
 * 文件名:BrainScriptInstaller.java
 *
 * 用途:测试范围的移植接口(port),评测运行器通过它安装 B-fast 层的
 * Brain 脚本。由 eval 测试中的脚本化 Brain 实现;生产装配永不提供该实现,
 * 因此在 eval 测试之外运行器会直接失败(fail-closed),不会误用。
 *
 * 协作:由 {@link ScenarioRunner} 调用 {@code installScript} 注入
 * {@link BrainScript},并通过 {@code observedStages()} / {@code providerRetries()}
 * 回读运行期观察数据。
 */
public interface BrainScriptInstaller {

    void installScript(BrainScript brainScript, String scenarioId, long seed, int paraphraseIndex);

    void resetScripts();

    List<String> observedStages();

    int providerRetries();
}
