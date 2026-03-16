package raft_demo;

import java.util.HashMap;
import java.util.Map;


public final class RaftConfig {
    public static final int UDP_BASE_PORT = 9101; //these are static final, they are constant and should not be changed after initialization
    public static final int CLIENT_BASE_PORT = 8101;
    public static final int MIN_NODE_ID = 1;
    public static final int MIN_CLUSTER_SIZE = 3;
    public static final int MAX_CLUSTER_SIZE = 7;
    public static final int DEFAULT_CLUSTER_SIZE = MIN_CLUSTER_SIZE;

    public static Map<Integer, Integer> getClusterMembers(int nodeCount) {
        validateClusterSize(nodeCount); //this gets the cluser members like 1-> 9102, 2-> 9103, 3-> 9104
        Map<Integer, Integer> members = new HashMap<>();
        for (int id = MIN_NODE_ID; id <= nodeCount; id++) {
            members.put(id, UDP_BASE_PORT + id);
        }
        return members; 
    }

    public static Map<Integer, Integer> getClientPorts(int nodeCount) {
        validateClusterSize(nodeCount); //this gets the client ports like 1-> 8102, 2-> 8103, 3-> 8104
        Map<Integer, Integer> members = new HashMap<>();
        for (int id = MIN_NODE_ID; id <= nodeCount; id++) {
            members.put(id, clientPort(id));
        }
        return members;
    }

    public static int clientPort(int id) { //helper method/ small utility function, used in RaftServer.java in a few places
        return CLIENT_BASE_PORT + id;
    }

    public static int UDPPort(int id) { //unused as of now...
        return UDP_BASE_PORT + id;
    }

    public static boolean isValidNodeId(int id, int nodeCount) { //this checks if the node id is valid used in RaftServer.java
        if (nodeCount < MIN_CLUSTER_SIZE || nodeCount > MAX_CLUSTER_SIZE) {
            return false;
        } else if (id < MIN_NODE_ID || id > nodeCount) {
            return false;
        } else {
            return true;
        }
    }

    public static void validateClusterSize(int nodeCount) { //this checks if the cluster size is valid used in RaftServer.java and Client
        if (nodeCount < MIN_CLUSTER_SIZE || nodeCount > MAX_CLUSTER_SIZE) {
            throw new IllegalArgumentException(
                "Cluster size must be between " + MIN_CLUSTER_SIZE + " and " + MAX_CLUSTER_SIZE + ": " + nodeCount
            );
        } else {
            return;
        }
    }
}
