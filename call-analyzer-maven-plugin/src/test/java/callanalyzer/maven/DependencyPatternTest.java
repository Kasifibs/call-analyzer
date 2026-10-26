package callanalyzer.maven;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyPatternTest {

    private static boolean matches(String pattern, String coordinates) {
        return AnalyzeMojo.dependencyPattern(pattern).matcher(coordinates).matches();
    }

    @Test
    void exactCoordinates() {
        assertTrue(matches("com.sirket:ortak-domain", "com.sirket:ortak-domain"));
        assertFalse(matches("com.sirket:ortak-domain", "com.sirket:ortak-domain-test"));
        assertFalse(matches("com.sirket:ortak-domain", "com.sirketx:ortak-domain"), "dots are no wildcards");
    }

    @Test
    void wildcards() {
        assertTrue(matches("com.sirket:*", "com.sirket:anything"));
        assertFalse(matches("com.sirket:*", "com.sirket.alt:anything"));
        assertTrue(matches("com.sirket*:*", "com.sirket.alt:anything"));
        assertTrue(matches("*:ortak-*", "org.x:ortak-domain"));
        assertFalse(matches("*:ortak-*", "org.x:domain"));
    }

    @Test
    void groupIdAlone() {
        assertTrue(matches("com.sirket", "com.sirket:ortak-domain"));
        assertFalse(matches("com.sirket", "com.sirket.alt:ortak-domain"));
        assertTrue(matches(" com.sirket ", "com.sirket:x"));
    }
}
