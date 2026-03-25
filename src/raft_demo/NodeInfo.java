package raft_demo;


//THIS IS THE ABTRACTION FOR ONE OF THE NODES that stores the info for each node in the cluster
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
