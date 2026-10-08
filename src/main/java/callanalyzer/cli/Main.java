package callanalyzer.cli;

import callanalyzer.acquire.AcquireAnalyzer;
import callanalyzer.acquire.AcquireComparison;
import callanalyzer.analysis.AnalysisOptions;
import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.CallGraphAnalyzer;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.loader.ClassPathLoader;
import callanalyzer.report.AcquireReport;
import callanalyzer.report.CallTreePrinter;
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
              --format text|json         Cikti bicimi (varsayilan: text).
              --output, -o <dosya>       Ciktiyi dosyaya yaz.
              --help, -h                 Bu yardimi goster.
            """;

    private Main() {
    }

    public static void main(String[] args) {
        // Reports contain non-ASCII text; write UTF-8 regardless of the platform default.
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
        String format = "text";
        Path output = null;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--help", "-h" -> {
                        stdout.print(USAGE);
                        return 0;
                    }
                    case "--cp", "-cp", "--classpath" -> {
                        for (String p : value(args, ++i, a).split(File.pathSeparator)) {
                            if (!p.isBlank()) classPath.add(Path.of(p));
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
                    case "--format" -> format = value(args, ++i, a);
                    case "--output", "-o" -> output = Path.of(value(args, ++i, a));
                    default -> throw new IllegalArgumentException("Bilinmeyen arguman: " + a);
                }
            }
            if (classPath.isEmpty() || options.entries().isEmpty() || options.targets().isEmpty()) {
                throw new IllegalArgumentException("--cp, --entry ve --target zorunludur.");
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
            AnalysisResult result = new CallGraphAnalyzer(hierarchy, options).analyze();
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

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " bir deger bekliyor.");
        }
        return args[index];
    }
}
