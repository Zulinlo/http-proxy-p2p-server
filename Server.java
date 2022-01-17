/**
 * Java TCP Server (localhost: 127.0.0.1)
 *
 * Author: Sam Wu
 * Date: 2021-11-10
 */

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Server {
    /**
     * Server information, inputted from command line
     */
    private static String credentialsFile = "credentials.txt";

    private static ServerSocket serverSocket;
    private static Integer serverPort;

    private static int blockDuration;
    private static int timeoutDuration;

    private ArrayList<String> activeClientNames;
    private HashMap<String, Integer> blockedUsers;
    private HashMap<String, String> credentials;
    private HashMap<String, User> users;

    // Construct server to avoid non static creation of Users problem
    public Server() {
        this.activeClientNames = new ArrayList<String>();
        this.blockedUsers = new HashMap<String, Integer>();

        // fetch credentials each time, in case new user was created
        // read existing users from credentials.txt into a hash table for ease of access
        this.credentials = new HashMap<String, String>();
        this.users = new HashMap<String, User>();
        try (BufferedReader br = new BufferedReader(new FileReader(credentialsFile))) {
            String userLogin;

            while ((userLogin = br.readLine()) != null) {
                String[] curr = userLogin.split(" ");
                if (curr.length == 0 || curr.length == 1)
                    continue;
                credentials.put(curr[0], curr[1]);
                users.put(userLogin.split(" ")[0], new User());
            }
        } catch (IOException e) {
            //e.printStackTrace();
        }
    }
    /**
     * Set a client to be online across all required states
     */
    public void addClient(ClientThread client, String username) {
        activeClientNames.add(username);
        users.get(username).setThread(client);
        users.get(username).setIsOnline(true);
    }

    /**
     * Set a client to be offline across all required states
     */
    public void removeClient(String username) {
        activeClientNames.remove(username);
        users.get(username).setThread(null);
        users.get(username).setIsOnline(false);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.out.println("===== Error usage: java TCPServer SERVER_PORT BLOCK_DURATION TIME_OUT =====");
            return;
        }

        // set up the server's socket
        serverPort = Integer.parseInt(args[0]);
        serverSocket = new ServerSocket(serverPort);

        blockDuration = Integer.parseInt(args[1]);
        timeoutDuration = Integer.parseInt(args[2]);

        System.out.println("===== Server is running ======");
        System.out.println("===== Waiting for connection request from clients...=====");
        Server server = new Server();

        // timer for blocked users
        // would be better to just put in timestamp and check value
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<String, Integer> user : server.blockedUsers.entrySet()) {
                    if (user.getValue() <= 0) {
                        server.blockedUsers.remove(user.getKey());
                    } else {
                        server.blockedUsers.put(user.getKey(), user.getValue() - 1);
                    }
                }
            }
        }, 0, 1, TimeUnit.SECONDS);

        while (true) {
            // when new conection request reaches the server, then socket establishes connection
            Socket clientSocket = serverSocket.accept();

            ClientThread clientThread = new ClientThread(clientSocket, server);
            clientThread.start();
        }
    }

    /**
     * define ClientThread for handling multi-threading issue
     * ClientThread needs to extend Thread and override run() method
     */
    private static class ClientThread extends Thread {
        private Server server;
        private Socket clientSocket;

        private DataInputStream inputStream;
        private DataOutputStream outputStream;

        private String username;
        private Timer timeoutDurationTimer;
        private boolean isClientAlive;

        public ClientThread (Socket socket, Server server) {
            this.server = server;
            this.clientSocket = socket;
            this.username = null;
            this.isClientAlive = true;

            try {
                this.outputStream = new DataOutputStream(socket.getOutputStream());
                this.inputStream = new DataInputStream(socket.getInputStream());
            } catch (IOException e) {
                //e.printStackTrace();
            }
        }

        public void run() {
            try {
                // user authentication phase
                if (loginUser()) {
                    // successful login
                    server.addClient(this, username);
                    broadcast(username, username + " logged in");
                    starttimeoutDurationTimer();
                } else {
                    // if user login fails, terminate the connection
                    System.out.println("A user has failed to login");
                    inputStream.close();
                    outputStream.close();
                    clientSocket.close();
                    isClientAlive = false;
                    return;
                }

                // first load up message queue of logged in user
                if (server.users.get(username).getMessageQueue() != null)
                    sendResponse(server.users.get(username).getMessageQueue());

                // constantly check for incoming requests
                while (isClientAlive) {
                    String req = readMessage();

                    // !message.matches(Pattern.quote("~!@#$%^&*_-+=`|(){}[]:;\"<>,.?/")) || !message.matches("^[a-zA-Z]+$"
                    if (req.equals("")) continue;

                    String[] reqSplitted = req.split(" ");
                    if (reqSplitted[0].equals("logout")) {
                        logout();
                    } else if (reqSplitted[0].equals("whoelse")) {
                        String res = "";
                        for (String user : server.activeClientNames) {
                            if (user != username && !server.users.get(user).hasBlacklisted(username)) {
                                res += user + "\n";
                            }
                        }

                        sendResponse(res);
                    } else if (reqSplitted[0].equals("whoelsesince")) {
                        if (reqSplitted.length == 2) {
                            displayActiveUsersSince(Integer.parseInt(reqSplitted[1]));
                        } else {
                            sendResponse("Error usage: whoelsesince <time>");
                        }
                    } else if (reqSplitted[0].equals("broadcast")) {
                        if (reqSplitted.length >= 2) {
                            broadcast(username, "===== Broadcast from " + username + ": " + req.substring(reqSplitted[0].length() + 1) + " =====");
                        } else {
                            sendResponse("Error usage: broadcast <message>");
                        }
                    } else if (reqSplitted[0].equals("message")) {
                        if (reqSplitted.length >= 3) {
                            String user = reqSplitted[1];

                            if (!server.users.containsKey(user))
                                sendResponse("Error. Invalid user");
                            else if (user.equals(username)) {
                                sendResponse("Error. You can't message yourself");
                            } else {
                                String message = username + ": " + req.substring(reqSplitted[0].length() + reqSplitted[1].length() + 2);
                                sendDirectMessage(message, username, user);
                            }
                        } else {
                            sendResponse("Error usage: message <user> <message>");
                        }
                    } else if (reqSplitted[0].equals("block")) {
                        if (reqSplitted.length == 2) {
                            User currUser = server.users.get(username);
                            if (reqSplitted[1].equals(username)) {
                                sendResponse("Error. Cannot block yourself");
                            } else if (!server.users.containsKey(reqSplitted[1])) {
                                sendResponse("Error. Invalid user");
                            } else if (currUser.hasBlacklisted(reqSplitted[1])) {
                                sendResponse(reqSplitted[1] + " has already been blocked");
                            } else {
                                currUser.blacklistAdd(reqSplitted[1]);
                                sendResponse(reqSplitted[1] + " has been blocked");
                            }
                        } else {
                            sendResponse("Error usage: block <user>");
                        }
                    } else if (reqSplitted[0].equals("unblock")) {
                        if (reqSplitted.length == 2) {
                            User currUser = server.users.get(username);
                            if (reqSplitted[1].equals(username)) {
                                sendResponse("Error. Cannot unblock yourself");
                            } else if (!server.users.containsKey(reqSplitted[1])) {
                                sendResponse("Error. Invalid user");
                            } else if (!currUser.hasBlacklisted(reqSplitted[1])) {
                                sendResponse(reqSplitted[1] + " was not blocked");
                            } else {
                                currUser.blacklistRemove(reqSplitted[1]);
                                sendResponse(reqSplitted[1] + " has been unblocked");
                            }
                        } else {
                            sendResponse("Error usage: unblock <user>");
                        }
                    } else if (reqSplitted[0].equals("setupprivate")) {
                        if (reqSplitted.length == 2) {
                            String user = reqSplitted[1];
                            if (user.equals(username)) {
                                sendResponse("startprivate|yourself");
                            } else if (server.users.containsKey(user)) {
                                User userData = server.users.get(user);
                                if (userData.hasBlacklisted(username)) {
                                    sendResponse("startprivate|blocked");
                                } else if (userData.isOnline()) {
                                    /**
                                     * Inspired by https://stackoverflow.com/questions/5757900/gethostaddress-and-getinetaddress-in-java
                                     */
                                    String ipAddress = userData.getSocket().getInetAddress().getHostAddress();
                                    int port = userData.getSocket().getPort();
                                    // initiate a P2P session
                                    sendResponse("startprivate|" + user + "|" + ipAddress + "|" + port);
                                } else {
                                    sendResponse("startprivate|offline");
                                }
                            } else {
                                sendResponse("startprivate|invalid");
                            }
                        }
                    } else {
                        sendResponse("That is an invalid command");
                    }
                    restartTimer();
                }
            } catch(Exception e) {
                close();
            }
        }

        /**
         * Handles user login, signup, authentication
         */
        private boolean loginUser() {
            boolean usernameEntered = false;
            String username = "";

            // request a username to be entered and verify it is valid
            sendResponse("Username: ");
            username = readMessage();

            // !message.matches(Pattern.quote("~!@#$%^&*_-+=`|(){}[]:;\"<>,.?/")) || !message.matches("^[a-zA-Z]+$"
            if (username.contains(" ") || username.equals("")) return false;

            // rerun fetch credentials in case connectsion initiated at the same time and a new user is created
            try (BufferedReader br = new BufferedReader(new FileReader(credentialsFile))) {
                String userLogin;
    
                while ((userLogin = br.readLine()) != null) {
                    String[] curr = userLogin.split(" ");
                    if (curr.length == 0 || curr.length == 1)
                        continue;
                    server.credentials.put(curr[0], curr[1]);
                    if ((server.users.get(userLogin.split(" ")[0]) == null)) 
                        server.users.put(userLogin.split(" ")[0], server.new User());
                }
            } catch (IOException e) {
                //e.printStackTrace();
            }

            for (Map.Entry<String, String> entry : server.credentials.entrySet()) {
                if (username.equals(entry.getKey())) {
                    usernameEntered = true;
                    break;
                }
            }

            // log in
            if (usernameEntered) {
                if (server.activeClientNames.contains(username)) {
                    sendResponse("This user is already logged in");
                    return false;
                } else if (server.blockedUsers.get(username) != null) {
                    sendResponse("Your account is blocked due to multiple login failures. Please try again later");
                    return false;
                }
            } else {
                // new user
                sendResponse("Password: ");
                String password = readMessage();

                try {
                    FileWriter myWriter = new FileWriter(credentialsFile, true);
                    myWriter.write("\n" + username + " " + password);

                    myWriter.close();
                } catch (IOException e) {
                    //e.printStackTrace();
                }

                server.users.put(username, server.new User());
                this.username = username;
                sendResponse("Successfully logged in! Welcome to the chat server!");
                sendResponse("name " + username);
                return true;
            }

            // password checking, check if to block user after 3 fails
            for (int i = 0; i < 3; i++) {
                sendResponse("Password: ");
                String password = readMessage();
                if (password.equals("")) continue;

                if (password.equals(server.credentials.get(username))) {
                    // succecssful login
                    this.username = username;
                    sendResponse("Successfully logged in! Welcome to the chat server!");
                    sendResponse("name " + username);
                    return true;
                }
            }

            sendResponse("Invalid password. Your account has been blocked. Please try again later.");
            server.blockedUsers.put(username, blockDuration);
            return false;
        }

        /**
         * Logs user out and closes thread
         */
        public void logout() {
            sendResponse("logout");
            broadcast(username, username + " has logged out");
            close();
        }

        /**
         * Sends response message to client
         */
        public void sendResponse(String message) {
            try {
                outputStream.writeUTF(message);
                outputStream.flush();
            } catch (IOException e) {
                close();
            }
        }

        /**
         * Reads request message from client
         */
        public String readMessage() {
            String message = "";
            try {
                message = inputStream.readUTF();
            } catch (IOException e) {
                close();
            }

            return message;
        }

        /**
         * Sends a broadcast message to all online and unblocked users
        */
        public void broadcast(String sender, String message) {
            ArrayList<String> users = new ArrayList<String>(server.activeClientNames);
            users.remove(sender);

            boolean hasBeenBlackListed = false;
            for (String username : users) {
                if (server.users.get(username).hasBlacklisted(sender)) {
                    hasBeenBlackListed = true;
                    continue;
                }
                sendDirectMessage(message, sender, username);
            }

            if (hasBeenBlackListed) {
                sendResponse("Your message could not be broadcasted too all users, as at least 1 user has blocked you");
            }
        }

        /**
         * Sends a message to another user from any user
         */
        public boolean sendDirectMessage(String message, String sender, String recipient) {
            User target = server.users.get(recipient);
            if (!target.hasBlacklisted(sender)) {
                if (target.isOnline()) {
                    ClientThread handler = target.getThread();
                    handler.sendResponse(message);
                } else {
                    target.sendToMessageQueue(message);
                }
                return true;
            }

            sendResponse("Your message could not be delivered as the recipient has blocked you");
            return false;
        }

        /**
         * Displays all active users since a specific amount of time back
         */
        public void displayActiveUsersSince(long secondsPrior) {
            long time = System.currentTimeMillis() / 1000 - secondsPrior;

            ArrayList<String> allUsers = new ArrayList<String>(server.users.keySet());
            for (Map.Entry<String, User> user : server.users.entrySet()) {
                if ((!user.getValue().isOnline() && user.getValue().getLastOnline() < time) || user.getValue().hasBlacklisted(username) || user.getKey().equals(username)) {
                    allUsers.remove(user.getKey());
                }
            }

            sendResponse(String.join("\n", allUsers));
        }

        /**
         * Initiate timeout timer, logout when times out
         */
        public void starttimeoutDurationTimer() {
            TimerTask timerTask = new TimerTask() {
                @Override
                public void run() {
                    sendResponse("Your current session has timed out");
                    logout();
                }
            };

            timeoutDurationTimer = new Timer();
            timeoutDurationTimer.schedule(timerTask, timeoutDuration * 1000);
        }

        /**
         * Restarts scheduled timer
         */
        public void restartTimer() {
            timeoutDurationTimer.cancel();
            starttimeoutDurationTimer();
        }

        public Socket getSocket() {
            return this.clientSocket;
        }

        /**
         * Removes client Thread from server, server stops execution process, close sockets
         * https://www.baeldung.com/a-guide-to-java-sockets inspired by
         */
        public void close() {
            try {
                server.removeClient(username);
                inputStream.close();
                outputStream.close();
                clientSocket.close();
                isClientAlive = false;
            } catch (IOException e) {
                //e.printStackTrace();
            } catch (NullPointerException e) {
                //
            }
        }
    }

    /**
     * This class is used for handling and tracking the state of a user
     */
    public class User {
        private ClientThread thread;

        private List<String> messageQueue;
        private HashSet<String> blacklist;
        private boolean isOnline;
        private long lastOnline;

        public User() {
            this.blacklist = new HashSet<String>();
            this.isOnline = false;
            this.thread = null;
            this.lastOnline	= System.currentTimeMillis() / 1000;
            this.messageQueue = new ArrayList<String>();
        }

        public String getMessageQueue() {
            if (messageQueue.size() == 0)
                return null;

            String messages = String.join("\n", messageQueue);
            messageQueue.clear();

            return messages;
        }

        public void sendToMessageQueue(String message) {
            messageQueue.add(message);
        }

        public void blacklistAdd(String user) {
            blacklist.add(user);
        }

        public boolean hasBlacklisted(String user) {
            return blacklist.contains(user);
        }

        public void blacklistRemove(String user) {
            blacklist.remove(user);
        }

        public ClientThread getThread() {
            return thread;
        }

        public void setThread(ClientThread thread) {
            this.thread = thread;
        }

        public boolean isOnline() {
            return isOnline;
        }

        public void setIsOnline(boolean status) {
            isOnline = status;
            setLastOnline();
        }

        public long getLastOnline() {
            return lastOnline;
        }

        public void setLastOnline() {
            this.lastOnline	= System.currentTimeMillis() / 1000;
        }

        public Socket getSocket() {
            return thread.getSocket();
        }
    }
}
