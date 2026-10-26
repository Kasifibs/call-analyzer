package callanalyzer.scan;

import callanalyzer.acquire.AcquireComparison;
import callanalyzer.acquire.ReleaseCheck;

/**
 * Outcome of analysing one candidate. The full call graph is not kept (it can be large); it is handed to the
 * listener of {@link AcquireScanner#run} while the item is produced.
 *
 * @param index           1-based position in the scan
 * @param candidate       the analysed method
 * @param findings        number of calls to the target type found in its flow
 * @param analysedMethods number of methods in its flow
 * @param comparison      the acquire comparison, {@code null} on error
 * @param error           error message if the analysis failed, otherwise {@code null}
 */
public record ScanItem(int index, Candidate candidate, int findings, int analysedMethods,
                       AcquireComparison comparison, String error) {

    public enum Status {
        /** A class used with certainty is not acquired. */
        MISSING,
        /** An acquired element is not released (in a finally block of the same method). */
        UNRELEASED,
        /** Only possibly missing or undecided classes, or releases that are not in a finally block / undecided. */
        POSSIBLE,
        OK,
        ERROR
    }

    public Status status() {
        if (error != null) {
            return Status.ERROR;
        }
        if (!comparison.missing().isEmpty()) {
            return Status.MISSING;
        }
        ReleaseCheck release = comparison.release();
        if (release != null && !release.unreleased().isEmpty()) {
            return Status.UNRELEASED;
        }
        if (!comparison.possiblyMissing().isEmpty() || !comparison.undecided().isEmpty()
                || release != null && (!release.notInFinally().isEmpty() || !release.undecided().isEmpty())) {
            return Status.POSSIBLE;
        }
        return Status.OK;
    }
}
