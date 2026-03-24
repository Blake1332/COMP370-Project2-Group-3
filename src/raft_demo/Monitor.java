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

    private Monitor() {
    }

    public static synchronized Monitor getInstance() {
        if (instance == null) {
            instance = new Monitor();
        }
        return instance;
    }

    public void initializeForClusterSize(int nodeCount) {
        nodeProcesses = new ArrayList<>(Collections.nCopies(nodeCount, null));
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
    }

    public void stopAllNodes(int nodeCount) {
        for (int id = RaftConfig.MIN_NODE_ID; id <= nodeCount; id++) {
            stopNodeProcess(id);
        }
    }

    public void connectClient(Map<Integer, Integer> ports) {
        client = new Client(ports);
    }

    public void clearClient() {
        client = null;
    }

    public Client getClient() {
        return client;
    }
}
