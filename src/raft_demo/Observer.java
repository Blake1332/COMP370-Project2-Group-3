package raft_demo;

/**
 * Minimal Observer interface (for the Observer pattern refactor).
 *
 * Events are represented as strings with a simple convention like:
 * - "CLIENT_CONNECTED:3"
 * - "LEADER_CHANGED:2"
 * - "LEADER_LOST"
 */
public interface Observer {
    void update(String event);
}

