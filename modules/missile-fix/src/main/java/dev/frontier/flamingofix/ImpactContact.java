package dev.frontier.flamingofix;

/** Server-thread token for the entity selected by an actual native collision. */
public final class ImpactContact<T> {
    private T target;
    public void record(T target) { this.target=target; }
    public T take() { T result=target;target=null;return result; }
}
