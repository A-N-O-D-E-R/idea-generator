package bio.anode.ideagenerator.service;

import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.model.Idea;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Per-board event bus: every change on a board is published here and the browser follows it over SSE. Events are maps with a "type" first. */
@Component
public class EventBus {
    private final Map<Long, Set<Consumer<Map<String, Object>>>> subs = new ConcurrentHashMap<>();

    /** Subscribe to one board; run the returned handle to unsubscribe. */
    public Runnable on(long boardId, Consumer<Map<String, Object>> fn) {
        subs.computeIfAbsent(boardId, k -> new CopyOnWriteArraySet<>()).add(fn);
        return () -> {
            Set<Consumer<Map<String, Object>>> s = subs.get(boardId);
            if (s != null) s.remove(fn);
        };
    }

    public void emit(long boardId, Map<String, Object> e) {
        for (Consumer<Map<String, Object>> fn : subs.getOrDefault(boardId, Set.of())) {
            try {
                fn.accept(e);
            } catch (RuntimeException dead) {
                // a dead subscriber must not break the others
            }
        }
    }

    public void emit(long boardId, String type, Object... kv) {
        emit(boardId, event(type, kv));
    }

    public void ideas(long boardId, List<Idea> ideas) {
        if (!ideas.isEmpty()) emit(boardId, "ideas", "ideas", ideas);
    }

    public static Map<String, Object> event(String type, Object... kv) {
        Object[] all = new Object[kv.length + 2];
        all[0] = "type";
        all[1] = type;
        System.arraycopy(kv, 0, all, 2, kv.length);
        return Js.obj(all);
    }
}
