rootProject.name = "spec-agent-backend"

includeBuild("../shared-libs/opencode-zen") {
    dependencySubstitution {
        substitute(module("com.sharedlibs:opencode-zen-spring-boot-autoconfigure"))
            .using(project(":opencode-zen-spring-boot-autoconfigure"))
        substitute(module("com.sharedlibs:opencode-zen-spring-boot-starter"))
            .using(project(":opencode-zen-spring-boot-starter"))
    }
}
