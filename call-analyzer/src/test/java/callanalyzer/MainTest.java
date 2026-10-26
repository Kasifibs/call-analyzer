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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        Run exempt = run(concat(base, "sample.cars.RentalService#rent",
                "--exempt", "sample.cars.RentalService#rent=sample.cars.TowTruck,sample.cars.Opel"));
        assertEquals(0, exempt.exit(), exempt.err());
        assertTrue(exempt.out().contains("- ISTISNA -"));
        assertFalse(exempt.out().contains("[X] EKSIK"));
        assertTrue(exempt.err().contains("UYARI: Istisnadaki sinif sinif yolunda yok: sample.cars.Opel"));

        String[] lock = {"--cp", Fixtures.sampleClassesDir().toString(), "--target", "sample.cars.ICar",
                "--acquirer", "sample.cars.CarAcquirer#acquire", "--releaser", "sample.cars.CarAcquirer#release",
                "--fail-on-unreleased", "--entry"};
        Run unreleased = run(concat(lock, "sample.cars.LockingService#teslaNotReleased"));
        assertEquals(3, unreleased.exit(), unreleased.err());
        assertTrue(unreleased.out().contains("[R] RELEASE EDILMEMIS:\n  Tesla"), unreleased.out());
        assertEquals(0, run(concat(lock, "sample.cars.LockingService#releasedInFinally")).exit());
        Run lockJson = run(concat(lock, "sample.cars.LockingService#teslaNotReleased", "--format", "json"));
        assertTrue(lockJson.out().contains("\"status\": \"UNRELEASED\""), lockJson.out());
        assertEquals(2, run("--cp", Fixtures.sampleClassesDir().toString(), "--target", "sample.cars.ICar",
                "--releaser", "sample.cars.CarAcquirer#release", "--entry", "sample.cars.LockingService").exit(),
                "--releaser needs --acquirer");

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
    void htmlReportIsSelfContainedAsciiAndEmbedsTheData(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rapor.html");
        Run r = run("--cp", Fixtures.sampleClassesDir().toString(), "--entry", "sample.events.EventFlowService#rent",
                "--target", "sample.cars.ICar", "--event-class", "sample.events.Event", "--event-dispatcher",
                "sample.events.EventDispatcher", "--event-observer", "sample.events.IObserver",
                "--acquire-annotation", "sample.cars.AcquireCar", "--format", "html", "-o", file.toString());
        assertEquals(0, r.exit(), r.err());
        String html = Files.readString(file);
        assertTrue(html.chars().allMatch(c -> c < 128), "non-ASCII byte in the HTML report");
        assertTrue(html.startsWith("<!doctype html>"));
        assertFalse(html.contains("/*DATA*/"));
        assertFalse(html.contains("src=\"http") || html.contains("href=\"http"), "must not load external resources");
        // the embedded JSON must not be able to close its <script> block
        assertEquals(2, html.split("</script>", -1).length - 1);
        String data = html.substring(html.indexOf("<script id=\"data\""), html.indexOf("</script>"));
        assertTrue(data.contains("\"findings\":[{"));
        assertTrue(data.contains("CarRentedObserver#handle(CarRentedEvent)"));
        assertTrue(data.contains("\"e\":\"event CAR_RENTED\""));
        assertTrue(html.contains("&#199;a&#287;r&#305; Analizi"), "Turkish text is written as character references");
    }

    @Test
    void scanModeTextJsonAndHtml(@TempDir Path dir) throws Exception {
        String cp = Fixtures.sampleClassesDir().toString();
        String[] base = {"--cp", cp, "--scan", "--target", "sample.cars.ICar", "--include", "sample",
                "--acquirer", "sample.cars.CarAcquirer#acquire", "--acquire-annotation", "sample.cars.AcquireCar"};
        Run text = run(concat(base, "--fail-on-missing"));
        assertEquals(3, text.exit(), text.err());
        assertTrue(text.out().contains("=== Toplu acquire taramasi"));
        assertTrue(text.out().contains("[X]    TowTruck"));
        assertTrue(text.err().contains("metot bulundu"));

        Run json = run(concat(base, "--format", "json"));
        assertEquals(0, json.exit(), json.err());
        assertTrue(json.out().contains("\"status\": \"MISSING\""));

        Path index = dir.resolve("tarama.html");
        Run html = run(concat(base, "--format", "html", "-o", index.toString()));
        assertEquals(0, html.exit(), html.err());
        String page = Files.readString(index);
        assertTrue(page.chars().allMatch(c -> c < 128));
        assertTrue(page.contains("tarama_metotlar/"));
        try (var files = Files.list(dir.resolve("tarama_metotlar"))) {
            assertTrue(files.count() > 10);
        }

        assertEquals(2, run(concat(base, "--format", "html")).exit(), "html scan needs -o");
        assertEquals(2, run(concat(base, "--entry", "a.B#c")).exit(), "--scan and --entry exclude each other");
    }

    @Test
    void publishedEventsAreListedWithTheirOrigin() throws Exception {
        String[] args = {"--cp", Fixtures.sampleClassesDir().toString(), "--target", "sample.cars.ICar",
                "--entry", "sample.events.EventFlowService#aggregateMethodRef",
                "--entry", "sample.events.EventFlowService#unknownEvent",
                "--event-class", "sample.events.Event", "--event-dispatcher", "sample.events.EventDispatcher",
                "--event-observer", "sample.events.IObserver", "--event-detection", "dispatch"};
        Run text = run(args);
        assertEquals(0, text.exit(), text.err());
        String out = text.out();
        // TyreChangedEvent, plus CarReturnedEvent published by CarRentedObserver (reached through the undetermined event)
        assertTrue(out.contains("Akista yayinlanan event'ler (2 tip):"), out);
        assertTrue(out.contains("<- new TyreChangedEvent() (CarAggregate.java:11)"), out);
        assertTrue(out.contains("? (belirlenemedi)"), out);
        // the undetermined row comes last
        assertTrue(out.indexOf("TyreChangedEvent ") < out.indexOf("? (belirlenemedi)"), out);

        Run json = run(concat(args, "--format", "json"));
        assertTrue(json.out().contains("\"published\": {"), json.out());
        assertTrue(json.out().contains("\"sample.events.TyreChangedEvent\": ["), json.out());
    }

    @Test
    void newModeMarksFindingsReachedThroughUnconfirmedEvents() throws Exception {
        String[] args = {"--cp", Fixtures.sampleClassesDir().toString(), "--target", "sample.cars.ICar",
                "--entry", "sample.events.EventFlowService#createWithoutPublishing",
                "--event-class", "sample.events.Event", "--event-dispatcher", "sample.events.EventDispatcher",
                "--event-observer", "sample.events.IObserver"};
        Run text = run(args);
        assertEquals(0, text.exit(), text.err());
        assertTrue(text.out().contains("EVENT VARSAYIMI"), text.out());
        assertTrue(text.out().contains("[event TyreChangedEvent (varsayim)] TyreObserver#handle(TyreChangedEvent)"),
                text.out());
        assertTrue(text.out().contains("yayin: DOGRULANAMADI"), text.out());
        Run json = run(concat(args, "--format", "json"));
        assertTrue(json.out().contains("\"viaAssumedEvent\": true"), json.out());
        assertEquals(2, run(concat(args, "--event-detection", "bogus")).exit());
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
                run("--cp", cp, "--scan", "--target", "sample.cars.ICar", "--include", "sample",
                        "--acquirer", "sample.cars.CarAcquirer#acquire", "--acquire-annotation", "sample.cars.AcquireCar"),
                run("--cp", cp, "--scan", "--target", "sample.cars.ICar", "--include", "sample",
                        "--acquirer", "sample.cars.CarAcquirer#acquire", "--format", "json"),
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
