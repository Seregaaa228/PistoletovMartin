package dev.frontier.flamingofix;

import java.util.Iterator;
import java.util.function.Predicate;

/** One native consumer may defer contact removal; later event handlers keep their ordinary veto. */
public final class DeferredContactIterator<T> implements Iterator<T> {
    private final Iterator<T> delegate;
    private final Predicate<T> defer;
    private T current;
    private boolean removable;
    public DeferredContactIterator(Iterator<T> delegate,Predicate<T> defer) {this.delegate=delegate;this.defer=defer;}
    @Override public boolean hasNext() {return delegate.hasNext();}
    @Override public T next() {current=delegate.next();removable=true;return current;}
    @Override public void remove() {
        if(!removable)throw new IllegalStateException("No unconsumed current recipient");
        removable=false;
        if(!defer.test(current))delegate.remove();
    }
}
