/**
 * ModelGateway: the only boundary through which Spec Agent talks to external model providers.
 * Custom HTTP gateway first, Spring AI is not the default.
 * Runtime Kernel must not depend on model gateway.
 */
package com.specagent.model.gateway;