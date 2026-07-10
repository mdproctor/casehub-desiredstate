package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.EventSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.stream.StreamSupport;

@ApplicationScoped
public class CdiMergedEventSource extends DefaultMergedEventSource {

    @Inject
    public CdiMergedEventSource(Instance<EventSource> sources) {
        super(StreamSupport.stream(sources.spliterator(), false).toList());
    }
}
