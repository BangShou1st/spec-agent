package com.specagent.api.spec;

import com.specagent.spec.UnresolvedItem;

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