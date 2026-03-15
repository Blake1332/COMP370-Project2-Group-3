# COMP370-Project1-Group-3
Mike Buss, Bhupinder Singh Gill, Armaan Kandola, Brayden Schneider, Eric Thai
## Overview

Implement a distributed system. We are using the Raft consensus algorithm. The project provides a class for a Raft node with the standard roles (Follower, Candidate, Leader), leader election, and log replication. Each node is its own java program that can handle network communication: UDP for Raft (RequestVote, AppendEntries) and TCP for client requests. Execution can be run with a GUI (start/stop cluster, send commands, view logs --- newer/nicer so recommened) or headless(we are trying to move away from this).

### Overview

The demo runs a cluster of three Raft nodes:

1. Nodes 1,2 and 3 are RaftServers: Each has a unique ID, listening on a UDP port for Raft RPCs and a TCP port for clients. One node becomes leader via election. The leader sends heartbeats and tracks log entries.

2. Clients discovers the leader by querying each node’s port, then sends a request to the leader. If the response indicates as NOT_LEADER, the client does what the node responds with to find the leader.

3. GUI: Does it all, no need for any commands.Start/stop the cluster, connect a client, send commands, and view node/client logs. “Simulate network delay” and log dropdown for Node 1, Node 2, Node 3, or Client. 

4. **deprecated** --- Headless: Use `run_headless.sh` to compile, start three nodes in the background, and optionally run a client or inspect logs under `logs/`. You will need to run commands/ kill processes manuals 

## Install Guide

### Prerequisites

- **Java 17** or higher (or a compatible JDK)
- **Bash** or **Command Prompt** (or alternative)

### Setup Instructions

1. **Clone or download the repository**

2. Ensure `javac` and `java` are installed and can be found.

3. Follow either 1,2 or 3 below:

(1) (Linux/macOS):
   ```bash
   ./run_gui.sh
   ```
   If everything is working, the Raft Demo GUI window should open. Click Start Cluster, then Connect Client, and send a command to test.

 (2) Windows:
   ```bat
   run_windows.bat
   ```
   Or just click the bat file in your file explorer. 

3.3. Headless run **---deprecated**
   ```bash
   ./run_headless.sh
   ```
   This compiles the project and starts three Raft nodes in the background. Use the printed commands to tail logs or stop the nodes.

## Project Structure

```
COMP370-Project1-Group-3/
├── src/raft_demo/
│   ├── RaftNode.java      # Core Raft logic: roles, election, 
│   ├── RaftServer.java    # Network stuff
│   ├── RaftRPC.java       # RequestVote / AppendEntries
│   ├── Client.java        # Client
│   ├── Logger.java        # Logging
│   └── GUI.java           # Swing GUI and controls
├── uml/                   # Diagrams
├── run_gui.sh             # Launch GUI 
├── run_headless.sh        # headless
├── run_windows.bat        # Launch GUI 
├── LICENSE
└── README.md
```

## Ports Used

| Node | Raft RPC (UDP) | Client (TCP) |
|------|----------------|--------------|
| 1    | 9102           | 8102         |
| 2    | 9103           | 8103         |
| 3    | 9104           | 8104         |

