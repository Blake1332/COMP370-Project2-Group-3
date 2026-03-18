# Update: Monitor Singleton + Observer Events

This update introduces a thin “backend controller” for the demo so the Swing `GUI` is no longer responsible for cluster process lifecycle and leader polling logic.

## Key changes

### New `Observer` interface
Path: `src/raft_demo/Observer.java`

- Defines a single method: `update(String event)`
- Events are string-based (simple convention) such as:
  - `CLIENT_CONNECTED:<id>`
  - `LEADER_CHANGED:<id>`
  - `LEADER_LOST`
  - `CLIENT_NO_LEADER`

### New `Monitor` singleton (cluster + leader orchestration)
Path: `src/raft_demo/Monitor.java`

- Singleton access: `Monitor.getInstance()`
- Responsibilities:
  - Start/stop/restart the Raft node processes (`startCluster`, `stopCluster`, `killNode`, `restartNode`)
  - Create a `Client` and poll for the current leader in a background thread
  - Emit Observer events on leader/client transitions

#### Follow-up: node events emitted by `Monitor`
`Monitor` also emits simple lifecycle events for node actions:
- `NODE_KILLED:<id>`
- `NODE_RESTARTED:<id>`

### `GUI` now implements `Observer`
Path: `src/raft_demo/GUI.java`

- Registers itself via `monitor.addObserver(this)`
- Delegates cluster lifecycle actions to `Monitor`:
  - “Start Cluster” -> `monitor.startCluster(...)`
  - “Stop Cluster” / window close -> `monitor.stopCluster()`
  - Node kill/restart buttons -> `monitor.killNode(...)` / `monitor.restartNode(...)`
- Leader UI updates are driven by Observer events rather than GUI polling
- Node button handlers are now even thinner: `GUI` relies on `GUI.update(...)` to append node stop/start messages

## Verification

- Recompiled `src/raft_demo/*.java` successfully.
- Ran a local 3-node headless smoke test:
  - leader election completes after a short warmup
  - `Client` can discover the leader
  - `PROCESS_JOB` is accepted by the leader

