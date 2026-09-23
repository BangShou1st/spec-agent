package com.specagent.workspace.spec;

import com.specagent.workspace.spec.UnresolvedItem;

/**
 * Read-only unresolved item of a spec snapshot.
 */
public record UnresolvedItemResponse(
        String text,
        String category) {

    public static UnresolvedItemResponse from(UnresolvedItem item) {
        return new UnresolvedItemResponse(item.text(), item.category());
    }
}