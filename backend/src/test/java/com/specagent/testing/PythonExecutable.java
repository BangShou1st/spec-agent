package com.specagent.testing;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件名:PythonExecutable.java
 *
 * 用途:跨平台定位"要启动的真实 Python 进程"可执行文件。
 *
 * <p>为什么需要它:集成测试会把 agent-brain 作为**独立进程**启动。那条链路在
 * Windows 和 Linux 上的虚拟环境布局不同({@code .venv/Scripts/python.exe} 与
 * {@code .venv/bin/python})。把某个平台的路径写死在测试里,会让另一个平台上的
 * 同一个测试以"找不到解释器"失败——那是环境假设错误,不是被测行为失败。
 *
 * <p>查找顺序:
 * <ol>
 *   <li>显式环境变量({@code SPEC_AGENT_GA_TEST_PYTHON} 或
 *       {@code SPEC_AGENT_P3B_EVAL_PYTHON})——调用方明确指定的解释器;</li>
 *   <li>仓库内的虚拟环境,按当前平台选择目录布局;</li>
 *   <li>{@code python3} / {@code python},交给系统解析。</li>
 * </ol>
 *
 * <p>绝不因为找不到解释器就伪造一个结果:调用方仍然会因为解释器缺失而失败,
 * 但那条消息现在说的是"缺什么",而不是一个和被测行为无关的路径错误。
 */
public final class PythonExecutable {

    private PythonExecutable() {
    }

    /**
     * 返回可用的 Python 解释器路径。
     *
     * @param root 仓库根目录(用于解析仓库内的虚拟环境)
     * @return 解释器路径;找不到时返回平台默认的 {@code python3} / {@code python}
     */
    public static String locate(Path root) {
        String configured = firstConfigured(
                System.getenv("SPEC_AGENT_GA_TEST_PYTHON"),
                System.getenv("SPEC_AGENT_P3B_EVAL_PYTHON"));
        if (configured != null) {
            Path path = Path.of(configured);
            Path fromRoot = path.isAbsolute() ? path : root.resolve(path).normalize();
            if (Files.isRegularFile(fromRoot)) {
                return fromRoot.toString();
            }
            Path fromCwd = path.isAbsolute() ? path
                    : Path.of(System.getProperty("user.dir")).resolve(path).normalize();
            if (Files.isRegularFile(fromCwd)) {
                return fromCwd.toString();
            }
            return configured;
        }
        Path venv = root.resolve(isWindows()
                ? "agent-brain/.venv/Scripts/python.exe"
                : "agent-brain/.venv/bin/python");
        if (Files.isRegularFile(venv)) {
            return venv.toString();
        }
        return isWindows() ? "python" : "python3";
    }

    private static String firstConfigured(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
