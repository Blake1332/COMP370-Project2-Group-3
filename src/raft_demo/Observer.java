package raft_demo;

public interface Observer {
    void update(String event);
}
