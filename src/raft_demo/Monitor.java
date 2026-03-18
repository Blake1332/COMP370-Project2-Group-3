package raft_demo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Singleton cluster manager
 *
 * Responsibilities:
 * - Start/stop/restart raft node processes (cluster lifecycle).
 * - Maintain a client to discover current leader.
 * - Notify registered observers about leader/client events.
 */
public final class Monitor {
    private static final Logger logger = Logger.getLogger(Monitor.class.getName());
    private static final Monitor instance = new Monitor();

    public static Monitor getInstance() {
        return instance;
    }

    private Monitor() {
    }

    private final Object clientLock = new Object();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private final Map<Integer, Process> nodeProcesses = new HashMap<>();

    private volatile int nodeCount = RaftConfig.DEFAULT_CLUSTER_SIZE;
    private volatile boolean simulateDelay = false;

    private volatile Client client = null;
    private volatile Integer lastLeaderId = null;

    private volatile boolean leaderPolling = false;
    private Thread leaderPollingThread = null;

    public void addObserver(Observer observer) {
        if (observer != null) observers.addIfAbsent(observer);
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    private void notifyObservers(String event) {
        for (Observer o : observers) {
            try {
                o.update(event);
            } catch (Exception e) {
                logger.warning("Observer update failed for event '" + event + "': " + e.getMessage());
            }
        }
    }

    public synchronized boolean isNodeRunning(int id) {
        Process p = nodeProcesses.get(id);
        return p != null && p.isAlive();
    }

    public synchronized void startCluster(int nodeCount, boolean simulateDelay) {
        // Assumes GUI compiles first; we only spawn processes.
        stopClusterInternal(false);
        this.nodeCount = nodeCount;
        this.simulateDelay = simulateDelay;

        for (int id = RaftConfig.MIN_NODE_ID; id <= nodeCount; id++) {
            startNodeInternal(id);
        }
    }

    public synchronized void stopCluster() {
        stopClusterInternal(true);
    }

    private synchronized void stopClusterInternal(boolean notifyEvent) {
        stopLeaderPolling();
        client = null;
        lastLeaderId = null;

        for (Process p : new ArrayList<>(nodeProcesses.values())) {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
        nodeProcesses.clear();

        if (notifyEvent) {
            notifyObservers("CLIENT_DISCONNECTED");
            notifyObservers("CLUSTER_STOPPED");
        }
    }

    public synchronized void killNode(int id) {
        killNodeInternal(id);
        notifyObservers("NODE_KILLED:" + id);
        // Leader polling will eventually detect leader loss/changes.
    }

    public synchronized void restartNode(int id) {
        killNodeInternal(id);
        startNodeInternal(id);
        notifyObservers("NODE_RESTARTED:" + id);
    }

    private synchronized void killNodeInternal(int id) {
        Process p = nodeProcesses.get(id);
        if (p == null) return;
        if (p.isAlive()) {
            p.destroyForcibly();
            try {
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        nodeProcesses.remove(id);
    }

    private void startNodeInternal(int id) {
        try {
            // Keep command identical to the GUI's previous implementation.
            List<String> cmd = new ArrayList<>();
            cmd.add("java");
            if (simulateDelay) {
                cmd.add("-Draft.heartbeat.delay.ms=500");
            }
            cmd.add("-cp");
            cmd.add("bin");
            cmd.add("raft_demo.RaftServer");
            cmd.add(String.valueOf(id));
            cmd.add(String.valueOf(nodeCount));

            File logsDir = new File("logs");
            if (!logsDir.exists()) logsDir.mkdirs();

            ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT);

            Process process = pb.start();
            nodeProcesses.put(id, process);
        } catch (IOException e) {
            logger.warning("Failed to start node " + id + ": " + e.getMessage());
        }
    }

    /**
     * Connects the client and begins periodic leader polling.
     * This method should be called from a background thread (does network I/O).
     */
    public void connectAndStartLeaderPolling(int nodeCount) {
        synchronized (this) {
            stopLeaderPolling();
            this.nodeCount = nodeCount;
            lastLeaderId = null;
            client = new Client(RaftConfig.getNodeInfos(nodeCount));
        }

        leaderPolling = true;
        leaderPollingThread = new Thread(() -> {
            // Poll loop: discover leader and emit events on transitions.
            boolean sentNoLeader = false;
            while (leaderPolling) {
                Integer leaderId;
                synchronized (clientLock) {
                    try {
                        boolean found = client != null && client.discoverLeader();
                        leaderId = found ? client.getCurrentLeaderId() : null;
                    } catch (Exception e) {
                        leaderId = null;
                    }
                }

                Integer prev = lastLeaderId;
                if (leaderId != null) {
                    sentNoLeader = false;
                    if (prev == null) {
                        lastLeaderId = leaderId;
                        notifyObservers("CLIENT_CONNECTED:" + leaderId);
                    } else if (!leaderId.equals(prev)) {
                        lastLeaderId = leaderId;
                        notifyObservers("LEADER_CHANGED:" + leaderId);
                    }
                } else {
                    if (prev != null) {
                        lastLeaderId = null;
                        notifyObservers("LEADER_LOST");
                    } else if (!sentNoLeader) {
                        notifyObservers("CLIENT_NO_LEADER");
                        sentNoLeader = true;
                    }
                }

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        leaderPollingThread.setDaemon(true);
        leaderPollingThread.start();
    }

    public Integer discoverLeaderOnce() {
        Client c = client;
        if (c == null) return null;
        synchronized (clientLock) {
            boolean found = c.discoverLeader();
            if (!found) return null;
            return c.getCurrentLeaderId();
        }
    }

    /**
     * Single leader discovery, updating internal lastLeaderId and notifying observers
     * if it changed. Useful for user actions (e.g., after a failed request).
     */
    public void discoverLeaderOnceAndNotify() {
        Integer leaderId;
        synchronized (clientLock) {
            if (client == null) return;
            try {
                boolean found = client.discoverLeader();
                leaderId = found ? client.getCurrentLeaderId() : null;
            } catch (Exception e) {
                leaderId = null;
            }
        }

        Integer prev = lastLeaderId;
        if (leaderId != null) {
            if (prev == null) {
                lastLeaderId = leaderId;
                notifyObservers("CLIENT_CONNECTED:" + leaderId);
            } else if (!leaderId.equals(prev)) {
                lastLeaderId = leaderId;
                notifyObservers("LEADER_CHANGED:" + leaderId);
            }
        } else {
            if (prev != null) {
                lastLeaderId = null;
                notifyObservers("LEADER_LOST");
            }
        }
    }

    public synchronized String sendRequest(String command) {
        if (client == null) return "ERROR: Client is not connected";
        synchronized (clientLock) {
            return client.sendRequest(command);
        }
    }

    public synchronized void stopLeaderPolling() {
        leaderPolling = false;
        Thread t = leaderPollingThread;
        leaderPollingThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    public synchronized boolean isClientConnected() {
        return client != null;
    }
}

