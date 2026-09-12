package com.specagent.model.provider;

/**
 * Sink for provider content fragments during one streaming completion call.
 *
 * <p>Fragments are raw decoded content strings in arrival order, not tokens
 * and not JSON structure. Returning {@code false} asks the transport to abort
 * the provider stream; the transport then throws {@link StreamCancelledException}.
 * The listener must never execute tools, navigation, or persistence - it only
 * observes presentation-safe text. Control decisions still require the complete
 * validated contract.
 */
public interface FragmentListener {

    boolean onFragment(String fragment);
}