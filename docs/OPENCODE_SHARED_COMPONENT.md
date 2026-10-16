# OpenCode Shared Component

Status: source copied into the Spec Agent repository and consumed as a Gradle composite build
Date: 2026-10-16

## Layout

The OpenCode Zen library has the conventional two-module Spring Boot layout under shared-libs/opencode-zen:

| Module | Responsibility |
|---|---|
| opencode-zen-spring-boot-autoconfigure | Shared OpenCode Zen protocol and model rules, Spring Boot properties, auto-configuration, and request factory. |
| opencode-zen-spring-boot-starter | Dependency aggregation entry point for Spring Boot applications. |

The former framework-neutral core code is included in the autoconfigure module. The external working copy is E:\project\shared-libs\opencode-zen. A source snapshot is also committed at shared-libs/opencode-zen in this repository, so a GitHub clone contains everything required to build Spec Agent. Refresh the snapshot on this machine with:

    .\scripts\sync-opencode-shared-libs.ps1

The backend includes the in-repository Gradle build from backend/settings.gradle.kts; it does not depend on this machine's Maven Local cache.

## Spring Boot use

Add com.sharedlibs:opencode-zen-spring-boot-starter:1.2.0. The starter registers an
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

    Set-Location E:\project\spec-agent\shared-libs\opencode-zen
    .\gradlew.bat compileJava

The backend uses the same checked-in source through Gradle composite substitution. To build the backend:

    Set-Location E:\project\spec-agent\backend
    .\gradlew.bat compileJava

For a new release, update the version in the shared modules and the backend dependency together. Do not overwrite a released version.

## Failure handling

The transport does not retry or switch providers automatically. HTTP 429 remains RATE_LIMITED; other HTTP, connection, timeout, empty-content, and malformed-response failures keep their existing categories and diagnostics.

An upstream `403 FreeTierError` is **not** a provider-side rejection of the product: it means the
request itself is not accepted (shaped identifiers/counter, CLI tool envelope, or the base URL).
`gradlew.bat zenUpstreamContract` in the backend asserts the shared construction path returns
`200` or `429` and fails on anything else.
