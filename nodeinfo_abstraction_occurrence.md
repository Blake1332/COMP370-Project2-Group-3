# Abstraction–Occurrence Refactor (NodeInfo)

## Goal
Reduce duplicated “port math” and scattered node identity logic by introducing a single abstraction that represents each Raft node’s identity and networking info.

## What changed
### 1. Added `NodeInfo`
New file: `src/raft_demo/NodeInfo.java`

`NodeInfo` encapsulates:
- `id`
- `host` (currently `"localhost"`)
- `udpPort` (used for Raft RPC over UDP)
- `clientPort` (used for client requests over TCP)

### 2. Centralized node construction in `RaftConfig`
Updated file: `src/raft_demo/RaftConfig.java`

Added:
- `getNodeInfos(int nodeCount)`: returns `Map<Integer, NodeInfo>` for the whole cluster.

Kept backwards compatibility:
- `getClusterMembers(int nodeCount)` still exists, but it is now derived from `getNodeInfos(...)` (so existing code paths keep working).

### 3. Refactored `Client` to use `NodeInfo`
Updated file: `src/raft_demo/Client.java`

`Client` now stores `Map<Integer, NodeInfo>` instead of `Map<Integer, Integer>`.
Leader discovery and request forwarding now use `NodeInfo.getClientPort()` rather than re-computing ports.

### 4. Refactored `RaftServer` to use `NodeInfo` at startup
Updated file: `src/raft_demo/RaftServer.java`

`RaftServer.main` now uses `RaftConfig.getNodeInfos(nodeCount)` to get the node’s UDP/TCP ports.

The rest of the server still constructs the legacy `Map<Integer, Integer>` (id -> UDP port) because `RaftNode` currently expects that format; behavior is preserved.

### 5. Updated `GUI` client wiring
Updated file: `src/raft_demo/GUI.java`

When connecting the GUI’s client, it now uses `RaftConfig.getNodeInfos(nodeCount)`.

## Smoke test result
Recompiled `src/raft_demo/*.java` and started a 3-node cluster locally.
After waiting for election, the `Client` successfully discovered a leader and received:
`Job accepted by leader: test command`.

