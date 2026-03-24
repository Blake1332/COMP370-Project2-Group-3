package raft_demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Monitor {

    private static Monitor instance;

    private List<Process> nodeProcesses = new ArrayList<>();
    private Client client;
    private final List<Observer> observers = new ArrayList<>();

    private Monitor() {
    }

    public static synchronized Monitor getInstance() {
        if (instance == null) {
            instance = new Monitor();
        }
        return instance;
    }

    public void addObserver(Observer o) {
        observers.add(o);
    }

    public void removeObserver(Observer o) {
        observers.remove(o);
    }

    public void notifyObservers(String event) {
        for (Observer o : new ArrayList<>(observers)) {
            o.update(event);
        }
    }

    public void initializeForClusterSize(int nodeCount) {
        nodeProcesses = new ArrayList<>(Collections.nCopies(nodeCount, null));
        notifyObservers("cluster_initialized");
    }

    public Process getNodeProcess(int id) {
        return nodeProcesses.get(id - 1);
    }

    public void startNode(int id, int clusterSize, boolean simulateDelay) {
        try {
            Process existing = nodeProcesses.get(id - 1);
            if (existing != null && existing.isAlive()) {
                stopNodeProcess(id);
            }
            if (simulateDelay) {
                nodeProcesses.set(id - 1, new ProcessBuilder("java", "-Draft.heartbeat.delay.ms=2500", "-cp", "bin", "raft_demo.RaftServer", String.valueOf(id), String.valueOf(clusterSize))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start());
            } else {
                nodeProcesses.set(id - 1, new ProcessBuilder("java", "-cp", "bin", "raft_demo.RaftServer", String.valueOf(id), String.valueOf(clusterSize))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start());
            }
            notifyObservers("node_started:" + id);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopNodeProcess(int id) {
        Process process = nodeProcesses.get(id - 1);
        if (process == null) {
            return;
        }

        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        nodeProcesses.set(id - 1, null);
        notifyObservers("node_stopped:" + id);
    }

    public void stopAllNodes(int nodeCount) {
        for (int id = RaftConfig.MIN_NODE_ID; id <= nodeCount; id++) {
            stopNodeProcess(id);
        }
        notifyObservers("all_nodes_stopped");
    }

    public void connectClient(Map<Integer, Integer> ports) {
        client = new Client(ports);
        notifyObservers("client_connected");
    }

    public void clearClient() {
        client = null;
        notifyObservers("client_cleared");
    }

    public Client getClient() {
        return client;
    }
}
