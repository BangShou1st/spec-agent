package com.specagent.model.provider;

import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Package-private routing surface shared by the Custom GA protocol transports. */
interface CustomGaTransport {
    GaModelContract.Response complete(GaModelTarget target, GaModelContract.Request request,
            Duration remainingBudget, BooleanSupplier active);

    GaModelContract.Response stream(GaModelTarget target, GaModelContract.Request request,
            Duration remainingBudget, BooleanSupplier active, FragmentListener listener);
}
