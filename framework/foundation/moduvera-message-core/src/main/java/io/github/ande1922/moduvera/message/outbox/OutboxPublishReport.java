package io.github.ande1922.moduvera.message.outbox;

public record OutboxPublishReport(
        int claimed, int published, int failed, int deferred, int staleUpdates) {

    public OutboxPublishReport {
        if (claimed < 0
                || published < 0
                || failed < 0
                || deferred < 0
                || staleUpdates < 0
                || claimed != published + failed + deferred
                || staleUpdates > published + failed) {
            throw new IllegalArgumentException("outbox report counts are inconsistent");
        }
    }

    public boolean hasClaimedWork() {
        return claimed > 0;
    }

    public boolean leaseDeadlineReached() {
        return deferred > 0;
    }
}
