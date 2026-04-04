package com.claude.code.state;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class Store<T> {
    public interface Listener<T> {
        void onChanged(T newState, T oldState);
    }

    private volatile T state;
    private final Set<Listener<T>> listeners = new CopyOnWriteArraySet<>();

    public Store(T initialState) {
        this.state = initialState;
    }

    public T getState() { return state; }

    public void setState(StateUpdater<T> updater) {
        T oldState = this.state;
        T newState = updater.update(oldState);
        if (newState != oldState) {
            this.state = newState;
            for (Listener<T> listener : listeners) {
                listener.onChanged(newState, oldState);
            }
        }
    }

    public void subscribe(Listener<T> listener) { listeners.add(listener); }
    public void unsubscribe(Listener<T> listener) { listeners.remove(listener); }

    public interface StateUpdater<T> {
        T update(T currentState);
    }
}
