package raft_demo;

/**
 * Abstraction–Occurrence pattern for Raft nodes.
 *
 * Each instance represents one occurrence (one node) in the
 * cluster, capturing all of the identifying and networking information in a
 * single place instead of re-computing ports throughout the codebase.
 * 
 * This significantly reduces the complexity of the codebase and makes it easier to maintain.
 */
public final class NodeInfo {
    private final int id;
    private final String host;
    private final int udpPort;
    private final int clientPort;

    public NodeInfo(int id, String host, int udpPort, int clientPort) {
        this.id = id;
        this.host = host;
        this.udpPort = udpPort;
        this.clientPort = clientPort;
    }

    public int getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public int getClientPort() {
        return clientPort;
    }
}

