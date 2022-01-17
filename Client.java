/*
 * Java TCP Client
 *
 * Author: Sam Wu
 * Date: 2021-11-10
 * */

import java.net.*;
import java.io.*;
import java.util.HashMap;
import java.util.Scanner;

public class Client {
    private static String serverHost;
    private static Integer serverPort;

    private static ServerSocket serverSocket;
    private static Socket clientSocket;

    private static DataOutputStream outputStream;
    private static DataInputStream inputStream;

    private static Scanner scanner;
    private String username;
    private HashMap<String, P2PConnection> P2PConnections;


    // Construct client to avoid non static creation of P2Pconnection problems
    public Client() {
        this.P2PConnections = new HashMap<String, P2PConnection>();
        this.username = "";
    }

    public String getUsername() {
        return username;
    }

    public HashMap<String, P2PConnection> getP2PConnections() {
        return P2PConnections;
    }

    public void addP2PSession(String user, P2PConnection handler) {
        P2PConnections.put(user, handler);
    }

    public void removeP2PSession(String user) {
        if (P2PConnections.containsKey(user)) {
            P2PConnections.remove(user);
            System.out.println("Private connection with " + user + " has stopped");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("===== Error usage: java TCPClient SERVER_IP SERVER_PORT =====");
            return;
        }

        scanner = new Scanner(System.in);

        serverHost = args[0];
        serverPort = Integer.parseInt(args[1]);

        // define socket for client
        clientSocket = new Socket(serverHost, serverPort);

        // define DataInputStream instance which would be used to receive response from the server
        // define DataOutputStream instance which would be used to send message to the server
        outputStream = new DataOutputStream(clientSocket.getOutputStream());
        inputStream = new DataInputStream(clientSocket.getInputStream());

        Client client = new Client();

        // initialise ServerSocket for P2P connections
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(clientSocket.getLocalAddress(), clientSocket.getLocalPort()));

        // initialise receiver and sender
        ClientOutput sender = new ClientOutput(client);
        ClientInput receiver = new ClientInput(client);
        sender.start();
        receiver.start();

        // for P2P incoming connections
        while (true) {
            Socket newConnection = serverSocket.accept();

            P2PConnection handler = new P2PConnection(client, newConnection, "");
            handler.start();
        }
    }

    public void close() {
        try {
            inputStream.close();
            outputStream.close();
            serverSocket.close();
        } catch (IOException e) {
            //e.printStackTrace();
        }
    }

    /**
     * Thread that listens to client inputs
     */
    private static class ClientOutput extends Thread {
        private Client client;

        public ClientOutput(Client client) {
            this.client = client;
        }

        /**
         * Listens to client input and reads each line to appropriately respond
         */
        public void run() {
            while (true) {
                String message = scanner.nextLine();

                // if p2p related then don't use server
                // TODO: confirm P2P connection
                if (!handleP2PMessage(message)) {
                    if (message.equals("logout")) {
                        client.close();
                        break;
                    }

                    try {
                        outputStream.writeUTF(message);
                        outputStream.flush();
                    } catch (IOException e) {
                        //e.printStackTrace();
                    }

                    if (message.equals("This user is already logged in") || message.equals("Invalid password. Your account is blocked due to multiple login failures. Please try again later"))
                        client.close();
                }
            }
        }

        /**
         * If Client input is p2p connection related, then directly communicate to appropriate threads rather than use server
         */
        public boolean handleP2PMessage(String message) {
            String[] splitMessage = message.split(" ");

            try {
                if (splitMessage[0].equals("startprivate")) {
                    if (splitMessage.length == 2) {
                        // check if connection with specific user already exists
                        if (client.getP2PConnections().get(splitMessage[1]) != null) {
                            System.out.println("Private connection with " + splitMessage[1] + " is already active");
                        } else {
                            // retrieve IP and port of target user to P2P with
                            outputStream.writeUTF("setupprivate " + splitMessage[1]);
                            outputStream.flush();
                        }
                    } else {
                        System.out.println("Invalid command. Use format: startprivate <user>");
                    }
                } else if (splitMessage[0].equals("private")) {
                    if (splitMessage.length >= 3) {
                        if (splitMessage[1].equals(client.username)) {
                            System.out.println("Error: Cannot send a private message to yourself");
                        } else if (client.getP2PConnections().get(splitMessage[1]) == null) {
                            System.out.println("Error: No private connection with " + splitMessage[1]);
                        } else if (!client.getP2PConnections().get(splitMessage[1]).isAllowed()) {
                            System.out.println("Error: " + splitMessage[1] + " has not confirmed the P2P connection");
                        } else {
                            // send private message
                            String msg = client.username + "(private): " + message.substring(splitMessage[0].length() + splitMessage[1].length() + 2);
                            client.getP2PConnections().get(splitMessage[1]).sendResponse(msg);
                        }
                    } else {
                        System.out.println("Invalid command. Use format: private <user> <message>");
                    }
                } else if (splitMessage[0].equals("stopprivate")) {
                    if (splitMessage.length == 2) {
                        if (client.getP2PConnections().get(splitMessage[1]) == null) {
                            System.out.println("Error: Startprivate was not executed with " + splitMessage[1]);
                        } else {
                            client.getP2PConnections().get(splitMessage[1]).close();
                        }
                    } else {
                        System.out.println("Invalid command. Use format: stopprivate <user>");
                    }
                } else {
                    return false;
                }
            } catch (IOException e) {
                //e.printStackTrace();
                return false;
            }

            return true;
        }
    }

    /**
     * Thread that listens for incoming responses
     */
    private static class ClientInput extends Thread {
        private Client client;

        public ClientInput(Client client) {
            this.client = client;
        }

        /**
         * Listens to incoming responses and reads each line to appropriately receive
         */
        public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String message = inputStream.readUTF();
                    String[] splitMessage = message.split(" ");
                    
                    if (splitMessage[0].equals("name") && splitMessage.length == 2) {
                        client.username = splitMessage[1];
                    } else if (!handleP2PMessage(message)) {
                        if (!message.equals("logout")) {
                            System.out.println(message);
                        }
                        
                        if (message.equals("Your current session has timed out") || message.equals("logout")) {
                            client.close();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                //e.printStackTrace();
            }
        }

        /**
         * Listens to whenever server responds back and gives p2p target IP and port details
         */
        public boolean handleP2PMessage(String message) {
            String[] splitMessage = message.split("\\|");

            if (splitMessage[0].equals("startprivate")) {
                if (splitMessage.length == 4) {
                    try {
                        // connect to the target user on a new socket, https://www.baeldung.com/a-guide-to-java-sockets
                        Socket newSocket = new Socket(splitMessage[2], Integer.parseInt(splitMessage[3]));

                        // initiate and start a new P2PConnection for the P2P session
                        P2PConnection receiver = new P2PConnection(client, newSocket, splitMessage[1]);
                        receiver.start();
                    } catch (IOException e) {
                        //e.printStackTrace();
                    }
                // handles error format of length 2 when trying to start p2p startup
                } else if (splitMessage.length == 2) {
                    if (splitMessage[1].equals("yourself")) {
                        System.out.println("Error: cannot start session with yourself");
                    } else if (splitMessage[1].equals("blocked")) {
                        System.out.println("Error: target user has blocked you");
                    } else if (splitMessage[1].equals("invalid")) {
                        System.out.println("Error: target user is invalid");
                    } else {
                        System.out.println("Error: target user is offline");
                    }
                }
                return true;
            }

            return false;
        }
    }

    /**
     * Thread of a P2P connection from this client to another
     */
    private static class P2PConnection extends Thread {
        private String targetUsername;
        private Client client;
        private Socket socket;

        private boolean isAllowed;

        private DataOutputStream outStream;
        private DataInputStream inStream;

        public P2PConnection(Client client, Socket socket, String targetUsername) {
            try {
                outStream = new DataOutputStream(socket.getOutputStream());
                inStream = new DataInputStream(socket.getInputStream());
            } catch (IOException e) {
                //e.printStackTrace();
            }
            this.client = client;
            this.targetUsername = targetUsername;
            this.socket = socket;
            // TODO: set isAllowed to false, then set to true when target sends "y"
            this.isAllowed = true;
        }

        /**
         * Handles all p2p transactions between this other client thread and the main client
         */
        @Override
        public void run() {
            try {
                String otherUser = "";
                if (targetUsername.isEmpty()) {
                    otherUser = inStream.readUTF();
                    this.targetUsername = otherUser;

                    outStream.writeUTF(client.username);
                    outStream.flush();
                } else {
                    // otherwise this client sent the outgoing request
                    outStream.writeUTF(client.getUsername());
                    outStream.flush();
                    
                    otherUser = inStream.readUTF();
                    if (otherUser.equals(targetUsername)) {
                        System.out.println("Started private connection with " + otherUser);
                    } else {
                        close();
                        return;
                    }
                }

                client.addP2PSession(otherUser, this);

                while (!Thread.currentThread().isInterrupted()) {
                    String message = inStream.readUTF();
                    System.out.println(message);
                }
            } catch (IOException e) {
                //e.printStackTrace();
                close();
            }
        }

        public boolean isAllowed() {
            return isAllowed;
        }

        public void setIsAllowed(boolean bool) {
            this.isAllowed = bool;
        }

        public void close() {
            try {
                inStream.close();
                outStream.close();
                socket.close();
                client.removeP2PSession(targetUsername);
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                //e.printStackTrace();
            }
        }

        public void sendResponse(String message) {
            try {
                outStream.writeUTF(message);
                outStream.flush();
            } catch (IOException e) {
                //e.printStackTrace();
                close();
            }
        }
    }
}
