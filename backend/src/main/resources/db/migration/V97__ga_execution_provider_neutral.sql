-- GA execution bindings are provider-neutral: ga_executions.provider records the frozen
-- provider of the run (resolved from the active model settings), so the V43 constraint that
-- pinned it to exactly 'OPENCODE_ZEN' must be relaxed to every registered provider.
--
-- Unknown codes are still rejected: the application binding validation only accepts a
-- registered ModelProvider, and the native-transport routing fails closed for providers
-- without a qualified native GA transport. The stored value remains the frozen provider of
-- the run, so switching provider mid-run can never retarget an existing binding.
ALTER TABLE ga_executions DROP CONSTRAINT ga_executions_provider_check;
ALTER TABLE ga_executions ADD CONSTRAINT ga_executions_provider_check
    CHECK (provider IN ('OPENCODE_ZEN', 'KILO', 'OPENROUTER', 'CUSTOM'));
