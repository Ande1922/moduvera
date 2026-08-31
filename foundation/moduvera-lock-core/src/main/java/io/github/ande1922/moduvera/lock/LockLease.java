package io.github.ande1922.moduvera.lock;

@FunctionalInterface
public interface LockLease extends AutoCloseable {

    @Override
    void close();
}
