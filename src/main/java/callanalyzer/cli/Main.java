package callanalyzer.cli;

import callanalyzer.analysis.AnalysisOptions;
import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.CallGraphAnalyzer;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.loader.ClassPathLoader;
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
            Kullanım:
              java -jar call-analyzer.jar --cp <sınıf-yolu> --entry <metot> --target <tip> [seçenekler]

            Zorunlu:
              --cp, -cp <yol>            Analiz edilecek derlenmiş kod: sınıf dizini (target/classes), .jar
                                         ya da dizin/* ; ':' (Windows'ta ';') ile ayrılabilir, tekrarlanabilir.
              --entry, -e <metot>        Başlangıç metodu. Örnekler:
                                           com.example.Garage#service
                                           com.example.Garage#service(ICar, int)
                                           com.example.Garage#service(Lcom/example/ICar;I)V
                                           com.example.Garage           (sınıftaki tüm metotlar)
                                         Tekrarlanabilir.
              --target, -t <tip>         Uygulayıcıları aranan arayüz/sınıf, ör. com.example.ICar. Tekrarlanabilir.

            Seçenekler:
              --include <paket>          Yalnızca bu önekle başlayan sınıfların içine gir (tekrarlanabilir).
              --exclude <paket>          Bu önekle başlayan sınıfların içine girme (tekrarlanabilir).
              --max-depth <n>            En fazla n seviye derine in (varsayılan: sınırsız).
              --include-constructors     'new Bmw()' gibi kurucu çağrılarını da raporla.
              --only-interface-methods   Yalnızca hedef tipin kendi metotlarını raporla (ör. ICar metotları).
              --skip-internal            Hedef tipi uygulayan sınıfların kendi içinden yapılan çağrıları raporlama.
              --direct-only              Yalnızca statik alıcı tipi hedef tipten olan çağrıları raporla
                                         (ör. Object#toString() üzerinden 'olası' eşleşmeleri atla).
              --tree                     Gezilen çağrı ağacını da yazdır.
              --format text|json         Çıktı biçimi (varsayılan: text).
              --output, -o <dosya>       Çıktıyı dosyaya yaz.
              --help, -h                 Bu yardımı göster.
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
                    case "--format" -> format = value(args, ++i, a);
                    case "--output", "-o" -> output = Path.of(value(args, ++i, a));
                    default -> throw new IllegalArgumentException("Bilinmeyen argüman: " + a);
                }
            }
            if (classPath.isEmpty() || options.entries().isEmpty() || options.targets().isEmpty()) {
                throw new IllegalArgumentException("--cp, --entry ve --target zorunludur.");
            }
            if (!format.equals("text") && !format.equals("json")) {
                throw new IllegalArgumentException("Geçersiz --format: " + format);
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
                stderr.println("Hata: sınıf yolunda hiç .class dosyası bulunamadı.");
                return 1;
            }
            ClassHierarchy hierarchy = new ClassHierarchy(loader.classes());
            AnalysisResult result = new CallGraphAnalyzer(hierarchy, options).analyze();

            PrintWriter out = output == null
                    ? new PrintWriter(new OutputStreamWriter(stdout, StandardCharsets.UTF_8))
                    : new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8));
            try {
                if (format.equals("json")) {
                    JsonReport.write(result, out);
                } else {
                    TextReport.write(result, out);
                    if (tree) {
                        CallTreePrinter.write(result, out);
                    }
                }
            } finally {
                out.flush();
                if (output != null) out.close();
            }
            if (output != null) {
                stderr.println(result.findings().size() + " çağrı bulundu, rapor yazıldı: " + output);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            stderr.println("Hata: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            stderr.println("G/Ç hatası: " + e.getMessage());
            return 1;
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " bir değer bekliyor.");
        }
        return args[index];
    }
}
