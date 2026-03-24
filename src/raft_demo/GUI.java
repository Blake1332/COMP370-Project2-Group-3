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
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); //WE NEED TO OVERRIDE THIS
        setSize(620, 520);
        setLocationRelativeTo(null);
        setResizable(false);

        //OVERRIDE THIS WILL KILL OUR WINDOW INSTEAD!
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                killAllNodes();
                dispose();
                System.exit(0);
            }
        });

        buildUI();
        monitor.addObserver(this);
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
            // REFRESH LEADER STATUS IF CLIENT IS CONNECTED
            Client client = monitor.getClient();
            if (client == null) {
                return;
            }
            new Thread(() -> {
                boolean found = client.discoverLeader();
                SwingUtilities.invokeLater(() -> {
                    if (found) {
                        String newLeader = getLeaderId(client);
                        if (!newLeader.equals(lastKnownLeader)) {
                            if (lastKnownLeader.equals("unknown")) {
                                appendOutput("Connected to new Leader who is node " + newLeader + "\n");
                            } else {
                                appendOutput("Leader changed from node " + lastKnownLeader + " to node " + newLeader + "\n");
                            }
                            lastKnownLeader = newLeader;
                        }
                    } else {
                        if (!lastKnownLeader.equals("unknown")) {
                            appendOutput("Leader lost. Trying to find new leader");
                            lastKnownLeader = "unknown";
                        }
                    }
                });
            }).start();
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
        if (monitor.getNodeProcess(id) != null) {
            monitor.stopNodeProcess(id);
            appendOutput("Node " + id + " killed manually.\n");
            btn.setText("Restart Node " + id);
            
            // If the killed node was the leader, log it
            if (lastKnownLeader.equals(String.valueOf(id))) {
                appendOutput("Current Leader (Node " + id + ") was killed.\n");
                lastKnownLeader = "unknown";
            }
        } else {
            monitor.startNode(id, nodeCount, delay != null && delay.isSelected());
            appendOutput("Node " + id + " restarted manually.\n");
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
            boolean simDelay = delay != null && delay.isSelected();
            for (int id = RaftConfig.MIN_NODE_ID; id <= nodeCount; id++) {
                monitor.startNode(id, nodeCount, simDelay);
            }
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
        killAllNodes();
        stopClusterBtn.setEnabled(false);
        startClusterBtn.setEnabled(true);
        clusterSizeDropdown.setEnabled(true);
        lastKnownLeader = "unknown";
        appendOutput("All nodes stopped.\n");
        setNodeButtonsEnabled(false);
    }

    private void onReset() {
        killAllNodes();
        monitor.clearClient();
        lastKnownLeader = "unknown";
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

    private void killAllNodes() {
        monitor.stopAllNodes(nodeCount);
        appendOutput("All nodes destroyed.\n");
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
            "src/raft_demo/Monitor.java",
            "src/raft_demo/Observer.java",
            "src/raft_demo/GUI.java"
        );
        return result == 0;
    }
    //--------------------------------------------------------------------------------
    
    //CLIENT
    private void onConnectClient() {
        monitor.connectClient(RaftConfig.getClientPorts(nodeCount));
        showLeaderBtn.setEnabled(true);

        // WE NEED TO USE A THREAD OR IT GOES UNRESPONSIVE
        new Thread(() -> {
            Client client = monitor.getClient();
            boolean found = client.discoverLeader();
            // UPDATE THE GUI
            SwingUtilities.invokeLater(() -> {
                if (found) {
                    lastKnownLeader = getLeaderId(client);
                    commandField.setEnabled(true);
                    sendBtn.setEnabled(true);
                    appendOutput("Connected and current leader is Node " + lastKnownLeader + "\n");
                } else {
                    lastKnownLeader = "unknown";
                    appendOutput("No leader found.\n");
                }
            });
        }).start();
    }

    private void onShowCurrentLeader() {
        showLeaderBtn.setEnabled(false);
        if (monitor.getClient() == null) {
            appendOutput("Client is not connected.\n");
            showLeaderBtn.setEnabled(false);
            return;
        }

        final Client finalLeaderClient = monitor.getClient();
        new Thread(() -> {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Boolean> future = executor.submit(finalLeaderClient::discoverLeader);
            boolean found = false;
            boolean timedOut = false;

            try {
                found = future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                timedOut = true;
                future.cancel(true);
            } catch (Exception e) {
                found = false;
            } finally {
                executor.shutdownNow();
            }

            final boolean finalFound = found;
            final boolean finalTimedOut = timedOut;
            SwingUtilities.invokeLater(() -> {
                if (finalTimedOut) {
                    appendOutput("timeout\n");
                } else if (finalFound) {
                    lastKnownLeader = getLeaderId(finalLeaderClient);
                    appendOutput("Current leader is Node " + lastKnownLeader + "\n");
                } else {
                    lastKnownLeader = "unknown";
                    appendOutput("Current leader is none.\n");
                }
                showLeaderBtn.setEnabled(true);
            });
        }).start();
    }

    //USES GETTER TO GET THE LEADER ID 
    private String getLeaderId(Client c) {
        Integer id = c.getCurrentLeaderId();
        if (id == null) {
            return "unknown";
        }else{
            return id.toString();
        }
    }

    private void onSendCommand() {
        String cmd = commandField.getText().trim();
        if (cmd.isEmpty() || monitor.getClient() == null) {
            return;
        }

        commandField.setText("");
        appendOutput("> " + cmd + "\n");

        // WE NEED TO USE A THREAD OR IT GOES UNRESPONSIVE
        new Thread(() -> {
            Client client = monitor.getClient();
            String response = client.sendRequest(cmd);
            // UPDATE THE GUI ON THE MAIN THREAD
            SwingUtilities.invokeLater(() -> {
                appendOutput("Response is " + response + "\n");
                
                // IF WE GOT A CONNECTION ERROR, TRY TO FIND NEW LEADER
                if (response.contains("ERROR") || response.contains("refused")) {
                    new Thread(() -> {
                        if (client.discoverLeader()) {
                            String newLeader = getLeaderId(client);
                            SwingUtilities.invokeLater(() -> {
                                if (!newLeader.equals(lastKnownLeader)) {
                                    appendOutput("Reconnected to new Leader who is Node " + newLeader + "\n");
                                    lastKnownLeader = newLeader;
                                }
                            });
                        }
                    }).start();
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
        monitor.initializeForClusterSize(nodeCount);
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

    @Override
    public void update(String event) {
        SwingUtilities.invokeLater(() -> outputArea.setToolTipText("Last monitor event: " + event));
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
