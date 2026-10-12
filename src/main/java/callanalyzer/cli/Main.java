package callanalyzer.cli;

import callanalyzer.acquire.AcquireAnalyzer;
import callanalyzer.acquire.AcquireComparison;
import callanalyzer.analysis.AnalysisOptions;
import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.CallGraphAnalyzer;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.events.EventConfig;
import callanalyzer.loader.ClassPathLoader;
import callanalyzer.report.AcquireReport;
import callanalyzer.report.CallTreePrinter;
import callanalyzer.report.EventReport;
import callanalyzer.report.JsonReport;
import callanalyzer.report.TextReport;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Command line entry point. Run with {@code --help} for usage. */
public final class Main {

    private static final String USAGE = """
            Kullanim:
              java -jar call-analyzer.jar --cp <sinif-yolu> --entry <metot> --target <tip> [secenekler]

            Zorunlu:
              --cp, -cp <yol>            Analiz edilecek derlenmis kod: sinif dizini (target/classes), .jar
                                         ya da dizin/* ; ':' (Windows'ta ';') ile ayrilabilir, tekrarlanabilir.
              --entry, -e <metot>        Baslangic metodu. Ornekler:
                                           com.example.Garage#service
                                           com.example.Garage#service(ICar, int)
                                           com.example.Garage#service(Lcom/example/ICar;I)V
                                           com.example.Garage           (siniftaki tum metotlar)
                                         Tekrarlanabilir.
              --target, -t <tip>         Uygulayicilari aranan arayuz/sinif, or. com.example.ICar. Tekrarlanabilir.

            Secenekler:
              --include <paket>          Yalnizca bu onekle baslayan siniflarin icine gir (tekrarlanabilir).
              --exclude <paket>          Bu onekle baslayan siniflarin icine girme (tekrarlanabilir).
              --max-depth <n>            En fazla n seviye derine in (varsayilan: sinirsiz).
              --include-constructors     'new Bmw()' gibi kurucu cagrilarini da raporla.
              --only-interface-methods   Yalnizca hedef tipin kendi metotlarini raporla (or. ICar metotlari).
              --skip-internal            Hedef tipi uygulayan siniflarin kendi icinden yapilan cagrilari raporlama.
              --direct-only              Yalnizca statik alici tipi hedef tipten olan cagrilari raporla
                                         (or. Object#toString() uzerinden 'olasi' eslesmeleri atla).
              --acquirer <metot>         Or. com.example.CarAcquirer#acquire : akistaki bu cagrilara verilen
                                         listedeki hedef tip nesnelerini bulur ve akista kullanilan siniflarla
                                         karsilastirir (listede olmayanlari raporlar).
              --acquire-annotation <tip> Or. com.example.AcquireCar : analiz edilen (giris) metotta bu anotasyon
                                         var mi bakar, icindeki siniflari (cars = {Opel.class, ...}) listeler ve
                                         karsilastirmaya ekler. --acquirer ile birlikte de kullanilabilir.
              --fail-on-missing          Karsilastirmada kesin eksik sinif bulunursa cikis kodu 3 olsun (CI icin).
              --tree                     Gezilen cagri agacini da yazdir.

            Event takibi (ucu de birlikte verilmeli):
              --event-class <tip>        Tum event'lerin temel sinifi, or. com.example.event.Event
              --event-dispatcher <tip>   Event'lerin yayinlandigi ve observer'larin kaydoldugu sinif,
                                         or. com.example.event.EventDispatcher
              --event-observer <tip>     Observer'larin temel tipi, or. com.example.event.IObserver
              --event-handle-method <ad> Observer'in event'i alan metodu (varsayilan: handle)
                                         dispatch(event) cagrisinda akis, yalnizca o event adina kayitli
                                         observer'larin handle metoduna devam eder.

            Ayar dosyasi:
              --config <dosya>           Secenekleri dosyadan oku. Her satir 'secenek=deger' (basta -- olmadan),
                                         or. 'event-class=com.example.event.Event'. Tekrarlanabilen secenekler
                                         (cp, entry, target, include, exclude) virgulle ayrilabilir; tree=true
                                         gibi bayraklar true/false alir. Komut satiri dosyadakilere eklenir.
              --format text|json         Cikti bicimi (varsayilan: text).
              --output, -o <dosya>       Ciktiyi dosyaya yaz.
              --help, -h                 Bu yardimi goster.
            """;

    private Main() {
    }

    public static void main(String[] args) {
        // Output is plain ASCII, but class names from the analysed code may not be: always write UTF-8.
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.exit(run(args, out, err));
    }

    static int run(String[] args, PrintStream stdout, PrintStream stderr) {
        List<Path> classPath = new ArrayList<>();
        AnalysisOptions options = new AnalysisOptions();
        boolean tree = false;
        String acquirer = null;
        String acquireAnnotation = null;
        boolean failOnMissing = false;
        String eventClass = null;
        String eventDispatcher = null;
        String eventObserver = null;
        String eventHandle = null;
        String format = "text";
        Path output = null;
        try {
            args = expandConfig(args);
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--help", "-h" -> {
                        stdout.print(USAGE);
                        return 0;
                    }
                    case "--cp", "-cp", "--classpath" -> {
                        for (String p : value(args, ++i, a).split(File.pathSeparator)) {
                            if (!p.isBlank()) classPath.addAll(classPathEntry(p.strip()));
                        }
                    }
                    case "--entry", "-e" -> options.entry(value(args, ++i, a));
                    case "--target", "-t" -> options.target(value(args, ++i, a));
                    case "--include" -> options.include(value(args, ++i, a));
                    case "--exclude" -> options.exclude(value(args, ++i, a));
                    case "--max-depth" -> options.maxDepth(Integer.parseInt(value(args, ++i, a)));
                    case "--include-constructors" -> options.includeConstructors(true);
                    case "--only-interface-methods" -> options.onlyInterfaceMethods(true);
                    case "--skip-internal" -> options.skipInternalCalls(true);
                    case "--direct-only" -> options.directOnly(true);
                    case "--tree" -> tree = true;
                    case "--acquirer" -> acquirer = value(args, ++i, a);
                    case "--acquire-annotation" -> acquireAnnotation = value(args, ++i, a);
                    case "--fail-on-missing" -> failOnMissing = true;
                    case "--event-class" -> eventClass = value(args, ++i, a);
                    case "--event-dispatcher" -> eventDispatcher = value(args, ++i, a);
                    case "--event-observer" -> eventObserver = value(args, ++i, a);
                    case "--event-handle-method" -> eventHandle = value(args, ++i, a);
                    case "--format" -> format = value(args, ++i, a);
                    case "--output", "-o" -> output = Path.of(value(args, ++i, a));
                    default -> throw new IllegalArgumentException("Bilinmeyen arguman: " + a);
                }
            }
            if (classPath.isEmpty() || options.entries().isEmpty() || options.targets().isEmpty()) {
                throw new IllegalArgumentException("--cp, --entry ve --target zorunludur.");
            }
            if (eventClass != null || eventDispatcher != null || eventObserver != null) {
                options.events(new EventConfig(eventClass, eventDispatcher, eventObserver, eventHandle));
            }
            if (!format.equals("text") && !format.equals("json")) {
                throw new IllegalArgumentException("Gecersiz --format: " + format);
            }
        } catch (IllegalArgumentException e) {
            stderr.println("Hata: " + e.getMessage());
            stderr.println();
            stderr.print(USAGE);
            return 2;
        }

        try {
            ClassPathLoader loader = ClassPathLoader.load(classPath);
            loader.warnings().forEach(w -> stderr.println("UYARI: " + w));
            if (loader.classes().isEmpty()) {
                stderr.println("Hata: sinif yolunda hic .class dosyasi bulunamadi.");
                return 1;
            }
            ClassHierarchy hierarchy = new ClassHierarchy(loader.classes());
            AnalysisResult result = new CallGraphAnalyzer(hierarchy, options, loader.classBytes()).analyze();
            AcquireComparison acquire = acquirer == null && acquireAnnotation == null
                    ? null
                    : new AcquireAnalyzer(result, loader.classBytes(), acquirer, acquireAnnotation).analyze();

            PrintWriter out = output == null
                    ? new PrintWriter(new OutputStreamWriter(stdout, StandardCharsets.UTF_8))
                    : new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8));
            try {
                if (format.equals("json")) {
                    JsonReport.write(result, acquire, out);
                } else {
                    TextReport.write(result, out);
                    if (options.events() != null) {
                        EventReport.writeText(result, out);
                    }
                    if (acquire != null) {
                        AcquireReport.writeText(acquire, out);
                    }
                    if (tree) {
                        CallTreePrinter.write(result, out);
                    }
                }
            } finally {
                out.flush();
                if (output != null) out.close();
            }
            if (output != null) {
                stderr.println(result.findings().size() + " cagri bulundu, rapor yazildi: " + output);
            }
            if (failOnMissing && acquire != null && !acquire.missing().isEmpty()) {
                return 3;
            }
            return 0;
        } catch (IllegalArgumentException e) {
            stderr.println("Hata: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            stderr.println("G/C hatasi: " + e.getMessage());
            return 1;
        }
    }

    /**
     * A class path entry; {@code dir/*} (or {@code dir\*}) stands for every archive in the directory. The wildcard
     * is expanded here because {@code *} is not a valid path character on Windows.
     */
    static List<Path> classPathEntry(String entry) {
        if (!entry.equals("*") && !entry.endsWith("/*") && !entry.endsWith("\\*")) {
            return List.of(Path.of(entry));
        }
        String dirName = entry.substring(0, entry.length() - 1);
        Path dir = dirName.isEmpty() ? Path.of(".") : Path.of(dirName);
        try (var files = Files.list(dir)) {
            List<Path> archives = files.filter(f -> {
                String n = f.getFileName().toString().toLowerCase();
                return n.endsWith(".jar") || n.endsWith(".war") || n.endsWith(".zip");
            }).sorted().toList();
            if (archives.isEmpty()) {
                throw new IllegalArgumentException("Dizinde jar bulunamadi: " + dir);
            }
            return archives;
        } catch (IOException e) {
            throw new IllegalArgumentException("Dizin okunamadi: " + dir + " (" + e.getMessage() + ")");
        }
    }

    private static final Set<String> REPEATABLE = Set.of("cp", "classpath", "entry", "target", "include", "exclude");

    /**
     * Replaces {@code --config <file>} with the options in the file. Each line is {@code option=value} (comments
     * start with {@code #}); backslashes are kept as they are, so Windows paths can be written directly.
     */
    public static String[] expandConfig(String[] args) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].equals("--config")) {
                result.add(args[i]);
                continue;
            }
            Path file = Path.of(value(args, ++i, "--config"));
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalArgumentException("Ayar dosyasi okunamadi: " + file + " (" + e.getMessage() + ")");
            }
            List<String> fromFile = new ArrayList<>();
            for (String raw : lines) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    throw new IllegalArgumentException("Ayar dosyasinda gecersiz satir (secenek=deger bekleniyor): "
                            + line);
                }
                String key = line.substring(0, eq).strip().replaceFirst("^-+", "");
                String val = line.substring(eq + 1).strip();
                if (val.equalsIgnoreCase("true")) {
                    fromFile.add("--" + key);
                } else if (val.equalsIgnoreCase("false")) {
                    continue;
                } else if (REPEATABLE.contains(key)) {
                    for (String part : val.split(",")) {
                        if (!part.isBlank()) {
                            fromFile.add("--" + key);
                            fromFile.add(part.strip());
                        }
                    }
                } else {
                    fromFile.add("--" + key);
                    fromFile.add(val);
                }
            }
            result.addAll(0, fromFile); // the command line comes after (and so overrides) the file
        }
        return result.toArray(String[]::new);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " bir deger bekliyor.");
        }
        return args[index];
    }
}
