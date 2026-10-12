package callanalyzer.scan;

import java.util.List;

/**
 * All candidates of a scan and their outcomes.
 *
 * @param acquirer   acquirer method selector, may be {@code null}
 * @param annotation acquire annotation (internal name), may be {@code null}
 * @param items      one item per candidate, in scan order
 */
public record ScanResult(String acquirer, String annotation, List<ScanItem> items) {

    public long count(ScanItem.Status status) {
        return items.stream().filter(i -> i.status() == status).count();
    }
}
