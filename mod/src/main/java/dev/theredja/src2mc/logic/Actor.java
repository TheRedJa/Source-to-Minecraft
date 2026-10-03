package dev.theredja.src2mc.logic;

/**
 * What can set an I/O chain off or be handed along it as {@code !activator}: a map entity or a
 * player. Filters and {@code !activator} targets see only this much of it.
 */
public interface Actor {
    /** The targetname, or "" for none; players have none. */
    String name();

    String classname();
}
