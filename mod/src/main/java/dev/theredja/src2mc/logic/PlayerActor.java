package dev.theredja.src2mc.logic;

import java.util.UUID;

/** A player as an activator: kept by id, so a chain still names them after a relog. */
public record PlayerActor(UUID id) implements Actor {
    @Override public String name() { return ""; }
    @Override public String classname() { return "player"; }
}
