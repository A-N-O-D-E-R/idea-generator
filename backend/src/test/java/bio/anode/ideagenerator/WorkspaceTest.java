package bio.anode.ideagenerator;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {
    static final String INDEX = """
            # Idea Index

            | Date | Slug | Title | Tags | Status | Summary |
            |------|------|-------|------|--------|---------|
            | 2026-11-07 | [old](ideas/old/PITCH.md) | Old | x | raw | old one |
            """;

    static Idea gold() {
        return new Idea(1, 1, "Singing bench", "A park bench that hums.", "expand", null, 0, 0, 5, 4, 5, "Novel.", null, null, 1,
                null, null, null, null, List.of("sound", "public space"), null, null, 0);
    }

    static Project.Brief brief(String slug) {
        return new Project.Brief("Singing Bench", slug, "A bench | that sings\nfor parks.", "Parks are silent.", "City planners",
                List.of("one bench"), List.of(), "ESP32", List.of("build it"), List.of("Which song?"), List.of("Vandalism"));
    }

    @Test
    void filesAGoldIdeaOnceAndNeverOverwrites(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("INDEX.md"), INDEX);
        Workspace.Filed f = Workspace.file(ws.toString(), brief("singing-bench"), gold(), "Sound in public", List.of(), "/p/singing-bench", "2026-11-10");
        assertFalse(f.existed());
        String pitch = Files.readString(ws.resolve("ideas/singing-bench/PITCH.md"));
        assertTrue(pitch.startsWith("# Singing Bench"));
        assertTrue(pitch.contains("14/15 🥇"));
        assertTrue(pitch.contains("Status: raw"));
        assertTrue(Files.exists(ws.resolve("ideas/singing-bench/LINK.md")));

        List<String> lines = Files.readAllLines(ws.resolve("INDEX.md"));
        int rule = lines.indexOf(Workspace.TABLE_RULE);
        assertEquals("| 2026-11-10 | [singing-bench](ideas/singing-bench/PITCH.md) | Singing Bench | sound, public space | raw | A bench \\| that sings for parks. |",
                lines.get(rule + 1), "newest first, pipes and line breaks escaped");
        assertTrue(lines.get(rule + 2).contains("[old]"));

        Files.writeString(ws.resolve("ideas/singing-bench/PITCH.md"), "hand edited");
        assertTrue(Workspace.file(ws.toString(), brief("singing-bench"), gold(), "", List.of(), "/p", "2026-11-11").existed());
        assertEquals("hand edited", Files.readString(ws.resolve("ideas/singing-bench/PITCH.md")));
        assertTrue(Workspace.file(ws.toString(), brief("old"), gold(), "", List.of(), "/p", "2026-11-11").existed(), "slug already in INDEX.md");
        assertEquals(lines.size(), Files.readAllLines(ws.resolve("INDEX.md")).size(), "no second row");
    }

    @Test
    void refusesAFolderThatIsNotAWorkspace(@TempDir Path ws) {
        assertThrows(java.io.IOException.class, () -> Workspace.file(ws.toString(), brief("x"), gold(), "", List.of(), "/p", "2026-11-10"));
    }

    static Providers.Chat answering(String json) {
        return r -> new Providers.ChatResult(Js.parse(json), 0.002);
    }

    @Test
    void readsTheIndexRows() {
        List<Workspace.Row> rows = Workspace.rows(INDEX);
        assertEquals(List.of(new Workspace.Row("old", "Old", "x", "old one")), rows);
    }

    @Test
    void aDuplicateIsNotFiledAndPointsAtTheExistingIdea(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("INDEX.md"), INDEX);
        var m = Workspace.match(answering("{\"duplicateOf\":\"old\",\"related\":[{\"slug\":\"old\",\"why\":\"same\"}]}"),
                "m", brief("singing-bench"), gold(), Workspace.rows(INDEX));
        assertEquals("old", m.value().duplicateOf());
        assertTrue(m.value().related().isEmpty(), "a duplicate is not also related");
        assertEquals(0.002, m.costUsd());
        Workspace.Filed f = Workspace.file(ws.toString(), brief("singing-bench"), gold(), "", List.of(), "/p", "2026-11-10", m.value());
        assertTrue(f.existed());
        assertEquals(ws.resolve("ideas/old").toString(), f.path());
        assertFalse(Files.exists(ws.resolve("ideas/singing-bench")));
        assertEquals(INDEX, Files.readString(ws.resolve("INDEX.md")));
    }

    @Test
    void relatedIdeasAreLinkedBothWaysAndInventedSlugsDropped(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("INDEX.md"), INDEX);
        Files.createDirectories(ws.resolve("ideas/old"));
        Files.writeString(ws.resolve("ideas/old/LINK.md"), "# Links — Old\n\nNone yet.\n");
        var m = Workspace.match(answering("{\"duplicateOf\":\"made-up\",\"related\":[{\"slug\":\"old\",\"why\":\"same market\"},{\"slug\":\"made-up\",\"why\":\"x\"}]}"),
                "m", brief("singing-bench"), gold(), Workspace.rows(INDEX)).value();
        assertNull(m.duplicateOf(), "a slug not in the index is ignored");
        Workspace.Filed f = Workspace.file(ws.toString(), brief("singing-bench"), gold(), "", List.of(), "/p", "2026-11-10", m);
        assertEquals(List.of("old"), f.related());
        assertEquals("# Links — Singing Bench\n\n- [Old](../old/PITCH.md) — same market\n", Files.readString(ws.resolve("ideas/singing-bench/LINK.md")));
        assertEquals("# Links — Old\n\n- [Singing Bench](../singing-bench/PITCH.md) — same market\n", Files.readString(ws.resolve("ideas/old/LINK.md")));
    }

    @Test
    void anEmptyIndexCostsNoCall() {
        var m = Workspace.match(r -> { throw new AssertionError("no call"); }, "m", brief("x"), gold(), List.of());
        assertSame(Workspace.Match.NONE, m.value());
    }
}
