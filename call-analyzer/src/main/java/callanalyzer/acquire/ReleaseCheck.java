package callanalyzer.acquire;

import callanalyzer.acquire.AcquireRelease.Status;

import java.util.List;

/**
 * Release check of a method's own acquire calls (acquire annotations need no release): every acquired element must
 * be released by a call of the release method in a {@code finally} block of the same method.
 *
 * @param releaser the release method selector as given by the user
 * @param calls    release calls in the method (each source line once)
 * @param elements every element of every acquire call with its release status
 */
public record ReleaseCheck(String releaser, List<ReleaseCall> calls, List<AcquireRelease> elements) {

    public List<AcquireRelease> unreleased() {
        return with(Status.UNRELEASED);
    }

    public List<AcquireRelease> notInFinally() {
        return with(Status.NOT_IN_FINALLY);
    }

    public List<AcquireRelease> undecided() {
        return with(Status.UNDECIDED);
    }

    public List<AcquireRelease> released() {
        return with(Status.RELEASED);
    }

    private List<AcquireRelease> with(Status status) {
        return elements.stream().filter(e -> e.status() == status).toList();
    }
}
