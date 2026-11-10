package bio.anode.ideagenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeadsProjectTest {

    @Test
    void urlsCompareLoosely() {
        assertEquals(Leads.normalizeUrl("https://x.com/a/status/1/"), Leads.normalizeUrl("https://twitter.com/a/status/1?s=20"));
        assertEquals("x.com/a/status/1", Leads.normalizeUrl("https://www.x.com/a/status/1"));
        assertNull(Leads.normalizeUrl("not a url"));
    }

    @Test
    void papersGoalMatchesFirstOnlyFromTheLastYearNoDuplicates() {
        String recent = Instant.now().minus(30, ChronoUnit.DAYS).toString();
        String old = Instant.now().minus(800, ChronoUnit.DAYS).toString();
        List<String> urls = new ArrayList<>();
        Function<String, String> fake = url -> {
            urls.add(url);
            return url.contains("/search")
                    ? """
                      [{"paper":{"id":"s1","title":"Spatial audio","ai_summary":"AI sum","publishedAt":"%1$s","upvotes":3}},
                       {"paper":{"id":"old","title":"Old","summary":"x","publishedAt":"%2$s"}},
                       {"paper":{"id":"d1","title":"Dup","summary":"x","publishedAt":"%1$s"}}]""".formatted(recent, old)
                    : """
                      [{"paper":{"id":"d1","title":"Daily","summary":"sum","publishedAt":"%s","upvotes":7}}]""".formatted(recent);
        };
        List<Leads.Paper> papers = Leads.fetchPapers("ljud i offentliga rum", fake);
        assertEquals(List.of("s1", "d1"), papers.stream().map(Leads.Paper::id).toList());
        assertEquals("AI sum", papers.getFirst().summary(), "prefers the short AI summary");
        assertTrue(urls.stream().anyMatch(u -> u.contains("q=ljud%20i%20offentliga%20rum")));
        assertEquals(List.of("d1"), Leads.fetchPapers("", fake).stream().map(Leads.Paper::id).toList(), "no goal: daily papers only");
    }

    @Test
    void slugsAreAsciiAndTildeExpandsToHome() {
        assertEquals("sjungande-bank-pa-oland", Project.slugify("Sjungande Bänk på Öland!"));
        assertEquals("idea", Project.slugify("!!!"));
        assertEquals(Path.of(System.getProperty("user.home"), "x").toString(), Project.expandHome("~/x"));
        assertEquals("/abs", Project.expandHome("/abs"));
    }

    static final Project.Brief BRIEF = new Project.Brief("Bänk", "bank", "En bänk.", "P", "A", List.of("m"), List.of("o"), "s",
            List.of("f1", "f2"), List.of("q"), List.of("r"));

    static Idea idea(long id, String title, String pitch, String kind, String originBoardTitle) {
        return new Idea(id, 1, title, pitch, kind, null, 0, 0, 5, 4, 4, "Bra.", null, null, 0, null, null, null, originBoardTitle, List.of(), "");
    }

    static final Idea IDEA = idea(1, "Bänk", "Sjunger.", "combine", null);

    @Test
    void claudeMdHasVerdictGoalNumberedStepsAndLineage() {
        String md = Project.renderClaudeMd(BRIEF, IDEA, "Ljud", List.of(idea(2, "Gäst", "G", "guest", "Ljusbordet")), "sv", "2026-11-02");
        assertTrue(md.contains("Originalitet 5 · Genomförbarhet 4 · Effekt 4 = 13/15 🥇"), md);
        assertTrue(md.contains("## Tavlans mål\n\nLjud"));
        assertTrue(md.contains("1. f1\n2. f2"));
        assertTrue(md.contains("- Gäst (från tavlan Ljusbordet): **Gäst** G"));
    }

    @Test
    void projectFoldersNeverOverwrite(@TempDir Path root) throws IOException {
        String a = Project.writeProject(root.toString(), BRIEF, IDEA, "", List.of(), "en", false);
        String b = Project.writeProject(root.toString(), BRIEF, IDEA, "", List.of(), "en", false);
        assertEquals(List.of("bank", "bank-2"), Stream.of(a, b).map(p -> p.substring(root.toString().length() + 1)).toList());
        try (Stream<Path> files = Files.list(Path.of(a))) {
            assertEquals(List.of(".gitignore", "CLAUDE.md", "README.md", "idea.json", "vault"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertTrue(Files.readString(Path.of(a, "README.md")).contains("cd \"" + a + "\"\nclaude"));
        IOException e = assertThrows(IOException.class, () -> Project.writeProject("relative", BRIEF, IDEA, "", List.of(), "en", false));
        assertTrue(e.getMessage().contains("absolute"));
    }
}
