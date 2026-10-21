rootProject.name = "spec-agent-backend"

// OpenCode Zen 依赖是"双模"的，项目内不再保存库源码快照：
//
//   * 默认：从 build.gradle.kts 声明的仓库解析
//     com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.4.2（公开 JitPack）；
//   * 联调覆盖：把 OPENCODE_ZEN_LOCAL 指向库的本地检出目录，Gradle 会用本地源码替换同坐标依赖，
//     改完即生效——不需要发布、不需要拷贝快照、不需要手工同步脚本。
//
// 用法：$env:OPENCODE_ZEN_LOCAL="E:\project\shared-libs\opencode-zen"
val localZen = System.getenv("OPENCODE_ZEN_LOCAL")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
if (localZen != null && localZen.resolve("settings.gradle.kts").isFile) {
    includeBuild(localZen) {
        dependencySubstitution {
            substitute(module("com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-autoconfigure"))
                .using(project(":opencode-zen-spring-boot-autoconfigure"))
            substitute(module("com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter"))
                .using(project(":opencode-zen-spring-boot-starter"))
        }
    }
}
