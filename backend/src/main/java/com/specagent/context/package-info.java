/**
 * Context: lineage-based context snapshot construction.
 * Context is built from active route's tip node by replaying parent lineage.
 * Sibling routes, superseded routes, deleted routes are excluded by default.
 */
package com.specagent.context;