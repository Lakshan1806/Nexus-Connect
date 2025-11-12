package com.nexusconnect.servicebackend.nio;

import com.nexusconnect.servicebackend.user.UserCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;


public class NioChatServer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(NioChatServer.class);
    private static final int READ_BUF = 64 * 1024;
    private static final int HISTORY_LIMIT = 200;

    private final int port;
    private final UserCredentialService credentialService;
    private final ExecutorService workers =
            Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));

    private final ConcurrentHashMap<String, Presence> online = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SocketChannel, Session> sessions = new ConcurrentHashMap<>();
    private final Deque<ChatMessage> history = new ConcurrentLinkedDeque<>();
    
    // Whiteboard sessions
    private final ConcurrentHashMap<Long, WhiteboardSession> whiteboardSessions = new ConcurrentHashMap<>();
    private final AtomicLong whiteboardSessionIdCounter = new AtomicLong(0);


    private volatile boolean running = false;
    private Selector selector;
    private ServerSocketChannel server;
    private Thread selectorThread;

    public NioChatServer(int port, UserCredentialService credentialService) {
        this.port = port;
        this.credentialService = credentialService;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        selector = Selector.open();
        server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.bind(new InetSocketAddress("0.0.0.0", port));
        server.register(selector, SelectionKey.OP_ACCEPT);
        running = true;
        selectorThread = new Thread(this, "nio-selector");
        selectorThread.start();
        log.info("NIO server started on :{}", port);
    }

    public synchronized void stop() {
        running = false;
        try {
            selector.wakeup();
        } catch (Exception ignored) {
        }
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        try {
            if (selector != null) selector.close();
        } catch (Exception ignored) {
        }
        try {
            if (selectorThread != null) selectorThread.join(1000);
        } catch (InterruptedException ignored) {
        }
        workers.shutdownNow();
        log.info("NIO server stopped");
    }

    @Override
    public void run() {
        try {
            while (running) {
                selector.select();
                var it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    var key = it.next();
                    it.remove();
                    try {
                        if (!key.isValid()) continue;
                        if (key.isAcceptable()) onAccept();
                        if (key.isReadable()) onRead(key);
                        if (key.isWritable()) onWrite(key);
                    } catch (CancelledKeyException ignored) {
                    } catch (Exception e) {
                        log.error("Key error", e);
                        closeKey(key);
                    }
                }
            }
        } catch (ClosedSelectorException ignored) {
        } catch (Exception e) {
            log.error("Selector loop crash", e);
        } finally {
            for (SelectionKey k : new ArrayList<>(selector.keys())) closeKey(k);
        }
    }

    private void onAccept() throws IOException {
        SocketChannel ch = server.accept();
        if (ch == null) return;
        ch.configureBlocking(false);
        SelectionKey key = ch.register(selector, SelectionKey.OP_READ);
        Session s = new Session(ch, key);
        sessions.put(ch, s);
        key.attach(s);
        log.info("Accepted {}", ch.getRemoteAddress());
    }

    private void onRead(SelectionKey key) throws IOException {
        Session s = (Session) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();

        int n = ch.read(s.readBuf);
        if (n == -1) {
            disconnect(s, "EOF");
            return;
        }
        if (n == 0) return;

        s.readBuf.flip();
        CharBuffer chars = StandardCharsets.UTF_8.decode(s.readBuf);
        s.lineBuffer.append(chars);
        s.readBuf.clear();

        int idx;
        while ((idx = s.lineBuffer.indexOf("\n")) >= 0) {
            String line = s.lineBuffer.substring(0, idx).trim();
            s.lineBuffer.delete(0, idx + 1);
            if (!line.isEmpty()) {
                String srcIp = ((InetSocketAddress) ch.getRemoteAddress()).getAddress().getHostAddress();
                String finalLine = line;
                workers.submit(() -> handleLine(s, finalLine, srcIp));
            }
        }
    }

    private void onWrite(SelectionKey key) throws IOException {
        Session s = (Session) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        while (true) {
            ByteBuffer buf = s.writeQueue.peek();
            if (buf == null) break;
            ch.write(buf);
            if (buf.hasRemaining()) break;
            s.writeQueue.poll();
        }
        if (s.writeQueue.isEmpty()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    private void handleLine(Session s, String line, String srcIp) {
        String[] parts = line.split(":", 10);
        String command = parts[0].trim().toUpperCase(Locale.ROOT);
        try {
            switch (command) {
                case "LOGIN" -> handleLogin(s, parts, srcIp);
                case "MSG" -> handleGlobalMsg(s, line);
                case "PEER" -> handleAskPeer(s, parts);
                case "USERS" -> sendUsersList(s);
                case "WHITEBOARD_OPEN" -> handleWhiteboardOpen(s, parts);
                case "WHITEBOARD_DRAW" -> handleWhiteboardDraw(s, parts);
                case "WHITEBOARD_CLEAR" -> handleWhiteboardClear(s, parts);
                case "WHITEBOARD_CLOSE" -> handleWhiteboardClose(s, parts);
                case "WHITEBOARD_SYNC" -> handleWhiteboardSync(s, parts);
                default -> sendFrame(s, "ERROR:unknown command");
            }
        } catch (Exception e) {
            log.warn("Failed to process frame '{}': {}", line, e.getMessage());
            sendFrame(s, "ERROR:bad frame");
        }
    }

    private void handleLogin(Session s, String[] parts, String srcIp) {
        if (parts.length < 3) {
            sendFrame(s, "LOGIN_FAIL:missing credentials");
            return;
        }
        String user = parts[1].trim();
        String pass = parts[2].trim();
        Integer fileTcp = parts.length >= 4 ? intOrNull(parts[3]) : null;
        Integer voiceUdp = parts.length >= 5 ? intOrNull(parts[4]) : null;

        if (user.isEmpty() || pass.isEmpty()) {
            sendFrame(s, "LOGIN_FAIL:missing credentials");
            return;
        }
        if (!credentialService.verifyCredentials(user, pass)) {
            sendFrame(s, "LOGIN_FAIL:bad credentials");
            return;
        }

        Presence prev = online.put(user, s);
        if (prev instanceof Session prevSession && prevSession != s) {
            disconnect(prevSession, "relogin");
        }

        s.username = user;
        s.ip = srcIp;
        s.fileTcp = fileTcp != null ? fileTcp : -1;
        s.voiceUdp = voiceUdp != null ? voiceUdp : -1;

        sendFrame(s, "LOGIN_SUCCESS:" + user);
        sendUsersList(s);
        broadcastUserEvent(user, true, "USER_JOINED", s);
        broadcastUserList(s);
        log.info("LOGIN {} from {} (fileTcp={}, voiceUdp={})", user, srcIp, s.fileTcp, s.voiceUdp);
    }

    private void handleGlobalMsg(Session s, String frame) {
        if (!s.isAuthed()) {
            sendFrame(s, "ERROR:login first");
            return;
        }
        String text = frame.indexOf(':') >= 0 ? frame.substring(frame.indexOf(':') + 1) : "";
        text = text.trim();
        if (text.isEmpty()) {
            return;
        }
        ChatMessage msg = new ChatMessage(s.username, text, System.currentTimeMillis() / 1000);
        recordMessage(msg);
        broadcastChat(msg);
    }

    private void handleAskPeer(Session s, String[] parts) {
        if (!s.isAuthed()) {
            sendFrame(s, "ERROR:login first");
            return;
        }
        if (parts.length < 2) {
            sendFrame(s, "ERROR:missing target");
            return;
        }
        String target = parts[1].trim();
        Presence t = online.get(target);
        if (t == null) {
            sendFrame(s, "PEER:" + target + ":offline");
            return;
        }
        sendFrame(s, String.join(":",
                "PEER",
                target,
                t.ip(),
                String.valueOf(t.fileTcp()),
                String.valueOf(t.voiceUdp()),
                t.viaNio() ? "nio" : "http"
        ));
    }

    private void sendUsersList(Session s) {
        sendFrame(s, "USER_LIST:" + encodeUserList());
    }

    private void broadcastUserList() {
        broadcastUserList(null);
    }

    private void broadcastUserList(Session exclude) {
        broadcastExcept(exclude, "USER_LIST:" + encodeUserList());
    }

    private String encodeUserList() {
        return online.values().stream()
                .sorted(Comparator.comparing(Presence::username))
                .map(this::encodePresence)
                .collect(Collectors.joining(";"));
    }

    private String encodePresence(Presence presence) {
        return String.join(",",
                presence.username(),
                presence.ip(),
                String.valueOf(presence.fileTcp()),
                String.valueOf(presence.voiceUdp()),
                presence.viaNio() ? "nio" : "http"
        );
    }

    // ========== Whiteboard Handler Methods ==========
    
    private Session findSessionByUsername(String username) {
        for (Session session : sessions.values()) {
            if (username.equals(session.username())) {
                return session;
            }
        }
        return null;
    }
    
    private void handleWhiteboardOpen(Session s, String[] parts) {
        if (!s.isAuthed()) {
            sendFrame(s, "ERROR:login first");
            return;
        }
        if (parts.length < 2) {
            sendFrame(s, "ERROR:missing peer username");
            return;
        }
        String peerUsername = parts[1].trim();
        
        // Check if peer is online
        Presence peerPresence = online.get(peerUsername);
        if (peerPresence == null) {
            sendFrame(s, "ERROR:peer offline");
            return;
        }
        
        // Check if session already exists between these two users
        WhiteboardSession existingSession = whiteboardSessions.values().stream()
                .filter(ws -> ws.hasUser(s.username()) && ws.hasUser(peerUsername))
                .findFirst()
                .orElse(null);
        
        if (existingSession != null) {
            // Reuse existing session
            existingSession.updateActivity();
            sendFrame(s, "WHITEBOARD_CREATED:" + existingSession.getSessionId());
            
            // Notify peer of rejoin
            Session peerSession = findSessionByUsername(peerUsername);
            if (peerSession != null) {
                sendFrame(peerSession, "WHITEBOARD_INVITATION:" + existingSession.getSessionId() + ":" + s.username());
            }
            return;
        }
        
        // Create new session
        long sessionId = whiteboardSessionIdCounter.incrementAndGet();
        WhiteboardSession newSession = new WhiteboardSession(sessionId, s.username(), peerUsername);
        whiteboardSessions.put(sessionId, newSession);
        
        sendFrame(s, "WHITEBOARD_CREATED:" + sessionId);
        
        // Notify peer
        Session peerSession = findSessionByUsername(peerUsername);
        if (peerSession != null) {
            sendFrame(peerSession, "WHITEBOARD_INVITATION:" + sessionId + ":" + s.username());
        }
    }
    
    private void handleWhiteboardDraw(Session s, String[] parts) {
        if (!s.isAuthed()) {
            sendFrame(s, "ERROR:login first");
            return;
        }
        // WHITEBOARD_DRAW:sessionId:x1:y1:x2:y2:color:thickness
        if (parts.length < 8) {
            sendFrame(s, "ERROR:invalid draw command");
            return;
        }
        
        try {
            long sessionId = Long.parseLong(parts[1]);
            WhiteboardSession session = whiteboardSessions.get(sessionId);
            
            if (session == null) {
                sendFrame(s, "ERROR:session not found");
                return;
            }
            
            if (!session.hasUser(s.username())) {
                sendFrame(s, "ERROR:not in session");
                return;
            }
            
            double x1 = Double.parseDouble(parts[2]);
            double y1 = Double.parseDouble(parts[3]);
            double x2 = Double.parseDouble(parts[4]);
            double y2 = Double.parseDouble(parts[5]);
            String color = parts[6];
            int thickness = Integer.parseInt(parts[7]);
            
            // Add command to session
            WhiteboardSession.DrawCommand cmd = WhiteboardSession.DrawCommand.draw(
                    s.username(), x1, y1, x2, y2, color, thickness);
            session.addCommand(cmd);
            
            // Broadcast to peer
            String peerUsername = session.getOtherUser(s.username());
            Session peerSession = findSessionByUsername(peerUsername);
            if (peerSession != null) {
                sendFrame(peerSession, String.format(
                        "WHITEBOARD_COMMAND:%s:%.2f:%.2f:%.2f:%.2f:%s:%d",
                        s.username(), x1, y1, x2, y2, color, thickness));
            }
            
            sendFrame(s, "OK");
        } catch (NumberFormatException e) {
            sendFrame(s, "ERROR:invalid format");
        }
    }
    
    private void handleWhiteboardClear(Session s, String[] parts) {
        if (!s.isAuthed()) {
            sendFrame(s, "ERROR:login first");
            return;
        }
        // WHITEBOARD_CLEAR:sessionId
        if (parts.length < 2) {
            sendFrame(s, "ERROR:missing session id");
            return;
        }
        
        try {
            long sessionId = Long.parseLong(parts[1]);
            WhiteboardSession session = whiteboardSessions.get(sessionId);
            
            if (session == null) {
                sendFrame(s, "ERROR:session not found");
                return;
            }
            
            if (!session.hasUser(s.username())) {
                sendFrame(s, "ERROR:not in session");
                return;
            }
            
            // Clear commands and add clear marker
            session.clearCommands();
            WhiteboardSession.DrawCommand clearCmd = WhiteboardSession.DrawCommand.clear(s.username());
            session.addCommand(clearCmd);
            
            // Broadcast to peer
            String peerUsername = session.getOtherUser(s.username());
            Session peerSession = findSessionByUsername(peerUsername);
            if (peerSession != null) {
                sendFrame(peerSession, "WHITEBOARD_CLEARED:" + s.username());
            }
            
            sendFrame(s, "OK");
        } catch (NumberFormatException e) {
            sendFrame(s, "ERROR:invalid session id");
        }
    }
    
    private void handleWhiteboardClose(Session s, String[] parts) {
        if (!s.isAuthed()) {
            sendFrame(s, "ERROR:login first");
            return;
        }
        // WHITEBOARD_CLOSE:sessionId
        if (parts.length < 2) {
            sendFrame(s, "ERROR:missing session id");
            return;
        }
        
        try {
            long sessionId = Long.parseLong(parts[1]);
            WhiteboardSession session = whiteboardSessions.get(sessionId);
            
            if (session == null) {
                sendFrame(s, "ERROR:session not found");
                return;
            }
            
            if (!session.hasUser(s.username())) {
                sendFrame(s, "ERROR:not in session");
                return;
            }
            
            // Notify peer before removing
            String peerUsername = session.getOtherUser(s.username());
            Session peerSession = findSessionByUsername(peerUsername);
            if (peerSession != null) {
                sendFrame(peerSession, "WHITEBOARD_CLOSED:" + s.username());
            }
            
            // Remove session
            whiteboardSessions.remove(sessionId);
            
            sendFrame(s, "OK");
        } catch (NumberFormatException e) {
            sendFrame(s, "ERROR:invalid session id");
        }
    }
    
    private void handleWhiteboardSync(Session s, String[] parts) {
        if (!s.isAuthed()) {
            sendFrame(s, "ERROR:login first");
            return;
        }
        // WHITEBOARD_SYNC:sessionId
        if (parts.length < 2) {
            sendFrame(s, "ERROR:missing session id");
            return;
        }
        
        try {
            long sessionId = Long.parseLong(parts[1]);
            WhiteboardSession session = whiteboardSessions.get(sessionId);
            
            if (session == null) {
                sendFrame(s, "ERROR:session not found");
                return;
            }
            
            if (!session.hasUser(s.username())) {
                sendFrame(s, "ERROR:not in session");
                return;
            }
            
            // Send all commands
            List<WhiteboardSession.DrawCommand> commands = new ArrayList<>(session.getCommands());
            
            // Send count first
            sendFrame(s, "WHITEBOARD_SYNC_START:" + commands.size());
            
            // Send each command
            for (WhiteboardSession.DrawCommand cmd : commands) {
                if ("clear".equals(cmd.type())) {
                    sendFrame(s, "WHITEBOARD_CLEARED:" + cmd.username());
                } else {
                    sendFrame(s, String.format(
                            "WHITEBOARD_COMMAND:%s:%.2f:%.2f:%.2f:%.2f:%s:%d",
                            cmd.username(), cmd.x1(), cmd.y1(), cmd.x2(), cmd.y2(),
                            cmd.color(), cmd.thickness()));
                }
            }
            
            sendFrame(s, "WHITEBOARD_SYNC_END");
        } catch (NumberFormatException e) {
            sendFrame(s, "ERROR:invalid session id");
        }
    }

    public List<Map<String,Object>> usersSnapshot() {
        return online.values().stream().map(p -> {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("user", p.username());
            m.put("ip", p.ip());
            m.put("fileTcp", p.fileTcp());
            m.put("voiceUdp", p.voiceUdp());
            m.put("viaNio", p.viaNio());
            return m;
        }).toList();
    }

    public List<UserPresence> onlineUsers() {
        return online.values().stream()
                .map(p -> new UserPresence(p.username(), p.ip(), p.fileTcp(), p.voiceUdp(), p.viaNio()))
                .toList();
    }

    public List<ChatMessage> recentMessages() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public Optional<Peer> findPeer(String user) {
        Presence t = online.get(user);
        if (t == null) return Optional.empty();
        return Optional.of(new Peer(t.ip(), t.fileTcp(), t.voiceUdp(), t.viaNio()));
    }

    public LoginResult loginHttp(String user, String pass, String ip, Integer fileTcp, Integer voiceUdp) {
        if (user == null || pass == null) {
            return new LoginResult(false, "missing", List.of(), recentMessages());
        }
        if (!verifyCredentials(user, pass)) {
            return new LoginResult(false, "bad creds", List.of(), recentMessages());
        }
        return doHttpLogin(user, ip, fileTcp, voiceUdp);
    }

    public LoginResult loginHttpTrusted(String user, String ip, Integer fileTcp, Integer voiceUdp) {
        if (user == null || !credentialService.userExists(user)) {
            return new LoginResult(false, "unknown user", List.of(), recentMessages());
        }
        return doHttpLogin(user, ip, fileTcp, voiceUdp);
    }

    private LoginResult doHttpLogin(String user, String ip, Integer fileTcp, Integer voiceUdp) {
        int file = fileTcp != null ? fileTcp : -1;
        int voice = voiceUdp != null ? voiceUdp : -1;
        HttpPresence presence = new HttpPresence(user, ip, file, voice);
        Presence previous = online.put(user, presence);
        if (previous instanceof Session session) {
            disconnect(session, "relogin via http");
        }
        broadcastUserEvent(user, false, "USER_JOINED", null);
        broadcastUserList();
        log.info("HTTP LOGIN {} from {} (fileTcp={}, voiceUdp={})", user, ip, file, voice);
        return new LoginResult(true, null, onlineUsers(), recentMessages());
    }

    public boolean logoutHttp(String user) {
        if (user == null) return false;
        Presence current = online.get(user);
        if (current instanceof HttpPresence httpPresence && online.remove(user, httpPresence)) {
            broadcastUserEvent(user, false, "USER_LEFT", null);
            broadcastUserList();
            log.info("HTTP LOGOUT {}", user);
            return true;
        }
        return false;
    }

    public Optional<ChatMessage> broadcastFrom(String user, String text) {
        if (user == null || text == null) return Optional.empty();
        String sanitized = text.strip();
        if (sanitized.isEmpty()) return Optional.empty();
        Presence presence = online.get(user);
        if (presence == null) return Optional.empty();
        ChatMessage msg = new ChatMessage(user, sanitized, System.currentTimeMillis() / 1000);
        recordMessage(msg);
        broadcastChat(msg);
        return Optional.of(msg);
    }

    public boolean verifyCredentials(String user, String pass) {
        if (user == null || pass == null) return false;
        return credentialService.verifyCredentials(user, pass);
    }

    public record Peer(String ip, int fileTcp, int voiceUdp, boolean viaNio) {}
    public record UserPresence(String user, String ip, int fileTcp, int voiceUdp, boolean viaNio) {}
    public record ChatMessage(String from, String text, long timestampSeconds) {}
    public record LoginResult(boolean success, String reason, List<UserPresence> users, List<ChatMessage> messages) {}

    private void recordMessage(ChatMessage msg) {
        synchronized (history) {
            history.addLast(msg);
            while (history.size() > HISTORY_LIMIT) {
                history.pollFirst();
            }
        }
    }

    private void broadcastChat(ChatMessage msg) {
        broadcast(String.join(":",
                "CHAT_MSG",
                msg.from(),
                String.valueOf(msg.timestampSeconds()),
                msg.text().replace('\n', ' ')
        ));
    }

    private void sendFrame(Session s, String frame) {
        s.enqueue(StandardCharsets.UTF_8.encode(frame + "\n"));
    }

    private void broadcast(String frame) {
        ByteBuffer buf = StandardCharsets.UTF_8.encode(frame + "\n");
        for (Session session : sessions.values()) {
            session.enqueue(buf.duplicate());
        }
    }

    private void broadcastExcept(Session except, String frame) {
        ByteBuffer buf = StandardCharsets.UTF_8.encode(frame + "\n");
        for (Session session : sessions.values()) {
            if (session != except) {
                session.enqueue(buf.duplicate());
            }
        }
    }

    private void broadcastUserEvent(String user, boolean viaNio, String type, Session exclude) {
        broadcastExcept(exclude, String.join(":",
                type,
                user,
                viaNio ? "nio" : "http"
        ));
    }

    private void disconnect(Session s, String reason) {
        try {
            if (s.username != null && online.remove(s.username, s)) {
                broadcastUserEvent(s.username, true, "USER_LEFT", s);
                broadcastUserList(s);
            }
            sessions.remove(s.ch);
            s.key.cancel();
            s.ch.close();
            log.info("Disconnected {} ({})", s.username != null ? s.username : s.ch, reason);
        } catch (Exception ignored) {
        }
    }

    private void closeKey(SelectionKey key) {
        try {
            Session s = (Session) key.attachment();
            if (s != null) disconnect(s, "error");
            else {
                key.channel().close();
                key.cancel();
            }
        } catch (IOException ignored) {
        }
    }
    private static Integer intOrNull(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private interface Presence {
        String username();
        String ip();
        int fileTcp();
        int voiceUdp();
        boolean viaNio();
    }

    private static class Session implements Presence {
        final SocketChannel ch; final SelectionKey key;
        final ByteBuffer readBuf = ByteBuffer.allocateDirect(READ_BUF);
        final StringBuilder lineBuffer = new StringBuilder(2048);
        final Deque<ByteBuffer> writeQueue = new ArrayDeque<>();
        volatile String username;
        volatile String ip;
        volatile int fileTcp = -1;
        volatile int voiceUdp = -1;

        Session(SocketChannel ch, SelectionKey key) {
            this.ch = ch;
            this.key = key;
        }

        boolean isAuthed() {
            return username != null;
        }

        void enqueue(ByteBuffer data) {
            synchronized (writeQueue) {
                writeQueue.add(data);
            }
            Selector selector = key.selector();
            if (selector != null) {
                selector.wakeup();
            }
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }

        @Override public String username() { return username; }
        @Override public String ip() { return ip; }
        @Override public int fileTcp() { return fileTcp; }
        @Override public int voiceUdp() { return voiceUdp; }
        @Override public boolean viaNio() { return true; }
    }

    private static class HttpPresence implements Presence {
        private final String username;
        private final String ip;
        private final int fileTcp;
        private final int voiceUdp;

        HttpPresence(String username, String ip, int fileTcp, int voiceUdp) {
            this.username = username;
            this.ip = ip;
            this.fileTcp = fileTcp;
            this.voiceUdp = voiceUdp;
        }

        @Override public String username() { return username; }
        @Override public String ip() { return ip; }
        @Override public int fileTcp() { return fileTcp; }
        @Override public int voiceUdp() { return voiceUdp; }
        @Override public boolean viaNio() { return false; }
    }
}
