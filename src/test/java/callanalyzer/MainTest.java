package callanalyzer;

import callanalyzer.cli.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertTrue(r.out().contains("=== Uygulama sinifi bazinda ozet ==="));
        assertTrue(r.out().contains("* ICar#start()  (Garage.java:8)  {Bmw, Tesla, TowTruck}"));
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
        assertTrue(missing.out().contains("[X] EKSIK"));
        assertTrue(missing.out().contains("sample.cars.TowTruck"));

        Run ok = run(concat(base, "sample.cars.RentalService#rentOnlyBmw"));
        assertEquals(0, ok.exit(), ok.err());
        assertTrue(ok.out().contains("? OLASI EKSIK"));

        Run json = run(concat(base, "sample.cars.RentalService#rent", "--format", "json"));
        assertTrue(json.out().contains("\"missing\": [\n"));
        assertTrue(json.out().contains("\"origin\": \"alan RentalService.bmw : Bmw\""));
    }

    @Test
    void annotationComparison() throws Exception {
        Run r = run("--cp", Fixtures.sampleClassesDir().toString(), "--target", "sample.cars.ICar",
                "--entry", "sample.cars.RentalService#rentAnnotated", "--acquire-annotation", "sample.cars.AcquireCar",
                "--fail-on-missing");
        assertEquals(3, r.exit(), r.err());
        assertTrue(r.out().contains("=== Acquire karsilastirmasi: @sample.cars.AcquireCar ==="));
        assertTrue(r.out().contains("Anotasyon @AcquireCar: VAR  RentalService#rentAnnotated()"));
        assertTrue(r.out().contains("@AcquireCar ile verildi: Bmw.class"));
        assertTrue(r.out().contains("[X] EKSIK"));
    }

    @Test
    void optionsFromConfigFile(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("analyzer.properties");
        Files.writeString(config, String.join("\n",
                "# event ayarlari",
                "cp=" + Fixtures.sampleClassesDir(),
                "target=sample.cars.ICar",
                "event-class=sample.events.Event",
                "event-dispatcher=sample.events.EventDispatcher",
                "--event-observer = sample.events.IObserver",
                "tree=true",
                "direct-only=false"));
        Run r = run("--config", config.toString(), "--entry", "sample.events.EventFlowService#rent");
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("=== Event akisi ==="));
        assertTrue(r.out().contains("[event CAR_RENTED] CarRentedObserver#handle(CarRentedEvent)"));
        assertTrue(r.out().contains("=== Cagri agaci"));

        Files.writeString(config, "cp=C:\\apps\\lib\\a.jar,C:\\apps\\b.jar\nentry=a.B#c\n");
        assertEquals(List.of("--cp", "C:\\apps\\lib\\a.jar", "--cp", "C:\\apps\\b.jar", "--entry", "a.B#c", "--tree"),
                List.of(Main.expandConfig(new String[]{"--config", config.toString(), "--tree"})));
    }

    @Test
    void classPathWildcardIsExpandedWithoutCreatingAPathWithStar(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("b.jar"), "");
        Files.writeString(dir.resolve("a.jar"), "");
        Files.writeString(dir.resolve("notes.txt"), "");
        Method m = Main.class.getDeclaredMethod("classPathEntry", String.class);
        m.setAccessible(true);
        assertEquals(List.of(dir.resolve("a.jar"), dir.resolve("b.jar")), m.invoke(null, dir + File.separator + "*"));
        assertEquals(List.of(dir.resolve("a.jar"), dir.resolve("b.jar")), m.invoke(null, dir + "/*"));
    }

    @Test
    void eventOptionsMustBeGivenTogether() throws Exception {
        Run r = run("--cp", Fixtures.sampleClassesDir().toString(), "--entry", "sample.events.EventFlowService#rent",
                "--target", "sample.cars.ICar", "--event-class", "sample.events.Event");
        assertEquals(2, r.exit());
        assertTrue(r.err().contains("--event-dispatcher"));
    }

    private static String[] concat(String[] a, String... b) {
        String[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** Windows consoles (cmd) do not show non-ASCII text reliably, so every message must be plain ASCII. */
    @Test
    void outputIsPlainAscii() throws Exception {
        String cp = Fixtures.sampleClassesDir().toString();
        List<Run> runs = List.of(
                run("--cp", cp, "--entry", "sample.cars.App#main", "--target", "sample.cars.ICar", "--tree",
                        "--include-constructors"),
                run("--cp", cp, "--entry", "sample.cars.App#main", "--target", "sample.cars.ICar", "--format", "json"),
                run("--cp", cp, "--entry", "sample.cars.RentalService#rentWithSpare", "--target", "sample.cars.ICar",
                        "--acquirer", "sample.cars.CarAcquirer#acquire", "--tree"),
                run("--cp", cp, "--entry", "sample.cars.RentalService#rent", "--target", "sample.cars.ICar",
                        "--acquirer", "sample.cars.CarAcquirer#acquire"),
                run("--cp", cp, "--entry", "sample.cars.Dealer#sell", "--target", "sample.cars.ICar",
                        "--acquirer", "sample.cars.CarAcquirer#acquire"),
                run("--cp", cp, "--entry", "sample.cars.RentalService", "--target", "sample.cars.ICar",
                        "--acquirer", "sample.cars.CarAcquirer#acquire", "--acquire-annotation",
                        "sample.cars.AcquireCar", "--format", "json"),
                run("--cp", cp, "--entry", "sample.cars.RentalService#rentLegacyAnnotated", "--target",
                        "sample.cars.ICar", "--acquire-annotation", "sample.cars.LegacyAcquireCar"),
                run("--cp", cp, "--entry", "sample.cars.Garage#nope", "--target", "sample.cars.Nope"),
                run("--cp", "does-not-exist", "--entry", "a.B#c", "--target", "a.I"),
                run("--cp", cp, "--entry", "sample.events.EventFlowService", "--target", "sample.cars.ICar",
                        "--event-class", "sample.events.Event", "--event-dispatcher", "sample.events.EventDispatcher",
                        "--event-observer", "sample.events.IObserver", "--tree"),
                run("--bogus"),
                run("--help"));
        for (Run r : runs) {
            for (String text : List.of(r.out(), r.err())) {
                assertTrue(text.chars().allMatch(c -> c < 128),
                        () -> "non-ASCII output: " + text.chars().filter(c -> c >= 128)
                                .mapToObj(c -> String.valueOf((char) c)).distinct().toList());
            }
        }
    }

    @Test
    void usageErrors() throws Exception {
        Run missing = run("--cp", "x");
        assertEquals(2, missing.exit());
        assertTrue(missing.err().contains("zorunludur"));
        assertEquals(0, run("--help").exit());
    }
}
