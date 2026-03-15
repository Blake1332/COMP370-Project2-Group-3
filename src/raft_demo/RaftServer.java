package raft_demo;

import java.net.*;
import java.util.*;
import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;


 //The network server for a Raft node.
 //Handles communication, serialization, and the main loop.
public class RaftServer {
    private static final int HEARTBEAT_DELAY_MS = 500;

    private static void sleepHeartbeatDelay() throws InterruptedException {
        Thread.sleep(HEARTBEAT_DELAY_MS);
    }

    private DatagramSocket socket;
    private ServerSocket clientSocket;
    private RaftNode raftNode;
    private int port;
    private int clientPort;

    private Logger logger;
    private final Map<Integer, Integer> lastSentIndexByFollower = new HashMap<>();

    
     // Initializes the server with its ID, port, and cluster members.

    public RaftServer(int id, int port, int clientPort, Map<Integer, Integer> clusterMembers) throws Exception {
        this.port = port;
        this.clientPort = clientPort;
        this.socket = new DatagramSocket(port);
        this.clientSocket = new ServerSocket(clientPort);
        this.raftNode = new RaftNode(id, clusterMembers);
        this.logger = Logger.getLogger(RaftServer.class.getName() + ".Node-" + id);
        this.raftNode.setLogger(this.logger);
    }

     // Starts the server's background threads and main logic loop.

    public void start() {
        logger.info("Server " + raftNode.id + " started on port " + port + ", client port " + clientPort);

        //Network Receiver Thread 
        // Continuously listens for incoming UDP packets and dispatches them to handlePacket
        new Thread(() -> {
            byte[] buffer = new byte[4096];
            while (true) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    logger.info("Received packet from " + packet.getAddress() + ":" + packet.getPort() + " sent to handlePacket");
                    handlePacket(packet);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Error handling packet: " + e.getMessage(), e);
                }
            }
        }).start();

        //Client Connection Thread
        // Continuously listens for incoming TCP connections from clients
        new Thread(() -> {
            while (true) {
                try {
                    Socket client = clientSocket.accept();
                    logger.info("Client connected from " + client.getInetAddress() + ":" + client.getPort());
                    // Handle each client in a separate thread
                    new Thread(() -> handleClientConnection(client)).start();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Error accepting client connection: " + e.getMessage(), e);
                }
            }
        }).start();

        //Main Logic Loop
        // Periodically checks for election timeouts or sends heartbeats if leader
        while (true) {
            try {
                Thread.sleep(500); // Tick rate of 500ms (Slower for visibility)
                synchronized (raftNode) {
                    if (raftNode.role == RaftNode.Role.LEADER) {
                        sendHeartbeats();
                    } else if (raftNode.isElectionTimeout()) {
                        startElection();
                    }
                    
                    // State Machine Application
                    // Apply committed entries that haven't been applied yet
                    while (raftNode.lastApplied < raftNode.commitIndex) {
                        raftNode.lastApplied++;
                        if (raftNode.lastApplied < raftNode.log.size()) {
                            RaftRPC.LogEntry entry = raftNode.log.get(raftNode.lastApplied);
                            logger.info("Applied to State Machine: " + entry.command + " at index " + raftNode.lastApplied);
                        }
                    }
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error in main loop: " + e.getMessage(), e);
            }
        }
    }

    
    // Deserializes incoming packets and routes them to the  handler.
     
    private void handlePacket(DatagramPacket packet) throws Exception {
        Object obj = deserialize(packet.getData());
        synchronized (raftNode) {

            if (obj instanceof RaftRPC.RequestVoteArgs) {
                // Handle a vote request from another node
                logger.info("Handling RequestVoteArgs from " + packet.getAddress() + ":" + packet.getPort());
                RaftRPC.RequestVoteResults res = raftNode.handleRequestVote((RaftRPC.RequestVoteArgs) obj);
                sendResponse(res, packet.getAddress(), packet.getPort());

            } else if (obj instanceof RaftRPC.AppendEntriesArgs) {
                // Handle a heartbeat or log replication request from a leader
                logger.info("Handling AppendEntriesArgs from " + packet.getAddress() + ":" + packet.getPort());
                sleepHeartbeatDelay();
                RaftRPC.AppendEntriesResults res = raftNode.handleAppendEntries((RaftRPC.AppendEntriesArgs) obj);
                sendResponse(res, packet.getAddress(), packet.getPort());

            } else if (obj instanceof RaftRPC.RequestVoteResults) {
                // Handle a vote response we received after starting an election
                logger.info("Handling RequestVoteResults from " + packet.getAddress() + ":" + packet.getPort());
                handleVoteResult((RaftRPC.RequestVoteResults) obj);

            } else if (obj instanceof RaftRPC.AppendEntriesResults) {
                // Handle a response to a log replication request we sent
                logger.info("Handling AppendEntriesResults from " + packet.getAddress() + ":" + packet.getPort());
                int fromId = -1;
                for (Map.Entry<Integer, Integer> entry : raftNode.clusterMembers.entrySet()) {
                    if (entry.getValue() == packet.getPort()) {
                        fromId = entry.getKey();
                        break;
                    }
                }
                handleAppendEntriesResult((RaftRPC.AppendEntriesResults) obj, fromId);
            }
        }
    }

    private int votesReceived = 0;

    
    // Initiates an election by requesting votes from all other cluster members.
     
    private void startElection() throws Exception {
        logger.info("Election timeout! Starting election for term " + (raftNode.currentTerm + 1));
        raftNode.startElection();
        votesReceived = 1; // Vote for self ofc

        logger.info("Requesting votes from other nodes...");
        RaftRPC.RequestVoteArgs args = new RaftRPC.RequestVoteArgs(
            raftNode.currentTerm, raftNode.id, 
            raftNode.log.size() - 1, 
            raftNode.log.isEmpty() ? 0 : raftNode.log.get(raftNode.log.size() - 1).term
        );
        

        for (Map.Entry<Integer, Integer> member : raftNode.clusterMembers.entrySet()) {
            if (member.getKey() != raftNode.id) {
                logger.info("Requesting vote from node " + member.getKey() + " at port " + member.getValue());
                sendRequest(args, "localhost", member.getValue());
            }
        }
    }

    
    //Processes a vote response and transitions to leader if a majority is reached.
    private void handleVoteResult(RaftRPC.RequestVoteResults res) {
        if (res.term > raftNode.currentTerm) {
            logger.info("Received higher term (" + res.term + "), stepping down");
            raftNode.stepDown(res.term);
            return;
        }
        if (raftNode.role == RaftNode.Role.CANDIDATE && res.voteGranted) {
            votesReceived++;
            logger.info("Vote received! Total votes: " + votesReceived + "/" + raftNode.clusterMembers.size());
            if (votesReceived > raftNode.clusterMembers.size() / 2) {
                logger.info("Majority votes received! Becoming leader for term " + raftNode.currentTerm);
                raftNode.becomeLeader();
            }
        }
    }

    
    //Sends heartbeats to all followers.
    //heartbeats are just empty AppendEntries Requests
    private void sendHeartbeats() throws Exception {
        if (System.currentTimeMillis() - raftNode.lastHeartbeat < 1500) {
            logger.info("Less than 1500ms since last heartbeat, skipping heartbeat send");
            return;
        }
        raftNode.lastHeartbeat = System.currentTimeMillis();
        logger.info("Sending heartbeats to followers...");
        sleepHeartbeatDelay();
        for (Map.Entry<Integer, Integer> member : raftNode.clusterMembers.entrySet()) {
            if (member.getKey() != raftNode.id) {
                logger.info("Sending heartbeat to node " + member.getKey() + " at port " + member.getValue());
                int nextIndex = raftNode.nextIndex.getOrDefault(member.getKey(), raftNode.log.size());
                if (nextIndex > raftNode.log.size()) {
                    nextIndex = raftNode.log.size();
                }
                int prevLogIndex = nextIndex - 1;
                int prevLogTerm = (prevLogIndex >= 0 && prevLogIndex < raftNode.log.size())
                    ? raftNode.log.get(prevLogIndex).term
                    : 0;
                List<RaftRPC.LogEntry> entries = null;
                if (nextIndex < raftNode.log.size()) {
                    entries = new ArrayList<>(raftNode.log.subList(nextIndex, raftNode.log.size()));
                    logger.info("Sending " + entries.size() + " entries to node " + member.getKey() + " starting at index " + nextIndex);
                    int lastSentIndex = nextIndex + entries.size() - 1;
                    lastSentIndexByFollower.put(member.getKey(), lastSentIndex);
                }
                RaftRPC.AppendEntriesArgs args = new RaftRPC.AppendEntriesArgs(
                    raftNode.currentTerm, raftNode.id,
                    prevLogIndex,
                    prevLogTerm,
                    entries, raftNode.commitIndex
                );
                sendRequest(args, "localhost", member.getValue());
            }
        }
    }

    
     // Processes log replication responses and updates replication progress.

    private void handleAppendEntriesResult(RaftRPC.AppendEntriesResults res, int fromId) {
        if (res.term > raftNode.currentTerm) {
            logger.info("Received higher term (" + res.term + "), stepping down");
            raftNode.stepDown(res.term);
            return;
        }
        
        if (raftNode.role == RaftNode.Role.LEADER) {
            if (res.success) {
                // Update the match index for this follower
                logger.info("AppendEntries successful from node " + fromId + ", updating matchIndex");
                int lastSentIndex = lastSentIndexByFollower.getOrDefault(fromId, raftNode.matchIndex.getOrDefault(fromId, -1));
                if (lastSentIndex >= 0) {
                    raftNode.matchIndex.put(fromId, lastSentIndex);
                    raftNode.nextIndex.put(fromId, lastSentIndex + 1);
                }
                updateCommitIndex();
            } else {
                // Back off nextIndex for retry
                int nextIndex = raftNode.nextIndex.getOrDefault(fromId, raftNode.log.size());
                if (nextIndex > 0) {
                    raftNode.nextIndex.put(fromId, nextIndex - 1);
                    logger.info("AppendEntries failed for node " + fromId + ", decrementing nextIndex to " + (nextIndex - 1));
                }
            }
        }
    }

    
    // Checks if a majority of nodes have replicated an entry and updates commitIndex.
    private void updateCommitIndex() {
        int lastIndex = raftNode.log.size() - 1;
        for (int n = lastIndex; n > raftNode.commitIndex; n--) {
            if (raftNode.log.get(n).term != raftNode.currentTerm) {
                continue;
            }
            int count = 1; // self
            for (int match : raftNode.matchIndex.values()) {
                if (match >= n) {
                    count++;
                }
            }
            if (count > raftNode.clusterMembers.size() / 2) {
                raftNode.commitIndex = n;
                logger.info("Majority reached! Committed up to index " + raftNode.commitIndex);
                break;
            }
        }
    }

    // Networking Helper stuff

    private void sendRequest(Object obj, String host, int port) throws Exception {
        byte[] data = serialize(obj);
        DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName(host), port);
        socket.send(packet);
    }

    private void sendResponse(Object obj, InetAddress address, int port) throws Exception {
        byte[] data = serialize(obj);
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
        socket.send(packet);
    }

    private byte[] serialize(Object obj) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ObjectOutputStream os = new ObjectOutputStream(out);
        os.writeObject(obj);
        return out.toByteArray();
    }

    private Object deserialize(byte[] data) throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        ObjectInputStream is = new ObjectInputStream(in);
        return is.readObject();
    }

    // Client Connection Handler
    private void handleClientConnection(Socket client) {
        try {
            ObjectInputStream in = new ObjectInputStream(client.getInputStream());
            ObjectOutputStream out = new ObjectOutputStream(client.getOutputStream());
            
            Client.ClientRequest request = (Client.ClientRequest) in.readObject();
            logger.info("Received client request: " + request.type);
            
            Client.ClientResponse response;
            
            // Use of synchronized to ensure thread safety when accessing raftNode
            synchronized (raftNode) {
                if (request.type == Client.ClientRequest.RequestType.GET_LEADER) {
                    // Return current leader ID
                    Integer leaderId = raftNode.role == RaftNode.Role.LEADER ? raftNode.id : raftNode.currentLeaderId;
                    logger.info("Responding with leader ID: " + leaderId);
                    response = new Client.ClientResponse(true, "Leader: " + leaderId, leaderId);
                    
                } else if (request.type == Client.ClientRequest.RequestType.PROCESS_JOB) {
                    if (raftNode.role == RaftNode.Role.LEADER) {
                        // Process the job as leader
                        logger.info("Processing job as leader: " + request.command);
                        raftNode.appendAsLeader(request.command);
                        response = new Client.ClientResponse(true, "Job accepted by leader: " + request.command, raftNode.id);
                    } else {
                        // Not the leader, redirect
                        Integer leaderId = raftNode.currentLeaderId;
                        logger.info("Not leader, redirecting to: " + leaderId);
                        response = new Client.ClientResponse(false, "NOT_LEADER", leaderId);
                    }
                } else {
                    response = new Client.ClientResponse(false, "Unknown request type", null);
                }
            }
            
            out.writeObject(response);
            out.flush();
            client.close();
            logger.info("Client connection handled and closed");
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error handling client connection: " + e.getMessage(), e);
        }
    }

    
     // Main entry point to start a node.
     
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: RaftServer <id>");
            return;
        }
        int id = Integer.parseInt(args[0]);
        
        // Define ports for the nodes
        Map<Integer, Integer> members = new HashMap<>();
        members.put(1, 9102);
        members.put(2, 9103);
        members.put(3, 9104);

        if (!members.containsKey(id)) {
            System.out.println("Invalid Node ID: " + id);
            return;
        }


        int port = members.get(id);
        int clientPort = 8101 + id; // Client ports: 8102, 8103, 8104
        RaftServer server = new RaftServer(id, port, clientPort, members);

        // Add shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.logger.info("Shutting down server " + id);
                server.socket.close();
                server.clientSocket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));

        server.start();
    }
}
