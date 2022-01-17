# http-proxy-p2p-server
A proxy server that allows multi-threaded connections from clients, as well as direct P2P connections.

Proxy server commands:
- broadcast [message]
- whoelse
- whoelsesince [time seconds]
- message [username] [message]
- block [username]
- unblock [username]
- logout

P2P commands:
- startprivate [username]
- private [username] [message]
- stopprivate [username]

### Executing program

```bash
javac Server.java
java Server SERVER_PORT BLOCK_DURATION TIME_OUT
javac Client.java
java Client SERVER_IP SERVER_PORT
```
