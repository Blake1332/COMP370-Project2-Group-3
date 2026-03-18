package raft_demo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class GUI extends JFrame implements Observer {

    private final Monitor monitor = Monitor.getInstance();
    private java.util.List<JButton> nodeKillButtons = new ArrayList<>();
    private JButton startClusterBtn, stopClusterBtn, connectClientBtn, showLeaderBtn, sendBtn, resetBtn;
    private JComboBox<Integer> clusterSizeDropdown;
    private JComboBox<String> logDropdown;
    private JPanel nodeControlRow;
    private JCheckBox delay;
    private JTextField commandField;
    private JTextArea outputArea;
    private int nodeCount = RaftConfig.DEFAULT_CLUSTER_SIZE;
    private java.util.List<String> logFiles = new ArrayList<>();
    private String currentLogFile = null;
    private javax.swing.Timer logRefreshTimer;

    private String lastKnownLeader = "unknown"; //NOTE THIS IS JUST USED FOR THE GUI, NOT THE LOGIC

    //--------------------------------------------------------------------------------
    public GUI() {
        super("Raft Demo GUI");
        monitor.addObserver(this);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); //WE NEED TO OVERRIDE THIS
        setSize(620, 520);
        setLocationRelativeTo(null);
        setResizable(false);

        //OVERRIDE THIS WILL KILL OUR WINDOW INSTEAD!
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                monitor.stopCluster();
                dispose();
                System.exit(0);
            }
        });

        buildUI();
    }

    private void buildUI() {
        setLayout(new BorderLayout(5, 5));
        JPanel buttons_jframe = new JPanel(new GridLayout(4, 1));

        // START AND STOP BUTTONS
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(startClusterBtn = new JButton("Start Cluster"));
        row1.add(stopClusterBtn = new JButton("Stop Cluster"));
        row1.add(resetBtn = new JButton("Hard Reset"));
        row1.add(delay = new JCheckBox("Simulate network delay", false));
        row1.add(new JLabel("Nodes:"));
        clusterSizeDropdown = new JComboBox<>();
        for (int size = RaftConfig.MIN_CLUSTER_SIZE; size <= RaftConfig.MAX_CLUSTER_SIZE; size++) {
            clusterSizeDropdown.addItem(size);
        }
        clusterSizeDropdown.setSelectedItem(nodeCount);
        clusterSizeDropdown.addActionListener(e -> onClusterSizeChanged());
        row1.add(clusterSizeDropdown);
        logDropdown = new JComboBox<>();
        logDropdown.addActionListener(e -> {
            int selected = logDropdown.getSelectedIndex();
            if (selected > 0) {
                currentLogFile = logFiles.get(selected);
                readLog(currentLogFile);
                logRefreshTimer.start();
            } else {
                currentLogFile = null;
                logRefreshTimer.stop();
            }
        });
        row1.add(logDropdown);

        // REFRESH THE LOGS CHANGE DELAY IF NEEDED
        logRefreshTimer = new javax.swing.Timer(2000, e -> {
            if (currentLogFile != null) readLog(currentLogFile);
        });

        row1.add(new JButton("Clear Output") {{
            addActionListener(e -> {
                outputArea.setText("");
                currentLogFile = null;
                logRefreshTimer.stop();
                logDropdown.setSelectedIndex(0);
            });
        }});
        stopClusterBtn.setEnabled(false);
        startClusterBtn.addActionListener(e -> onStartCluster());
        stopClusterBtn.addActionListener(e -> onStopCluster());
        resetBtn.addActionListener(e -> onReset());
        buttons_jframe.add(row1);

        // CONNECT AND CURRENT LEADER BUTTONS
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(connectClientBtn = new JButton("Connect to Cluster"));
        connectClientBtn.addActionListener(e -> onConnectClient());
        row2.add(showLeaderBtn = new JButton("Show Current Leader"));
        showLeaderBtn.addActionListener(e -> onShowCurrentLeader());
        showLeaderBtn.setEnabled(false);
        buttons_jframe.add(row2);

        // INPUT
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row3.add(commandField = new JTextField(30));
        row3.add(sendBtn = new JButton("Send"));
        commandField.setEnabled(false);
        sendBtn.setEnabled(false);
        commandField.addActionListener(e -> onSendCommand());
        sendBtn.addActionListener(e -> onSendCommand());
        buttons_jframe.add(row3);

        // NODE FAILURE SIMULATION
        nodeControlRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons_jframe.add(nodeControlRow);

        add(buttons_jframe, BorderLayout.NORTH);

        // OUTPUT
        outputArea = new JTextArea();
        outputArea.setEditable(false);
        add(new JScrollPane(outputArea), BorderLayout.CENTER);

        initializeNodeState();
        refreshNodeButtons();
        refreshLogDropdownOptions();
    }
    //KILLING NODES
    private void onKillNode(int id, JButton btn) {
        if (monitor.isNodeRunning(id)) {
            monitor.killNode(id);
            btn.setText("Restart Node " + id);
        } else {
            monitor.restartNode(id);
            btn.setText("Kill Node " + id);
        }
    }

    //STOPING AND STARTING BUTTON FUNCTIONS
    //--------------------------------------------------------------------------------

    private void onStartCluster() {
        startClusterBtn.setEnabled(false);
        clusterSizeDropdown.setEnabled(false);
        appendOutput("Compiling...\n");
        new Thread(() -> {
            boolean compiled = compileProject();
            if (!compiled) {
                SwingUtilities.invokeLater(() -> {
                    appendOutput("Compilation failed.\n");
                    startClusterBtn.setEnabled(true);
                    clusterSizeDropdown.setEnabled(true);
                });
                return;
            }
            SwingUtilities.invokeLater(() -> appendOutput("Compilation successful.\n"));
            monitor.startCluster(nodeCount, delay != null && delay.isSelected());
            SwingUtilities.invokeLater(() -> {
                for (int id = RaftConfig.MIN_NODE_ID; id <= nodeCount; id++) {
                    appendOutput("Node " + id + " started.\n");
                }
                appendOutput("Cluster started successfully.\n");
                if (delay != null && delay.isSelected()) {
                    appendOutput("Network delay started (500ms heartbeats).");
                }
                stopClusterBtn.setEnabled(true);
                setNodeButtonsEnabled(true);
            });
        }).start();
    }

    //--------------------------------------------------------------------------------
    private void onStopCluster() {
        monitor.stopCluster();
        stopClusterBtn.setEnabled(false);
        startClusterBtn.setEnabled(true);
        clusterSizeDropdown.setEnabled(true);
        commandField.setEnabled(false);
        sendBtn.setEnabled(false);
        setNodeButtonsEnabled(false);
    }

    private void onReset() {
        monitor.stopCluster();
        startClusterBtn.setEnabled(true);
        stopClusterBtn.setEnabled(false);
        clusterSizeDropdown.setEnabled(true);
        connectClientBtn.setEnabled(true);
        showLeaderBtn.setEnabled(false);
        commandField.setEnabled(false);
        sendBtn.setEnabled(false);
        outputArea.setText("");
        appendOutput("Reset complete.\n");
        setNodeButtonsEnabled(false);
    }
    private boolean compileProject() {
        new File("bin").mkdirs();
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            appendOutput("Error: Java compiler not found.\n");
            return false;
        }
        int result = compiler.run(null, null, null,
            "-d", "bin",
            "src/raft_demo/RaftServer.java",
            "src/raft_demo/RaftNode.java",
            "src/raft_demo/RaftRPC.java",
            "src/raft_demo/Client.java",
            "src/raft_demo/RaftConfig.java",
            "src/raft_demo/NodeInfo.java",
            "src/raft_demo/Observer.java",
            "src/raft_demo/Monitor.java",
            "src/raft_demo/GUI.java"
        );
        return result == 0;
    }
    //--------------------------------------------------------------------------------
    
    //CLIENT
    private void onConnectClient() {
        lastKnownLeader = "unknown";
        commandField.setEnabled(false);
        sendBtn.setEnabled(false);
        showLeaderBtn.setEnabled(true);
        // WE NEED TO USE A THREAD OR IT GOES UNRESPONSIVE
        new Thread(() -> monitor.connectAndStartLeaderPolling(nodeCount)).start();
    }

    private void onShowCurrentLeader() {
        showLeaderBtn.setEnabled(false);
        if (!monitor.isClientConnected()) {
            appendOutput("Client is not connected.\n");
            showLeaderBtn.setEnabled(false);
            return;
        }

        new Thread(() -> {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Integer> future = executor.submit(monitor::discoverLeaderOnce);
            boolean timedOut = false;
            Integer leaderId = null;

            try {
                leaderId = future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                timedOut = true;
                future.cancel(true);
            } catch (Exception ignored) {
                leaderId = null;
            } finally {
                executor.shutdownNow();
            }

            final boolean finalTimedOut = timedOut;
            final Integer finalLeaderId = leaderId;
            SwingUtilities.invokeLater(() -> {
                if (finalTimedOut) {
                    appendOutput("timeout\n");
                } else {
                    if (finalLeaderId != null) {
                        lastKnownLeader = finalLeaderId.toString();
                        appendOutput("Current leader is Node " + lastKnownLeader + "\n");
                    } else {
                        lastKnownLeader = "unknown";
                        appendOutput("Current leader is none.\n");
                    }
                }
                showLeaderBtn.setEnabled(true);
            });
        }).start();
    }

    private void onSendCommand() {
        String cmd = commandField.getText().trim();
        if (cmd.isEmpty() || !monitor.isClientConnected()) {
            return;
        }

        commandField.setText("");
        appendOutput("> " + cmd + "\n");

        // WE NEED TO USE A THREAD OR IT GOES UNRESPONSIVE
        new Thread(() -> {
            String response = monitor.sendRequest(cmd);
            // UPDATE THE GUI ON THE MAIN THREAD
            SwingUtilities.invokeLater(() -> {
                appendOutput("Response is " + response + "\n");
                
                // IF WE GOT A CONNECTION ERROR, TRY TO FIND NEW LEADER
                if (response.contains("ERROR") || response.contains("refused")) {
                    // Leader polling may take a moment; trigger an immediate discovery+notification.
                    new Thread(() -> monitor.discoverLeaderOnceAndNotify()).start();
                }
            });
        }).start();
    }

    // READ A LOG FILE AND PRINT ITS CONTENTS TO THE OUTPUT AREA
    private void readLog(String filename) {
        try {
            outputArea.setText( filename );
            outputArea.append(new String(java.nio.file.Files.readAllBytes(new File("logs/" + filename).toPath())));
        } catch (IOException ex) {
            outputArea.setText(filename + ": " + ex.getMessage() + "\n");
        }
    }

    // OUTPUT
    private void appendOutput(String text) {
        outputArea.append(text);
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }

    @Override
    public void update(String event) {
        if (event == null) return;
        SwingUtilities.invokeLater(() -> {
            if (event.startsWith("CLIENT_CONNECTED:")) {
                String leaderId = event.substring("CLIENT_CONNECTED:".length());
                lastKnownLeader = leaderId;
                commandField.setEnabled(true);
                sendBtn.setEnabled(true);
                appendOutput("Connected and current leader is Node " + lastKnownLeader + "\n");
                return;
            }

            if (event.equals("CLIENT_NO_LEADER")) {
                lastKnownLeader = "unknown";
                commandField.setEnabled(false);
                sendBtn.setEnabled(false);
                appendOutput("No leader found.\n");
                return;
            }

            if (event.startsWith("LEADER_CHANGED:")) {
                String newLeaderId = event.substring("LEADER_CHANGED:".length());
                if (!newLeaderId.equals(lastKnownLeader)) {
                    if (lastKnownLeader.equals("unknown")) {
                        appendOutput("Connected to new Leader who is node " + newLeaderId + "\n");
                    } else {
                        appendOutput("Leader changed from node " + lastKnownLeader + " to node " + newLeaderId + "\n");
                    }
                    lastKnownLeader = newLeaderId;
                }
                return;
            }

            if (event.equals("LEADER_LOST")) {
                if (!"unknown".equals(lastKnownLeader)) {
                    appendOutput("Leader lost. Trying to find new leader\n");
                    lastKnownLeader = "unknown";
                }
                return;
            }

            if (event.equals("CLIENT_DISCONNECTED")) {
                lastKnownLeader = "unknown";
                commandField.setEnabled(false);
                sendBtn.setEnabled(false);
                return;
            }

            if (event.equals("CLUSTER_STOPPED")) {
                appendOutput("All nodes stopped.\n");
                return;
            }

            if (event.startsWith("NODE_KILLED:")) {
                String killedId = event.substring("NODE_KILLED:".length());
                appendOutput("Node " + killedId + " killed manually.\n");
                if (lastKnownLeader.equals(killedId)) {
                    // Leader polling will emit LEADER_LOST, but this keeps the UI responsive.
                    lastKnownLeader = "unknown";
                }
                return;
            }

            if (event.startsWith("NODE_RESTARTED:")) {
                String restartedId = event.substring("NODE_RESTARTED:".length());
                appendOutput("Node " + restartedId + " restarted manually.\n");
            }
        });
    }

    private void onClusterSizeChanged() {
        Integer selected = (Integer) clusterSizeDropdown.getSelectedItem();
        if (selected == null || selected == nodeCount) {
            return;
        }
        if (!startClusterBtn.isEnabled()) {
            clusterSizeDropdown.setSelectedItem(nodeCount);
            return;
        }

        nodeCount = selected;
        initializeNodeState();
        refreshNodeButtons();
        refreshLogDropdownOptions();
        appendOutput("Size is now " + nodeCount + " nodes.\n");
    }

    private void initializeNodeState() {
        // No per-node process state lives in the GUI anymore.
    }

    private void refreshNodeButtons() {
        nodeControlRow.removeAll();
        nodeKillButtons.clear();

        for (int id = RaftConfig.MIN_NODE_ID; id <= nodeCount; id++) {
            JButton killBtn = new JButton("Kill Node " + id);
            killBtn.setEnabled(false);
            final int nodeId = id;
            killBtn.addActionListener(e -> onKillNode(nodeId, killBtn));
            nodeKillButtons.add(killBtn);
            nodeControlRow.add(killBtn);
        }

        nodeControlRow.revalidate();
        nodeControlRow.repaint();
    }

    private void setNodeButtonsEnabled(boolean enabled) {
        for (int i = 0; i < nodeKillButtons.size(); i++) {
            JButton btn = nodeKillButtons.get(i);
            btn.setEnabled(enabled);
            btn.setText("Kill Node " + (i + 1));
        }
    }

    private void refreshLogDropdownOptions() {
        logFiles = new ArrayList<>();
        logDropdown.removeAllItems();
        logDropdown.addItem("View Logs");
        logFiles.add(null);

        for (int id = RaftConfig.MIN_NODE_ID; id <= nodeCount; id++) {
            logDropdown.addItem("Node " + id);
            logFiles.add("node_" + id + ".log");
        }
        logDropdown.addItem("Client");
        logFiles.add("client.log");

        currentLogFile = null;
        logRefreshTimer.stop();
        logDropdown.setSelectedIndex(0);
    }
     //--------------------------------------------------------------------------------
    // MAIN (ENTRY POINT)

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new File("logs").mkdirs(); //MAKE SURE LOGS HAVE SOMEWHERE TO GO
            GUI gui = new GUI();
            gui.setVisible(true);
        });
    }
}
