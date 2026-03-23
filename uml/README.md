# UML Diagrams for COMP370 Mini Project 2 (SRMS)

All diagrams are provided as:
- Mermaid source files (`.mmd`)
- Exported PNG files in the `/exports` folder

This set satisfies the UML requirements outlined in Step 5 (Refactor Code and UML).
- Use Case Diagram
- Class Diagram
- Sequence: Startup + Leader Election
- Sequence: Client Request (Normal)
- Sequence: Client Request (Failover)

---

## Diagram Files

### Use Case Diagram
- Source: `use-case-diagram.mmd`
- Export: `exports/use-case-diagram.png`

---

### Class Diagram
- Source: `class-diagram-required.mmd`
- Export: `exports/class-diagram-required.png`

---

### Sequence Diagrams

#### 1. Startup and Leader Election
- Source: `sequence-startup-and-election.mmd`
- Export: `exports/sequence-startup-and-election.png`

#### 2. Client Request (Normal)
- Source: `sequence-client-normal.mmd`
- Export: `exports/sequence-client-normal.png`

#### 3. Client Request (Failover)
- Source: `sequence-client-failover.mmd`
- Export: `exports/sequence-client-failover.png`

---

## Rendering Workflow

Diagrams were created using Mermaid and rendered locally via VS Code.

Process:
1. Open `.mmd` file
2. Render using Mermaid preview
3. Export to PNG for submission

---

## Design Note

The system uses a Raft-style consensus approach for leader election and log replication.  
No separate monitor process is used; this is an intentional design decision and is explained in the report.