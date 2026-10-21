# OpenCode Shared Component

Status: maintained in its own repository and consumed as a published Gradle dependency from public JitPack
Date: 2026-10-16

## Layout

The OpenCode Zen library has the conventional two-module Spring Boot layout under shared-libs/opencode-zen:

| Module | Responsibility |
|---|---|
| opencode-zen-spring-boot-autoconfigure | Shared OpenCode Zen protocol and model rules, Spring Boot properties, auto-configuration, and request factory. |
| opencode-zen-spring-boot-starter | Dependency aggregation entry point for Spring Boot applications. |

The library lives in its own repository at `E:\project\shared-libs\opencode-zen`; Spec Agent does
**not** keep a copy of the sources and there is no sync step. The backend declares a normal
dependency on `com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.4.2` and
resolves it from public JitPack (`https://jitpack.io`) with no credentials and no token.

For co-development, point `OPENCODE_ZEN_LOCAL` at a local library checkout: Gradle then substitutes
those coordinates with the local source build, so a library change takes effect immediately without
publishing anything.

```powershell
$env:OPENCODE_ZEN_LOCAL = "E:\project\shared-libs\opencode-zen"
```

By default the backend resolves the published coordinates from public JitPack, on a workstation as
well as in CI; the `OPENCODE_ZEN_LOCAL` override above switches it to the local source build.

## Spring Boot use

Add com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.4.2. The starter registers an
OpenCodeZenRequestFactory bean and binds opencode.zen.user-agent. Applications keep credential
storage, model classification, request bodies, timeouts, traces, routing, and business contracts.

Consumers that do not want to manage anything about the wire shape can use the one-stop factory
methods `newConversationRequest(endpoint, freeModel, apiKey, conversationKey)` and
`newEphemeralRequest(endpoint, freeModel, apiKey)`: the factory generates the shaped `ses_`/`msg_`
identifiers itself (caching one session per conversation), resolves the credential, and applies the
shared identity headers. Identifiers supplied by hand are validated fail-closed, so a UUID or an
arbitrary string is rejected locally instead of being answered upstream with `403 FreeTierError`.

`OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL` is the canonical inference base
`https://opencode.ai/inference/v1`; the legacy `https://opencode.ai/zen/v1` is exposed separately as
`LEGACY_DISCOVERY_BASE_URL` for model-catalogue discovery only. `applyRequiredChatEnvelope`
attaches the full CLI core tool envelope (`bash, edit, glob, grep, read`), which the free tier
requires: a body without it is rejected with `403 FreeTierError`.

The model list contains free and paid Chat models. The UI's 仅免费 control is only a filter. Free
selections use Bearer public without requiring or sending a user key. Paid selections require a key;
unknown models are treated as paid.

## Build

Build the shared modules:

    Set-Location E:\project\shared-libs\opencode-zen
    .\gradlew.bat compileJava

The backend consumes the same library either as published coordinates or, with
`OPENCODE_ZEN_LOCAL` set, as the local source build. To build the backend:

    Set-Location E:\project\spec-agent\backend
    .\gradlew.bat compileJava

For a new release, update the version in the shared modules and the backend dependency together. Do not overwrite a released version.

## Publishing and consuming through public JitPack

The library is published publicly on JitPack. A release is identified by a git tag and is consumed
with the tag-based coordinate `com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:<tag>`.
There is no private repository, no stored secret and no token: a consumer only needs the public
JitPack repository and the dependency itself.

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "jitpack"
        url = uri("https://jitpack.io")
    }
}
dependencies { implementation("com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.4.2") }
```

To work against a local checkout without publishing anything, use the `OPENCODE_ZEN_LOCAL`
composite-build override described above: Gradle substitutes the same coordinates with the local
source build.

### Discipline

* publish a new tag (and the matching version) for every change; a released version is never
  overwritten;
* keep the starter and the backend dependency in step: the backend coordinate
  `com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:<tag>` must name the tag the
  library release was published under.

## Failure handling

The transport does not retry or switch providers automatically. HTTP 429 remains RATE_LIMITED; other HTTP, connection, timeout, empty-content, and malformed-response failures keep their existing categories and diagnostics.

An upstream `403 FreeTierError` is **not** a provider-side rejection of the product: it means the
request itself is not accepted (shaped identifiers/counter, CLI tool envelope, or the base URL).
`gradlew.bat zenUpstreamContract` in the backend asserts the shared construction path returns
`200` or `429` and fails on anything else.
