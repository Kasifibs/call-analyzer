package callanalyzer;

import callanalyzer.cli.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    private record Run(int exit, String out, String err) {
    }

    private static Run run(String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Method m = Main.class.getDeclaredMethod("run", String[].class, PrintStream.class, PrintStream.class);
        m.setAccessible(true);
        int exit = (int) m.invoke(null, args,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void textReportWithTree() throws Exception {
        Run r = run("--cp", Fixtures.sampleClassesDir().toString(), "--entry", "sample.cars.App#main",
                "--target", "sample.cars.ICar", "--tree");
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("[1] "));
        assertTrue(r.out().contains("Mechanic#check(ICar, int)"));
        assertTrue(r.out().contains("=== Uygulama sınıfı bazında özet ==="));
        assertTrue(r.out().contains("★ ICar#start()  (Garage.java:8)  {Bmw, Tesla, TowTruck}"));
    }

    @Test
    void jsonReportToFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("report.json");
        Run r = run("--cp", Fixtures.sampleClassesDir().toString(), "-e", "sample.cars.Garage#service",
                "-t", "sample.cars.ICar", "--format", "json", "-o", file.toString());
        assertEquals(0, r.exit(), r.err());
        String json = Files.readString(file);
        assertTrue(json.contains("\"calledMethod\": \"sample.cars.ICar#start()\""));
        assertTrue(json.contains("\"class\": \"sample.cars.Tesla\""));
    }

    @Test
    void acquirerComparisonAndFailOnMissing() throws Exception {
        String[] base = {"--cp", Fixtures.sampleClassesDir().toString(), "--target", "sample.cars.ICar",
                "--acquirer", "sample.cars.CarAcquirer#acquire", "--fail-on-missing", "--entry"};
        Run missing = run(concat(base, "sample.cars.RentalService#rent"));
        assertEquals(3, missing.exit(), missing.err());
        assertTrue(missing.out().contains("✗ EKSİK"));
        assertTrue(missing.out().contains("sample.cars.TowTruck"));

        Run ok = run(concat(base, "sample.cars.RentalService#rentOnlyBmw"));
        assertEquals(0, ok.exit(), ok.err());
        assertTrue(ok.out().contains("? OLASI EKSİK"));

        Run json = run(concat(base, "sample.cars.RentalService#rent", "--format", "json"));
        assertTrue(json.out().contains("\"missing\": [\n"));
        assertTrue(json.out().contains("\"origin\": \"alan RentalService.bmw : Bmw\""));
    }

    private static String[] concat(String[] a, String... b) {
        String[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @Test
    void usageErrors() throws Exception {
        Run missing = run("--cp", "x");
        assertEquals(2, missing.exit());
        assertTrue(missing.err().contains("zorunludur"));
        assertEquals(0, run("--help").exit());
    }
}
