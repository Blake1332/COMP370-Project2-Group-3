package raft_demo;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client for the Raft cluster.
 * Discovers the leader and sends requests.
 */

public class Client {
    private final Map<Integer, NodeInfo> clusterMembers;
    private Integer currentLeaderId;
    public Integer getCurrentLeaderId() { return currentLeaderId; }
    
    public Client(Map<Integer, NodeInfo> clusterMembers) {
        this.clusterMembers = clusterMembers;
    }


    // Logger for client activities
    private static final Logger logger = Logger.getLogger(Client.class.getName());

    // Discovers the current leader by querying all cluster members
    public boolean discoverLeader() {
        logger.info("Discovering leader...");
        
        // Query each member for the leader
        for (Map.Entry<Integer, NodeInfo> member : clusterMembers.entrySet()) {
            try {
                Integer leaderId = queryNodeForLeader(member.getValue().getClientPort());
                if (leaderId != null) {
                    currentLeaderId = leaderId;
                    logger.info("Found leader: Node " + leaderId);
                    return true;
                }
            } catch (Exception e) {
                // Try next node
                logger.log(Level.WARNING, "Leader query failed: " + e.getMessage(), e);
            }
        }
        
        logger.info("No leader found");
        return false;
    }
    
    // Helper query to get the leader from a node
    private Integer queryNodeForLeader(int port) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(2000);
            
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            
            logger.info("    Querying node at port " + port + " for leader");
            ClientRequest request = new ClientRequest(ClientRequest.RequestType.GET_LEADER, null);
            out.writeObject(request);
            out.flush();
            
            // Get response
            ClientResponse response = (ClientResponse) in.readObject();
            logger.info("    Received leader response: " + response.leaderId);
            return response.leaderId;
        }
    }

    // Sends a request to the current leader
    public String sendRequest(String command) {
        // Discover leader if we don't know who it is
        if (currentLeaderId == null && !discoverLeader()) {
            return "ERROR: No leader available";
        }
        
        try {

            // Send request to leader's port
            NodeInfo leaderInfo = clusterMembers.get(currentLeaderId);
            if (leaderInfo == null) {
                currentLeaderId = null;
                return "ERROR: Unknown leader id " + currentLeaderId;
            }
            int port = leaderInfo.getClientPort();
            logger.info("    Sending request to leader at port " + port);
            
            try (Socket socket = new Socket("localhost", port)) {
                socket.setSoTimeout(5000);
                
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                
                // Send PROCESS_JOB request with command
                ClientRequest request = new ClientRequest(ClientRequest.RequestType.PROCESS_JOB, command);
                out.writeObject(request);
                out.flush();
                
                // Get response
                ClientResponse response = (ClientResponse) in.readObject();
                
                if (!response.success && response.message.startsWith("NOT_LEADER")) {
                    // Leader changed, rediscover and retry
                    currentLeaderId = null;
                    return sendRequest(command);
                }
                
                return response.message;
            }
        } catch (Exception e) {
            // Leader might have failed, set currentLeaderId to null to rediscover next time
            currentLeaderId = null;
            return "ERROR: " + e.getMessage();
        }
    }
    

    // Request message sent from client to server

    public static class ClientRequest implements Serializable {
        public enum RequestType { PROCESS_JOB, GET_LEADER }
        
        public RequestType type;
        public String command;
        
        public ClientRequest(RequestType type, String command) {
            this.type = type;
            this.command = command;
        }
    }
    

    //Response message sent from server to client

    public static class ClientResponse implements Serializable {
        public boolean success;
        public String message;
        public Integer leaderId;
        
        public ClientResponse(boolean success, String message, Integer leaderId) {
            this.success = success;
            this.message = message;
            this.leaderId = leaderId;
        }
    }
    
    // Main method for testing the client
    public static void main(String[] args) throws Exception {
        int nodeCount = RaftConfig.DEFAULT_CLUSTER_SIZE;
        if (args.length > 0) {
            nodeCount = Integer.parseInt(args[0]);
        }
        RaftConfig.validateClusterSize(nodeCount);
        Client client = new Client(RaftConfig.getNodeInfos(nodeCount));
        
        // Interactive mode
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        logger.info("Raft Client Started.");
        System.out.println("Give any input to send to PROCESS_JOB command, or type QUIT to exit:");
        
        while (true) {
            System.out.println("> ");
            String input = reader.readLine();
            
            if (input == null || input.equalsIgnoreCase("QUIT")) {
                break;
            }
            
            if (input.trim().isEmpty()) {
                continue;
            }

            logger.info("Received input, other than QUIT: " + input);
            // Send request to leader and receive response
            String response = client.sendRequest(input);
            logger.info("Response from leader: " + response);
        }
        
        // If input is QUIT, exit and close logger
        logger.info("Client shut down.");
        reader.close();
    }
}


