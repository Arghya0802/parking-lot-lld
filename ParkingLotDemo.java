import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-file Parking Lot LLD reference implementation.
 *
 * <p>Run with:
 * <pre>
 *   javac ParkingLotDemo.java
 *   java ParkingLotDemo optimistic
 *   java ParkingLotDemo pessimistic
 * </pre>
 *
 * <p>Concepts demonstrated:
 * Strategy and Dependency Inversion, immutable snapshots, thread safety,
 * Semaphore admission control, AtomicLong IDs, optimistic CAS, a pessimistic
 * mutex, and a concurrent one-spot collision test.
 */
public final class ParkingLotDemo {
    private static final int CALLER_COUNT = 40;
    private static final int ENTRY_PERMITS = 8;
    private static final int MAX_OPTIMISTIC_RETRIES = 128;
    private static final long CENTS_PER_HOUR = 500L;
    private static final long SECONDS_PER_HOUR = 3_600L;

    private ParkingLotDemo() {
    }

    enum VehicleType {
        MOTORCYCLE,
        CAR,
        ELECTRIC_CAR,
        TRUCK
    }

    enum SpotType {
        MOTORCYCLE,
        COMPACT,
        LARGE,
        ELECTRIC
    }

    enum SpotStatus {
        AVAILABLE,
        OCCUPIED,
        OUT_OF_SERVICE
    }

    enum LockingMode {
        OPTIMISTIC,
        PESSIMISTIC;

        static LockingMode from(String value) {
            return switch (value.toLowerCase()) {
                case "optimistic" -> OPTIMISTIC;
                case "pessimistic" -> PESSIMISTIC;
                default -> throw new IllegalArgumentException(
                        "Unknown locking mode '" + value
                                + "'; expected optimistic or pessimistic");
            };
        }
    }

    record Vehicle(String registration, VehicleType type) {
        Vehicle {
            if (registration == null || registration.isBlank()) {
                throw new IllegalArgumentException("Vehicle registration is required");
            }
            Objects.requireNonNull(type, "Vehicle type is required");
        }
    }

    record ParkingSpot(
            String id,
            SpotType type,
            int distanceFromEntry,
            SpotStatus status,
            String currentTicketId) {

        ParkingSpot {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Spot ID is required");
            }
            Objects.requireNonNull(type, "Spot type is required");
            Objects.requireNonNull(status, "Spot status is required");
            if (distanceFromEntry < 0) {
                throw new IllegalArgumentException("Distance cannot be negative");
            }
            boolean hasOwner = currentTicketId != null;
            if ((status == SpotStatus.OCCUPIED) != hasOwner) {
                throw new IllegalArgumentException(
                        "Occupied spots require a ticket owner and other states forbid one");
            }
        }

        static ParkingSpot available(String id, SpotType type, int distanceFromEntry) {
            return new ParkingSpot(id, type, distanceFromEntry, SpotStatus.AVAILABLE, null);
        }

        boolean canFit(Vehicle vehicle) {
            return switch (vehicle.type()) {
                case MOTORCYCLE -> type == SpotType.MOTORCYCLE
                        || type == SpotType.COMPACT
                        || type == SpotType.LARGE;
                case CAR -> type == SpotType.COMPACT || type == SpotType.LARGE;
                case ELECTRIC_CAR -> type == SpotType.ELECTRIC
                        || type == SpotType.COMPACT
                        || type == SpotType.LARGE;
                case TRUCK -> type == SpotType.LARGE;
            };
        }

        boolean isAvailable() {
            return status == SpotStatus.AVAILABLE;
        }

        ParkingSpot occupy(String ticketId) {
            if (!isAvailable()) {
                throw new IllegalStateException(
                        "Spot " + id + " is not available; refresh candidate state");
            }
            return new ParkingSpot(id, type, distanceFromEntry, SpotStatus.OCCUPIED, ticketId);
        }

        ParkingSpot releaseOwnedBy(String ticketId) {
            if (status != SpotStatus.OCCUPIED
                    || !Objects.equals(currentTicketId, ticketId)) {
                throw new IllegalStateException(
                        "Spot " + id + " is not owned by ticket " + ticketId
                                + "; check for a duplicate or stale exit request");
            }
            return available(id, type, distanceFromEntry);
        }
    }

    record ParkingTicket(
            String id,
            Vehicle vehicle,
            String spotId,
            Instant enteredAt) {
        ParkingTicket {
            Objects.requireNonNull(id, "Ticket ID is required");
            Objects.requireNonNull(vehicle, "Vehicle is required");
            Objects.requireNonNull(spotId, "Spot ID is required");
            Objects.requireNonNull(enteredAt, "Entry time is required");
        }
    }

    record Receipt(
            String ticketId,
            String spotId,
            Instant exitedAt,
            long feeInCents) {
    }

    /** The complete immutable aggregate state makes one CAS a business transaction. */
    record LotState(
            Map<String, ParkingSpot> spots,
            Map<String, ParkingTicket> activeTickets,
            long version) {
        LotState {
            spots = Map.copyOf(spots);
            activeTickets = Map.copyOf(activeTickets);
            if (version < 0) {
                throw new IllegalArgumentException("State version cannot be negative");
            }
        }
    }

    interface SpotAllocationStrategy {
        Optional<ParkingSpot> select(Vehicle vehicle, Collection<ParkingSpot> spots);
    }

    static final class NearestCompatibleSpotStrategy implements SpotAllocationStrategy {
        private static final Comparator<ParkingSpot> BY_DISTANCE_THEN_ID =
                Comparator.comparingInt(ParkingSpot::distanceFromEntry)
                        .thenComparing(ParkingSpot::id);

        @Override
        public Optional<ParkingSpot> select(
                Vehicle vehicle,
                Collection<ParkingSpot> spots) {
            return spots.stream()
                    .filter(ParkingSpot::isAvailable)
                    .filter(spot -> spot.canFit(vehicle))
                    .min(BY_DISTANCE_THEN_ID);
        }
    }

    interface PricingStrategy {
        long feeInCents(ParkingTicket ticket, Instant exitedAt);
    }

    static final class HourlyPricingStrategy implements PricingStrategy {
        private final long centsPerHour;

        HourlyPricingStrategy(long centsPerHour) {
            if (centsPerHour < 0) {
                throw new IllegalArgumentException("Hourly rate cannot be negative");
            }
            this.centsPerHour = centsPerHour;
        }

        @Override
        public long feeInCents(ParkingTicket ticket, Instant exitedAt) {
            long parkedSeconds = Math.max(
                    0L,
                    Duration.between(ticket.enteredAt(), exitedAt).getSeconds());
            long billableHours = Math.max(
                    1L,
                    (parkedSeconds + SECONDS_PER_HOUR - 1L) / SECONDS_PER_HOUR);
            return Math.multiplyExact(billableHours, centsPerHour);
        }
    }

    static final class ParkingFullException extends RuntimeException {
        ParkingFullException(String message) {
            super(message);
        }
    }

    static final class ConcurrentAllocationException extends RuntimeException {
        ConcurrentAllocationException(String message) {
            super(message);
        }
    }

    /**
     * Thread-safe aggregate facade.
     *
     * <p>Optimistic mode computes against an immutable snapshot and atomically
     * replaces the entire aggregate with compare-and-set. Pessimistic mode holds
     * a ReentrantLock across selection and mutation. Both preserve the same
     * spot/ticket invariant.
     */
    static final class ParkingLot {
        private final AtomicReference<LotState> state;
        private final SpotAllocationStrategy allocationStrategy;
        private final PricingStrategy pricingStrategy;
        private final LockingMode lockingMode;
        private final Semaphore entryPermits;
        private final AtomicLong ticketSequence = new AtomicLong();
        private final Lock pessimisticStateLock = new ReentrantLock(true);
        private final Clock clock;

        ParkingLot(
                Collection<ParkingSpot> spots,
                SpotAllocationStrategy allocationStrategy,
                PricingStrategy pricingStrategy,
                LockingMode lockingMode,
                int entryPermitCount,
                Clock clock) {
            Objects.requireNonNull(spots, "Spots are required");
            Map<String, ParkingSpot> spotsById = new HashMap<>();
            for (ParkingSpot spot : spots) {
                ParkingSpot previous = spotsById.put(spot.id(), spot);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate spot ID: " + spot.id());
                }
            }
            if (spotsById.isEmpty()) {
                throw new IllegalArgumentException("At least one parking spot is required");
            }
            if (entryPermitCount <= 0) {
                throw new IllegalArgumentException("Entry permit count must be positive");
            }
            this.state = new AtomicReference<>(new LotState(spotsById, Map.of(), 0L));
            this.allocationStrategy = Objects.requireNonNull(allocationStrategy);
            this.pricingStrategy = Objects.requireNonNull(pricingStrategy);
            this.lockingMode = Objects.requireNonNull(lockingMode);
            this.entryPermits = new Semaphore(entryPermitCount, true);
            this.clock = Objects.requireNonNull(clock);
        }

        ParkingTicket park(Vehicle vehicle) throws InterruptedException {
            Objects.requireNonNull(vehicle, "Vehicle is required");
            entryPermits.acquire();
            try {
                String ticketId = "T-" + ticketSequence.incrementAndGet();
                Instant enteredAt = clock.instant();
                return lockingMode == LockingMode.OPTIMISTIC
                        ? parkOptimistically(vehicle, ticketId, enteredAt)
                        : parkPessimistically(vehicle, ticketId, enteredAt);
            } finally {
                entryPermits.release();
            }
        }

        Receipt exit(String ticketId) {
            if (ticketId == null || ticketId.isBlank()) {
                throw new IllegalArgumentException("Ticket ID is required");
            }
            Instant exitedAt = clock.instant();
            return lockingMode == LockingMode.OPTIMISTIC
                    ? exitOptimistically(ticketId, exitedAt)
                    : exitPessimistically(ticketId, exitedAt);
        }

        private ParkingTicket parkOptimistically(
                Vehicle vehicle,
                String ticketId,
                Instant enteredAt) {
            for (int attempt = 1; attempt <= MAX_OPTIMISTIC_RETRIES; attempt++) {
                LotState current = state.get();
                ParkTransition transition = buildParkTransition(
                        current, vehicle, ticketId, enteredAt);
                if (state.compareAndSet(current, transition.nextState())) {
                    return transition.ticket();
                }
                Thread.onSpinWait();
            }
            throw new ConcurrentAllocationException(
                    "Parking allocation exceeded " + MAX_OPTIMISTIC_RETRIES
                            + " optimistic retries; retry the request with backoff");
        }

        private ParkingTicket parkPessimistically(
                Vehicle vehicle,
                String ticketId,
                Instant enteredAt) {
            pessimisticStateLock.lock();
            try {
                ParkTransition transition = buildParkTransition(
                        state.get(), vehicle, ticketId, enteredAt);
                state.set(transition.nextState());
                return transition.ticket();
            } finally {
                pessimisticStateLock.unlock();
            }
        }

        private Receipt exitOptimistically(String ticketId, Instant exitedAt) {
            for (int attempt = 1; attempt <= MAX_OPTIMISTIC_RETRIES; attempt++) {
                LotState current = state.get();
                ExitTransition transition = buildExitTransition(current, ticketId, exitedAt);
                if (state.compareAndSet(current, transition.nextState())) {
                    return transition.receipt();
                }
                Thread.onSpinWait();
            }
            throw new ConcurrentAllocationException(
                    "Parking exit exceeded " + MAX_OPTIMISTIC_RETRIES
                            + " optimistic retries; retry the request with backoff");
        }

        private Receipt exitPessimistically(String ticketId, Instant exitedAt) {
            pessimisticStateLock.lock();
            try {
                ExitTransition transition = buildExitTransition(state.get(), ticketId, exitedAt);
                state.set(transition.nextState());
                return transition.receipt();
            } finally {
                pessimisticStateLock.unlock();
            }
        }

        private ParkTransition buildParkTransition(
                LotState current,
                Vehicle vehicle,
                String ticketId,
                Instant enteredAt) {
            ParkingSpot selected = allocationStrategy
                    .select(vehicle, current.spots().values())
                    .orElseThrow(() -> new ParkingFullException(
                            "No compatible spot is currently available for " + vehicle.type()));

            ParkingTicket ticket = new ParkingTicket(
                    ticketId, vehicle, selected.id(), enteredAt);
            Map<String, ParkingSpot> nextSpots = new HashMap<>(current.spots());
            nextSpots.put(selected.id(), selected.occupy(ticketId));
            Map<String, ParkingTicket> nextTickets = new HashMap<>(current.activeTickets());
            nextTickets.put(ticketId, ticket);
            LotState nextState = new LotState(
                    nextSpots, nextTickets, current.version() + 1L);
            return new ParkTransition(nextState, ticket);
        }

        private ExitTransition buildExitTransition(
                LotState current,
                String ticketId,
                Instant exitedAt) {
            ParkingTicket ticket = Optional.ofNullable(current.activeTickets().get(ticketId))
                    .orElseThrow(() -> new IllegalStateException(
                            "Active ticket " + ticketId
                                    + " was not found; check for a duplicate exit request"));
            ParkingSpot spot = Optional.ofNullable(current.spots().get(ticket.spotId()))
                    .orElseThrow(() -> new IllegalStateException(
                            "Ticket " + ticketId + " refers to missing spot " + ticket.spotId()));

            Map<String, ParkingSpot> nextSpots = new HashMap<>(current.spots());
            nextSpots.put(spot.id(), spot.releaseOwnedBy(ticketId));
            Map<String, ParkingTicket> nextTickets = new HashMap<>(current.activeTickets());
            nextTickets.remove(ticketId);
            LotState nextState = new LotState(
                    nextSpots, nextTickets, current.version() + 1L);
            Receipt receipt = new Receipt(
                    ticketId,
                    spot.id(),
                    exitedAt,
                    pricingStrategy.feeInCents(ticket, exitedAt));
            return new ExitTransition(nextState, receipt);
        }

        long availableSpotCount() {
            return state.get().spots().values().stream()
                    .filter(ParkingSpot::isAvailable)
                    .count();
        }

        int activeTicketCount() {
            return state.get().activeTickets().size();
        }

        long stateVersion() {
            return state.get().version();
        }

        private record ParkTransition(LotState nextState, ParkingTicket ticket) {
        }

        private record ExitTransition(LotState nextState, Receipt receipt) {
        }
    }

    public static void main(String[] args) throws Exception {
        LockingMode mode = LockingMode.from(args.length == 0 ? "optimistic" : args[0]);
        ParkingLot parkingLot = new ParkingLot(
                List.of(ParkingSpot.available("A-1", SpotType.COMPACT, 1)),
                new NearestCompatibleSpotStrategy(),
                new HourlyPricingStrategy(CENTS_PER_HOUR),
                mode,
                ENTRY_PERMITS,
                Clock.systemUTC());

        ExecutorService executor = Executors.newFixedThreadPool(CALLER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        Queue<String> winningTicketIds = new ConcurrentLinkedQueue<>();
        List<Future<Boolean>> attempts = new ArrayList<>();

        for (int index = 0; index < CALLER_COUNT; index++) {
            int callerIndex = index;
            attempts.add(executor.submit(() -> {
                start.await();
                try {
                    ParkingTicket ticket = parkingLot.park(
                            new Vehicle("CAR-" + callerIndex, VehicleType.CAR));
                    winningTicketIds.add(ticket.id());
                    return true;
                } catch (ParkingFullException expected) {
                    return false;
                }
            }));
        }

        start.countDown();
        int successCount = 0;
        for (Future<Boolean> attempt : attempts) {
            if (attempt.get(10, TimeUnit.SECONDS)) {
                successCount++;
            }
        }
        executor.shutdownNow();

        require(successCount == 1, "Exactly one concurrent caller must win");
        require(parkingLot.activeTicketCount() == 1, "Exactly one ticket must be active");
        require(parkingLot.availableSpotCount() == 0, "The single spot must be occupied");

        String winningTicketId = winningTicketIds.element();
        Receipt receipt = parkingLot.exit(winningTicketId);
        require(parkingLot.activeTicketCount() == 0, "Exit must close the active ticket");
        require(parkingLot.availableSpotCount() == 1, "Exit must release the spot");

        System.out.println("Java Parking Lot concurrency demo passed");
        System.out.println("Mode: " + mode);
        System.out.println("Concurrent callers: " + CALLER_COUNT);
        System.out.println("Successful allocations: " + successCount);
        System.out.println("Receipt fee: " + receipt.feeInCents() + " cents");
        System.out.println("Final state version: " + parkingLot.stateVersion());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
