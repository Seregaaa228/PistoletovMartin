package dev.frontier.flamingofix;

/** Bridge added only to the pinned native terrain mesh by its mixin. */
public interface DeferredMissileMesh {
    void frontier$defer(DeferredMissileMotion motion);
}
