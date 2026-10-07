package callanalyzer.loader;

import callanalyzer.model.ClassInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads every class found in a list of class-path entries (class directories, {@code .jar}/{@code .war}
 * files, or {@code dir/*} wildcards). As with a real class path, the first definition of a class wins.
 */
public final class ClassPathLoader {

    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    private final Map<String, byte[]> classBytes = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public static ClassPathLoader load(List<Path> entries) throws IOException {
        ClassPathLoader loader = new ClassPathLoader();
        for (Path entry : entries) {
            loader.loadEntry(entry);
        }
        return loader;
    }

    public Map<String, ClassInfo> classes() {
        return classes;
    }

    /** Raw class files by internal name, for analyses that need the full method bodies (see {@code acquire}). */
    public Map<String, byte[]> classBytes() {
        return classBytes;
    }

    public List<String> warnings() {
        return warnings;
    }

    private void loadEntry(Path entry) throws IOException {
        if (entry.getFileName() != null && entry.getFileName().toString().equals("*")) {
            Path dir = entry.getParent() == null ? Path.of(".") : entry.getParent();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.filter(ClassPathLoader::isArchive).sorted().toList()) {
                    loadArchive(p);
                }
            }
        } else if (Files.isDirectory(entry)) {
            loadDirectory(entry);
        } else if (Files.isRegularFile(entry) && isArchive(entry)) {
            loadArchive(entry);
        } else if (Files.isRegularFile(entry) && entry.toString().endsWith(".class")) {
            add(Files.readAllBytes(entry), entry.toString());
        } else {
            warnings.add("Sınıf yolu girdisi bulunamadı ya da desteklenmiyor: " + entry);
        }
    }

    private void loadDirectory(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.toString().endsWith(".class"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        try {
                            add(Files.readAllBytes(p), p.toString());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    private void loadArchive(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".class") || n.startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(e)) {
                    add(in.readAllBytes(), archive + "!/" + n);
                }
            }
        }
    }

    private void add(byte[] bytes, String origin) {
        try {
            ClassInfo info = ClassInfoReader.read(bytes);
            if (info.name().equals("module-info") || info.name().endsWith("/module-info")) {
                return;
            }
            if (classes.putIfAbsent(info.name(), info) == null) {
                classBytes.put(info.name(), bytes);
            }
        } catch (RuntimeException e) {
            warnings.add("Sınıf okunamadı (" + origin + "): " + e);
        }
    }

    private static boolean isArchive(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".jar") || n.endsWith(".war") || n.endsWith(".zip");
    }
}
