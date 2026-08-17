/**
 * Route lifecycle: creation, fork, regenerate, restore, archive, soft delete.
 * Route lifecycle status: open, superseded, archived, deleted.
 * Active route is tracked by Project.activeRouteId, not by Route.lifecycleStatus.
 */
package com.specagent.route;