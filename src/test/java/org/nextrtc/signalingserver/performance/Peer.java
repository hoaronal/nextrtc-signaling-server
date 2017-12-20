package org.nextrtc.signalingserver.performance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Getter;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.nextrtc.signalingserver.domain.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import static java.util.Collections.synchronizedList;
import static org.nextrtc.signalingserver.domain.Message.create;

@WebSocket
@Getter
public class Peer {
    private final static ExecutorService service = Executors.newFixedThreadPool(4);
    private final Gson gson = new GsonBuilder().create();
    private String name;
    private Session session;
    private String joinedTo;
    private Map<String, Action> actions = new ConcurrentHashMap<>();
    private Map<String, List<String>> candidates = new ConcurrentHashMap<>();
    private List<String> joined = synchronizedList(new ArrayList<>());
    private List<String> errors = synchronizedList(new ArrayList<>());
    private List<Message> log = synchronizedList(new ArrayList<>());

    public Peer(int i) {
        actions.put("created", (s, msg) -> {
            name = msg.getTo();
            joinedTo = msg.getContent();
        });
        actions.put("joined", (s, msg) -> {
            name = msg.getTo();
            joinedTo = msg.getContent();
        });
        actions.put("offerrequest", (s, msg) -> {
            send(create()
                    .to(msg.getFrom())
                    .signal("offerResponse")
                    .content("offer from" + name)
                    .build());
        });
        actions.put("answerrequest", (s, msg) -> {
            send(create()
                    .to(msg.getFrom())
                    .signal("answerResponse")
                    .content("answer from" + name)
                    .build());
        });
        actions.put("finalize", (s, msg) -> {
            send(create()
                    .to(msg.getFrom())
                    .signal("candidate")
                    .content("local candidate from " + name)
                    .build());
            send(create()
                    .to(msg.getFrom())
                    .signal("candidate")
                    .content("remote candidate from " + name)
                    .build());
        });
        actions.put("candidate", (s, msg) -> {
            candidates.computeIfAbsent(msg.getFrom(), k -> new ArrayList<>());
            candidates.get(msg.getFrom()).add(msg.getContent());
            if (!msg.getContent().contains("answer")) {
                send(create()
                        .to(msg.getFrom())
                        .signal("candidate")
                        .content("answer from " + name + " on " + msg.getContent())
                        .build());
            }
        });
        actions.put("ping", (s, m) -> {
        });
        actions.put("newjoined", (s, msg) -> {
            joined.add(msg.getContent());
        });
        actions.put("error", (s, msg) -> {
            errors.add(msg.getContent());
        });
        actions.put("end", (s, msg) -> {
            throw new RuntimeException(msg.getContent());
        });
    }

    public void join(String name) {
        send(create()
                .signal("join")
                .content(name)
                .build());
    }

    public void createConv(String name) {
        send(create()
                .signal("create")
                .content(name)
                .build());
    }

    public void leave() {
        send(create()
                .signal("left")
                .build());
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        this.session = session;
    }

    @OnWebSocketMessage
    public void onMessage(String msg) {
        Message message = gson.fromJson(msg, Message.class);
        if (!"ping".equalsIgnoreCase(message.getSignal())) {
            log.add(message);
        }
        service.submit(() -> {
            String key = message.getSignal().toLowerCase();
            if (actions.containsKey(key)) {
                actions.get(key).execute(session, message);
            }
        });
    }

    @OnWebSocketClose
    public void onClose(int status, String reason) {
        session = null;
        log.add(create().content(reason).build());
    }

    private void send(Message message) {
        log.add(message);
        service.submit(() -> {
            waitUtil(m -> m.getSession() != null);
            try {
                session.getRemote().sendString(gson.toJson(message));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    void waitUtil(Predicate<Peer> predicate) {
        int waitingTime = 0;
        while (!predicate.test(this)) {
            try {
                Thread.sleep(100);
                waitingTime += 100;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (waitingTime >= 1 * 1000)
                break;
        }
    }

    private interface Action {
        void execute(Session session, Message message);
    }
}
