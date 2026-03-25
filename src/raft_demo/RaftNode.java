package raft_demo;

import java.util.*;
import java.util.logging.Logger;

// Represents a single node in the Raft cluster.
// This class maintains the node's state (Follower, Candidate, or Leader)
// and implements the core Raft consensus logic that goes on with being a leader, follower, or candidate.
public class RaftNode {

    // Possible roles for a Raft node, must be one of these
    public enum Role { FOLLOWER, CANDIDATE, LEADER }

    // Latest term server has seen (initialized to 0 on first boot)
    public int currentTerm = 0;

    // CandidateId that received vote in current term
    public Integer votedFor = null;

    // Tracks current leader's ID
    public Integer currentLeaderId = null;

    // Log entries
    public List<RaftRPC.LogEntry> log = new ArrayList<>();

    // Index of highest log entry known to be committed
    public int commitIndex = -1;
    // Index of highest log entry 
    public int lastApplied = -1;

     //---------------------------------

    // For leaders, this resets after election
    // For each server, index of the highest log entry known to be replicated on that server
    public Map<Integer, Integer> matchIndex = new HashMap<>();

    // For leaders, index of the next log entry to send to each follower
    public Map<Integer, Integer> nextIndex = new HashMap<>();

     //---------------------------------

    // For each node its own id and role and the cluster members 
    // id for this node
    public int id;

    // Current role 
    public Role role = Role.FOLLOWER;

    // Map of all cluster members
    public Map<Integer, NodeInfo> clusterMembers;

    //---------------------------------

    //Timing and Election logic 
    // random election timeout 
    public long electionTimeout;

    // Timestamp of the last heartbeat or valid RPC received from leader
    public long lastHeartbeat;

    private final Random random = new Random();

    private Logger logger;

    public RaftNode(int id, Map<Integer, NodeInfo> clusterMembers) {
        this.id = id;
        this.clusterMembers = clusterMembers;
        resetElectionTimeout();
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    // Resets the election timeout to a random value between 3000ms and 5000ms.
    // I find this was the best but there may be better values for this.
    public void resetElectionTimeout() {
        this.electionTimeout = 3000 + random.nextInt(2000); 
        this.lastHeartbeat = System.currentTimeMillis();
    }

    // Checks if the time since the last heartbeat has exceeded the election timeout.
    public boolean isElectionTimeout() {
        return System.currentTimeMillis() - lastHeartbeat > electionTimeout;
    }

    //---------------------------------

    // handleRequestVote: Invoked by candidates to gather votes
    
    // Some rules for the vote:
    // 1. Reply false if term < currentTerm (no need to vote for an old term)

    // 2. If votedFor is null or candidateId, and candidate's log is at least as up-to-date as receiver's log, grant vote

    public synchronized RaftRPC.RequestVoteResults handleRequestVote(RaftRPC.RequestVoteArgs args) {
        logger.info("Received RequestVote from Node " + args.candidateId + " for term " + args.term);
        
        // 1. Reply false if term < currentTerm
        if (args.term < currentTerm) {
            logger.info("Vote denied to Node " + args.candidateId + ": term " + args.term + " < " + currentTerm);
            return new RaftRPC.RequestVoteResults(currentTerm, false);
        }

        // If RPC term is higher, update currentTerm and step down to follower
        if (args.term > currentTerm) {
            // Clear current leader as higher term found
            currentLeaderId = null;
            logger.info("Stepping down to follower for higher term " + args.term);
            stepDown(args.term);
        }

        // 2. Check if candidate's log is up-to-date
        boolean logUpToDate = false;
        int lastLogTerm = log.isEmpty() ? 0 : log.get(log.size() - 1).term;
        int lastLogIndex = log.size() - 1;

        if (args.lastLogTerm > lastLogTerm || 
           (args.lastLogTerm == lastLogTerm && args.lastLogIndex >= lastLogIndex)) {
            logUpToDate = true;
            logger.info("Candidate's log is up to date");
        }

        // 3. Grant vote if not yet voted in this term and log is ok
        if ((votedFor == null || votedFor == args.candidateId) && logUpToDate) {
            votedFor = args.candidateId;
            resetElectionTimeout();
            logger.info("Vote granted to Node " + args.candidateId + " for term " + currentTerm);
            return new RaftRPC.RequestVoteResults(currentTerm, true);
        }

        logger.info("Vote denied to Node " + args.candidateId + ": already voted or log not up to date");
        return new RaftRPC.RequestVoteResults(currentTerm, false);
    }

    // Transitions the node to the Candidate state and increments the term.
    public synchronized void startElection() {
        role = Role.CANDIDATE;
        currentTerm++;
        votedFor = id; // Vote for self
        resetElectionTimeout();
        logger.info("Node " + id + " starting election for term " + currentTerm);
    }

    //---------------------------------

    // used by leader to send log entries and as a heartbeat.
    // Rules to follow:

    // 1. Reply false if term < currentTerm (same as above)

    // 2. Reply false if log doesn't contain an entry at prevLogIndex whose term matches prevLogTerm (same as above)

    // 3. If an existing entry conflicts with a new one, delete the existing entry and all that follow it

    // 4. Append any new entries not already in the log

    // 5. If leaderCommit > commitIndex, set commitIndex = min(leaderCommit, index of last new entry)
    public synchronized RaftRPC.AppendEntriesResults handleAppendEntries(RaftRPC.AppendEntriesArgs args) {

        // 1. Reply false if term < currentTerm
        if (args.term < currentTerm) {
            logger.info("AppendEntries denied from Leader " + args.leaderId + ": term " + args.term + " < " + currentTerm);
            return new RaftRPC.AppendEntriesResults(currentTerm, false);
        }

        // Reset timeout because we heard from a valid leader
        logger.info("Resetting election timeout due to valid leader " + args.leaderId);
        // Update current leader ID
        currentLeaderId = args.leaderId;
        resetElectionTimeout();
        
        // If term is higher, update currentTerm
        if (args.term > currentTerm) {
            logger.info("Stepping down to follower for higher term " + args.term);
            stepDown(args.term);
        }
        // Recognize the sender as the valid leader
        role = Role.FOLLOWER;

        if (args.entries == null || args.entries.isEmpty()) {
            // Heartbeat received
            logger.info("Received heartbeat from Leader " + args.leaderId + " (Term: " + args.term + ")");
        }



        // 2. Reply false if log doesn't contain an entry at prevLogIndex whose term matches prevLogTerm
        if (args.prevLogIndex >= 0) {
            if (args.prevLogIndex >= log.size() || log.get(args.prevLogIndex).term != args.prevLogTerm) {
                logger.info("AppendEntries denied from Leader " + args.leaderId + ": log inconsistency at prevLogIndex " + args.prevLogIndex);
                return new RaftRPC.AppendEntriesResults(currentTerm, false);
            }
        }

        // 3 & 4. Append new entries and handle conflicts
        if (args.entries != null && !args.entries.isEmpty()) {
            logger.info("Appending " + args.entries.size() + " entries from Leader " + args.leaderId);
            int index = args.prevLogIndex + 1;
            for (RaftRPC.LogEntry entry : args.entries) {
                logger.info("    Processing log entry at index " + index + " with term " + entry.term);
                if (index < log.size()) {
                    if (log.get(index).term != entry.term) {
                        // Conflict 
                        logger.info("    Conflict detected at index " + index + ". Replacing existing entries.");
                        log = new ArrayList<>(log.subList(0, index));
                        log.add(entry);
                    }
                } else {
                    // No conflict
                    logger.info("    No conflict at index " + index + ". Appending entry.");
                    log.add(entry);
                }
                index++;
                logger.info("    Log size is now " + log.size());
            }
        }

        // 5. Update commitIndex
        if (args.leaderCommit > commitIndex) {
            commitIndex = Math.min(args.leaderCommit, log.size() - 1);
            logger.info("Updated commitIndex to " + commitIndex);
        }

        logger.info("AppendEntries from Leader " + args.leaderId + " processed successfully");
        return new RaftRPC.AppendEntriesResults(currentTerm, true);
    }

    // makes the node a Leader 
    public synchronized void becomeLeader() {
        role = Role.LEADER;
        // Update current leader ID
        currentLeaderId = id;
        int next = log.size();
        for (Integer memberId : clusterMembers.keySet()) {
            if (memberId != id) {
                matchIndex.put(memberId, -1);
                nextIndex.put(memberId, next);
            }
        }
        matchIndex.put(id, log.size() - 1);
        logger.info("    Node " + id + " became leader for term " + currentTerm);
    }

    // Append a new log entry as leader and update leader's own matchIndex.
    public synchronized int appendAsLeader(String command) {
        if (role != Role.LEADER) {
            throw new IllegalStateException("Only leader can append");
        }

        RaftRPC.LogEntry entry = new RaftRPC.LogEntry(currentTerm, command);
        log.add(entry);

        int lastIndex = log.size() - 1;
        matchIndex.put(id, lastIndex);
        return lastIndex;
    }

    // Reverts the node to Follower state when a higher term is encountered.
    public synchronized void stepDown(int term) {
        if (term > currentTerm) {
            currentTerm = term;
            votedFor = null;
            role = Role.FOLLOWER;
            logger.info("    Node " + id + " stepping down to follower for term " + currentTerm);
        }
    }
}
