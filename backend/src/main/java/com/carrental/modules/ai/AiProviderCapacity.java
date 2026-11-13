package com.carrental.modules.ai;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public final class AiProviderCapacity {
    public enum Provider {
        QWEN,
        OSRM
    }

    public static final class CapacityExceededException extends RuntimeException {
        private final Provider provider;

        private CapacityExceededException(Provider provider) {
            super("Provider capacity is full.");
            this.provider = provider;
        }

        public Provider provider() {
            return provider;
        }
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }

    private final Semaphore qwen = new Semaphore(1);
    private final Semaphore osrm = new Semaphore(2);

    public Permit acquire(Provider provider) {
        Semaphore semaphore = switch (provider) {
            case QWEN -> qwen;
            case OSRM -> osrm;
        };
        if (!semaphore.tryAcquire()) {
            throw new CapacityExceededException(provider);
        }
        return new Permit(semaphore);
    }
}
