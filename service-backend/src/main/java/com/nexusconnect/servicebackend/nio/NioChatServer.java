package com.nexusconnect.servicebackend.nio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;


public class NioChatServer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(NioChatServer.class);
    private static final int READ_BUF = 64 * 1024;

    private final int port;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService workers =
            Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()-1));

  
    private final Map<String,String> auth = Map.of(
            "lakshan","123", "kevin","123", "alice","a1", "bob","b1"
    );

 
    private final ConcurrentHashMap<String, Session> online = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SocketChannel, Session> sessions = new ConcurrentHashMap<>();

   
    private volatile boolean running = false;
    private Selector selector;
    private ServerSocketChannel server;
    private Thread selectorThread;

    public NioChatServer(int port) { this.port = port; }

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
        try { selector.wakeup(); } catch (Exception ignored) {}
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (selector != null) selector.close(); } catch (Exception ignored) {}
        try { if (selectorThread != null) selectorThread.join(1000); } catch (InterruptedException ignored) {}
        workers.shutdownNow();
        log.info("NIO server stopped");
    }

    @Override public void run() {
        try {
            while (running) {
                selector.select();
                var it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    var key = it.next(); it.remove();
                    try {
                        if (!key.isValid()) continue;
                        if (key.isAcceptable()) onAccept();
                        if (key.isReadable())   onRead(key);
                        if (key.isWritable())   onWrite(key);
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
        if (n == -1) { disconnect(s, "EOF"); return; }
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
                workers.submit(() -> handleLine(s, line, srcIp));
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
        try {
            Map<?,?> obj = json.readValue(line, Map.class);
            String type = str(obj.get("type"));
            switch (type) {
                case "LOGIN"     -> handleLogin(s, obj, srcIp);
                case "MSG"       -> handleGlobalMsg(s, obj);
                case "ASK_PEER"  -> handleAskPeer(s, obj);
                case "ASK_USERS" -> sendUsersList(s);
                default -> sendJson(s, Map.of("type","ERROR","msg","unknown type: "+type));
            }
        } catch (Exception e) {
            sendJson(s, Map.of("type","ERROR","msg","bad json"));
        }
    }

    private void handleLogin(Session s, Map<?,?> obj, String srcIp) {
        String user = str(obj.get("user"));
        String pass = str(obj.get("pass"));
        Integer fileTcp = intOrNull(obj.get("fileTcp"));
        Integer voiceUdp= intOrNull(obj.get("voiceUdp"));

        if (user == null || pass == null) { sendJson(s, Map.of("type","LOGIN_FAIL","reason","missing")); return; }
        if (!pass.equals(auth.get(user))) { sendJson(s, Map.of("type","LOGIN_FAIL","reason","bad creds")); return; }

        Session prev = online.put(user, s);
        if (prev != null && prev != s) disconnect(prev, "relogin");

        s.username = user; s.ip = srcIp;
        s.fileTcp = fileTcp != null ? fileTcp : -1;
        s.voiceUdp= voiceUdp!= null ? voiceUdp: -1;

        sendJson(s, Map.of("type","LOGIN_OK", "you", user, "users", usersSnapshot()));
        broadcastExcept(s, Map.of("type","USER_JOINED","user",user));
        log.info("LOGIN {} from {} (fileTcp={}, voiceUdp={})", user, srcIp, s.fileTcp, s.voiceUdp);
    }

    private void handleGlobalMsg(Session s, Map<?,?> obj) {
        if (!s.isAuthed()) { sendJson(s, Map.of("type","ERROR","msg","login first")); return; }
        String text = str(obj.get("text"));
        if (text == null || text.isBlank()) return;
        Map<String,Object> frame = new LinkedHashMap<>();
        frame.put("type","MSG"); frame.put("from", s.username);
        frame.put("text", text); frame.put("ts", System.currentTimeMillis()/1000);
        broadcast(frame);
    }

    private void handleAskPeer(Session s, Map<?,?> obj) {
        if (!s.isAuthed()) { sendJson(s, Map.of("type","ERROR","msg","login first")); return; }
        String target = str(obj.get("user"));
        Session t = online.get(target);
        if (t == null) { sendJson(s, Map.of("type","PEER","user",target,"error","offline")); return; }
        sendJson(s, Map.of("type","PEER","user",target,"ip",t.ip,"fileTcp",t.fileTcp,"voiceUdp",t.voiceUdp));
    }

    private void sendUsersList(Session s) { sendJson(s, Map.of("type","USERS","list", usersSnapshot())); }

    public List<Map<String,Object>> usersSnapshot() {
        return online.values().stream().map(sess -> {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("user", sess.username); m.put("ip", sess.ip);
            m.put("fileTcp", sess.fileTcp); m.put("voiceUdp", sess.voiceUdp);
            return m;
        }).collect(Collectors.toList());
    }
    public Optional<Peer> findPeer(String user) {
        Session t = online.get(user);
        if (t == null) return Optional.empty();
        return Optional.of(new Peer(t.ip, t.fileTcp, t.voiceUdp));
    }
    public record Peer(String ip, int fileTcp, int voiceUdp) {}

    private void sendJson(Session s, Map<String,?> map) {
        try {
            byte[] bytes = (json.writeValueAsString(map) + "\n").getBytes(StandardCharsets.UTF_8);
            s.enqueue(ByteBuffer.wrap(bytes));
        } catch (Exception e) { log.warn("sendJson failed", e); }
    }
    private void broadcast(Map<String,?> map) {
        byte[] bytes;
        try { bytes = (json.writeValueAsString(map) + "\n").getBytes(StandardCharsets.UTF_8); }
        catch (Exception e) { return; }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        for (Session s: sessions.values()) s.enqueue(buf.duplicate());
    }
    private void broadcastExcept(Session except, Map<String,?> map) {
        byte[] bytes;
        try { bytes = (json.writeValueAsString(map) + "\n").getBytes(StandardCharsets.UTF_8); }
        catch (Exception e) { return; }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        for (Session s: sessions.values()) if (s != except) s.enqueue(buf.duplicate());
    }

    private void disconnect(Session s, String reason) {
        try {
            if (s.username != null && online.remove(s.username, s)) {
                broadcastExcept(s, Map.of("type","USER_LEFT","user",s.username));
            }
            sessions.remove(s.ch);
            s.key.cancel();
            s.ch.close();
            log.info("Disconnected {} ({})", s.username != null ? s.username : s.ch, reason);
        } catch (Exception ignored) {}
    }
    private void closeKey(SelectionKey key) {
        try {
            Session s = (Session) key.attachment();
            if (s != null) disconnect(s, "error");
            else { key.channel().close(); key.cancel(); }
        } catch (IOException ignored) {}
    }
    private static String str(Object o){ return o==null?null:String.valueOf(o); }
    private static Integer intOrNull(Object o){ try{ return o==null?null:Integer.parseInt(String.valueOf(o)); }catch(Exception e){return null;} }

    private static class Session {
        final SocketChannel ch; final SelectionKey key;
        final ByteBuffer readBuf = ByteBuffer.allocateDirect(READ_BUF);
        final StringBuilder lineBuffer = new StringBuilder(2048);
        final Deque<ByteBuffer> writeQueue = new ArrayDeque<>();
        volatile String username; volatile String ip; volatile int fileTcp=-1; volatile int voiceUdp=-1;

        Session(SocketChannel ch, SelectionKey key){ this.ch = ch; this.key = key; }
        boolean isAuthed(){ return username != null; }
        void enqueue(ByteBuffer data) {
            synchronized (writeQueue) { writeQueue.add(data); }
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            key.selector().wakeup();
        }
    }
}
