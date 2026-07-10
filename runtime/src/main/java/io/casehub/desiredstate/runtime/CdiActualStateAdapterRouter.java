package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualStateAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.stream.StreamSupport;

@ApplicationScoped
public class CdiActualStateAdapterRouter extends DefaultActualStateAdapterRouter {

    @Inject
    public CdiActualStateAdapterRouter(Instance<ActualStateAdapter> adapters) {
        super(StreamSupport.stream(adapters.spliterator(), false).toList());
    }
}
