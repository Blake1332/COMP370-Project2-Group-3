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

public class GUI extends JFrame {

    private Process[] nodeProcesses = new Process[3];
    private JButton[] nodeKillButtons = new JButton[3];
    private JButton startClusterBtn, stopClusterBtn, connectClientBtn, showLeaderBtn, sendBtn, resetBtn;
    private JCheckBox delay;
    private JTextField commandField;
    private JTextArea outputArea;
    private Client client;
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
        JComboBox<String> logDropdown = new JComboBox<>(new String[]{
            "View Logs", "Node 1", "Node 2", "Node 3", "Client"
        });
        String[] logFiles = {null, "node_1.log", "node_2.log", "node_3.log", "client.log"};
        logDropdown.addActionListener(e -> {
            int selected = logDropdown.getSelectedIndex();
            if (selected > 0) {
                currentLogFile = logFiles[selected];
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
            if (client != null) {
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
            }
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
        JPanel row4 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (int i = 1; i <= 3; i++) {
            int id = i;
            JButton killBtn = new JButton("Kill Node " + id);
            killBtn.setEnabled(false);
            killBtn.addActionListener(e -> onKillNode(id, killBtn));
            nodeKillButtons[id - 1] = killBtn;
            row4.add(killBtn);
        }
        buttons_jframe.add(row4);

        add(buttons_jframe, BorderLayout.NORTH);

        // OUTPUT
        outputArea = new JTextArea();
        outputArea.setEditable(false);
        add(new JScrollPane(outputArea), BorderLayout.CENTER);
    }
    //KILLING NODES
    private void onKillNode(int id, JButton btn) {
        if (nodeProcesses[id - 1] != null) {
            stopNodeProcess(id); 
            appendOutput("Node " + id + " killed manually.\n");
            btn.setText("Restart Node " + id);
            
            // If the killed node was the leader, log it
            if (lastKnownLeader.equals(String.valueOf(id))) {
                appendOutput("Current Leader (Node " + id + ") was killed.\n");
                lastKnownLeader = "unknown";
            }
        } else {
            startNode(id);
            appendOutput("Node " + id + " restarted manually.\n");
            btn.setText("Kill Node " + id);
        }
    }

    //STOPING AND STARTING BUTTON FUNCTIONS
    //--------------------------------------------------------------------------------

    private void onStartCluster() {
        startClusterBtn.setEnabled(false);
        appendOutput("Compiling...\n");
        new Thread(() -> {
            boolean compiled = compileProject();
            if (!compiled) {
                SwingUtilities.invokeLater(() -> {
                    appendOutput("Compilation failed.\n");
                    startClusterBtn.setEnabled(true);
                });
                return;
            }
            SwingUtilities.invokeLater(() -> appendOutput("Compilation successful.\n"));
            startNode(1); startNode(2); startNode(3);
            SwingUtilities.invokeLater(() -> {
                appendOutput("Node 1 started.\n");
                appendOutput("Node 2 started.\n");
                appendOutput("Node 3 started.\n");
                appendOutput("Cluster started successfully.\n");
                if (delay != null && delay.isSelected()) {
                    appendOutput("Network delay started (500ms heartbeats).");
                }
                stopClusterBtn.setEnabled(true);
                for (JButton btn : nodeKillButtons) {
                    btn.setEnabled(true);
                    btn.setText("Kill Node " + (Arrays.asList(nodeKillButtons).indexOf(btn) + 1));
                }
            });
        }).start();
    }

    //--------------------------------------------------------------------------------
    private void onStopCluster() {
        killAllNodes();
        stopClusterBtn.setEnabled(false);
        startClusterBtn.setEnabled(true);
        lastKnownLeader = "unknown";
        appendOutput("All nodes stopped.\n");
        for (JButton btn : nodeKillButtons) {
            btn.setEnabled(false);
            btn.setText("Kill Node " + (Arrays.asList(nodeKillButtons).indexOf(btn) + 1));
        }
    }

    private void onReset() {
        killAllNodes();
        client = null;
        lastKnownLeader = "unknown";
        startClusterBtn.setEnabled(true);
        stopClusterBtn.setEnabled(false);
        connectClientBtn.setEnabled(true);
        showLeaderBtn.setEnabled(false);
        commandField.setEnabled(false);
        sendBtn.setEnabled(false);
        outputArea.setText("");
        appendOutput("Reset complete.\n");
        for (JButton btn : nodeKillButtons) {
            btn.setEnabled(false);
            btn.setText("Kill Node " + (Arrays.asList(nodeKillButtons).indexOf(btn) + 1));
        }
    }

    // THIS IS JANK, JANK, JANK, im going crazy trying to get this to work dynamically tho 
    private void killAllNodes() {
        stopNodeProcess(1);
        stopNodeProcess(2);
        stopNodeProcess(3);
        appendOutput("All nodes destroyed.\n");
    }
    // this did the same as KillAllNodes, but more dynamic im trying to build towards more then 3 nodes
    private void stopNodeProcess(int id) {
        Process process = nodeProcesses[id - 1];
        if (process == null) {
            return;
        }

        process.destroyForcibly();
        try { 
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        nodeProcesses[id - 1] = null;
    }

    private void startNode(int id) {
        try { //THIS is a mess. but I forgot to do it and it works
            Process existing = nodeProcesses[id - 1];
            if (existing != null && existing.isAlive()) {
                stopNodeProcess(id); //edge case
            }
            if (delay != null && delay.isSelected()) {
                nodeProcesses[id - 1] = new ProcessBuilder("java", "-Draft.heartbeat.delay.ms=500", "-cp", "bin", "raft_demo.RaftServer", String.valueOf(id))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
            } else {
                nodeProcesses[id - 1] = new ProcessBuilder("java", "-cp", "bin", "raft_demo.RaftServer", String.valueOf(id))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
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
            "src/raft_demo/GUI.java"
        );
        return result == 0;
    }
    //--------------------------------------------------------------------------------
    
    //CLIENT
    private void onConnectClient() {
        //PORTS (change later if figured out how to do dynamic ports)
        Map<Integer, Integer> members = new HashMap<>();
        members.put(1, 8102);
        members.put(2, 8103);
        members.put(3, 8104);
        client = new Client(members);
        showLeaderBtn.setEnabled(true);

        // WE NEED TO USE A THREAD OR IT GOES UNRESPONSIVE
        new Thread(() -> {
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
        if (client == null) {
            appendOutput("Client is not connected.\n");
            showLeaderBtn.setEnabled(false);
            return;
        }

        final Client finalLeaderClient = client;
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
        if (cmd.isEmpty() || client == null) {
            return;
        }

        commandField.setText("");
        appendOutput("> " + cmd + "\n");

        // WE NEED TO USE A THREAD OR IT GOES UNRESPONSIVE
        new Thread(() -> {
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
