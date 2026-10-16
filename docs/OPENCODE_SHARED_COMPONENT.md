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

Add com.sharedlibs:opencode-zen-spring-boot-starter:1.1.1. The starter registers an OpenCodeZenRequestFactory bean and binds opencode.zen.user-agent. Applications keep credential storage, model classification, request bodies, timeouts, traces, routing, and business contracts. The factory accepts an application-resolved credential (public for a free model, the user's key for a paid model) and applies shared identity and authorization headers.

The model list contains free and paid Chat models. The UI's 仅免费 control is only a filter. Free selections use Bearer public without requiring or sending a user key. Paid selections require a key; unknown models are treated as paid.

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
