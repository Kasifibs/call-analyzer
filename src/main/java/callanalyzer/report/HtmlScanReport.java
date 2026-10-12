package callanalyzer.report;

import callanalyzer.acquire.AcquireComparison;
import callanalyzer.analysis.AnalysisResult;
import callanalyzer.scan.ScanItem;
import callanalyzer.scan.ScanResult;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * HTML output of a scan: an overview page ({@code rapor.html}) and one full report per method in a folder next to
 * it ({@code rapor_metotlar/001-RentalService-rent.html}), each linking back to the overview.
 */
public final class HtmlScanReport {

    private final Path index;
    private final Path pagesDir;
    private final Map<Integer, String> pages = new HashMap<>();

    public HtmlScanReport(Path index) {
        this.index = index.toAbsolutePath();
        String base = index.getFileName().toString().replaceFirst("\\.html?$", "");
        this.pagesDir = this.index.resolveSibling(base + "_metotlar");
    }

    /** Writes the page of one analysed method (called while the scan runs). */
    public void writeMethodPage(ScanItem item, AnalysisResult result) {
        if (result == null) {
            return;
        }
        String name = String.format("%03d-%s-%s.html", item.index(),
                item.candidate().method().ownerDotted().replaceAll(".*\\.", ""),
                item.candidate().method().name()).replaceAll("[^A-Za-z0-9._-]", "_");
        try {
            Files.createDirectories(pagesDir);
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(pagesDir.resolve(name), StandardCharsets.UTF_8))) {
                AcquireComparison cmp = item.comparison();
                HtmlReport.write(result, cmp, "../" + index.getFileName(), out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pages.put(item.index(), pagesDir.getFileName() + "/" + name);
    }

    public void writeIndex(ScanResult scan) throws IOException {
        Map<String, Object> data = ScanReport.toJson(scan, pages);
        data.put("generated", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(index, StandardCharsets.UTF_8))) {
            HtmlReport.page("scan.html", data, out);
        }
    }

    public Path pagesDir() {
        return pagesDir;
    }
}
