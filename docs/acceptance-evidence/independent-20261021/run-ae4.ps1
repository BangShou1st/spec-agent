$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$output = Join-Path $repo 'backend/build/ae4-independent-manual'
New-Item -ItemType Directory -Force $output | Out-Null
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$jars = Get-ChildItem $cache -Recurse -Filter *.jar | Where-Object {
    $_.FullName -match '\\(org\.springframework|org\.springframework\.boot|org\.junit\.jupiter|org\.junit\.platform|org\.mockito|org\.assertj|net\.bytebuddy|org\.objenesis|org\.opentest4j|org\.apiguardian)\\' -and
    $_.Name -notmatch '(gradle-plugin|buildpack|loader-tools|configuration-processor)'
}
$classpath = ($jars.FullName -join ';')
$runner = @'
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public class Ae4OfflineRunner {
    public static void main(String[] args) {
        var request = LauncherDiscoveryRequestBuilder.request().selectors(
            selectClass("com.specagent.support.DatabaseTestContextGuardTest"),
            selectClass("com.specagent.eval.P3BLiveDatabaseGuardTest")).build();
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, listener);
        var summary = listener.getSummary();
        summary.printTo(new java.io.PrintWriter(System.out));
        summary.printFailuresTo(new java.io.PrintWriter(System.out));
        if (summary.getTestsFoundCount() == 0 || summary.getTestsFailedCount() != 0
                || summary.getTestsAbortedCount() != 0 || summary.getTestsSkippedCount() != 0)
            System.exit(1);
    }
}
'@
$runnerPath = Join-Path $output 'Ae4OfflineRunner.java'
[IO.File]::WriteAllText($runnerPath, $runner)
$sources = @(
    'backend/src/eval/java/com/specagent/eval/EvalLiveIsolatedDatabase.java',
    'backend/src/test/java/com/specagent/eval/P3BLiveDatabaseGuardTest.java',
    'backend/src/test/java/com/specagent/support/DatabaseTestContextGuard.java',
    'backend/src/test/java/com/specagent/support/DatabaseTestContextGuardTest.java'
) | ForEach-Object { Join-Path $repo $_ }
& javac -proc:none -encoding UTF-8 -cp $classpath -d $output @sources $runnerPath
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }
New-Item -ItemType Directory -Force (Join-Path $output 'META-INF') | Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'backend/src/test/resources/META-INF/spring.factories') -Destination (Join-Path $output 'META-INF/spring.factories')
& java '-Dspecagent.database-tests.enabled=false' '-Dspecagent.external-tests.enabled=false' -cp "$output;$classpath" Ae4OfflineRunner
if ($LASTEXITCODE -ne 0) { throw "JUnit failed ($LASTEXITCODE)" }
