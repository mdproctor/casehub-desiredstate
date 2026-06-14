package io.casehub.desiredstate.api;

public interface ActualStateAdapter {
    ActualState readActual(DesiredStateGraph desired);
}
