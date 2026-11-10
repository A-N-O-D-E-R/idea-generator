package bio.anode.ideagenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractTest {

    @Test
    void recognisesALoneLinkWithOrWithoutScheme() {
        assertEquals("https://example.com/a?b=1", Extract.asUrl("https://example.com/a?b=1"));
        assertEquals("https://www.example.se/artikel", Extract.asUrl("  www.example.se/artikel "));
        assertNull(Extract.asUrl("elcyklar i glesbygd"));
        assertNull(Extract.asUrl("läs https://example.com"), "text with a link in it is text");
        assertNull(Extract.asUrl("javascript:alert(1)"));
        assertNull(Extract.asUrl("ftp://example.com"));
    }

    @Test
    void decodesNamedAndNumericEntities() {
        assertEquals("Räksmörgås & åä …", Extract.decodeEntities("R&auml;ksm&ouml;rg&aring;s &amp; &#229;&#xE4; &hellip;"));
        assertEquals("&bogus; &#0;", Extract.decodeEntities("&bogus; &#0;"));
    }

    @Test
    void extractsTheArticleAndDropsChrome() {
        String html = """
                <!doctype html><html><head>
                    <title>Fallback title</title>
                    <meta property="og:title" content="Cykelbibliotek i Växjö">
                    <style>.x{color:red}</style><script>var secret = 1;</script></head>
                    <body><nav>Meny Hem Om</nav>
                    <article><h1>Cykelbibliotek</h1><p>Kommunen l&aring;nar ut <b>lastcyklar</b>.</p>
                    <script>tracking()</script><p>Andra stycket.<br>Ny rad.</p></article>
                    <footer>© 2026</footer></body></html>""";
        Extract.Page p = Extract.htmlToText(html);
        assertEquals("Cykelbibliotek i Växjö", p.title());
        assertTrue(p.text().startsWith("Cykelbibliotek\n\nKommunen lånar ut lastcyklar ."), p.text());
        assertTrue(p.text().contains("Andra stycket.\nNy rad."), p.text());
        for (String junk : List.of("secret", "tracking", "Meny", "©", "color:red")) assertTrue(!p.text().contains(junk), junk);
    }

    @Test
    void fallsBackToTitleAndBody() {
        Extract.Page p = Extract.htmlToText("<title>T &amp; T</title><body><div>Hej</div><div>då</div></body>");
        assertEquals("T & T", p.title());
        assertEquals("Hej\n\ndå", p.text());
    }

    @Test
    void capsVeryLongPages() {
        String text = Extract.htmlToText("<body><p>" + "ord ".repeat(5000) + "</p></body>").text();
        assertTrue(text.length() <= 6000 + 4);
        assertTrue(text.endsWith("[…]"));
    }
}
