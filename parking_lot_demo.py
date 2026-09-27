#!/usr/bin/env python3
"""Single-file, thread-safe Parking Lot LLD reference implementation.

Run with:
    python3 parking_lot_demo.py optimistic
    python3 parking_lot_demo.py pessimistic

Concepts demonstrated:
- Strategy and Dependency Inversion
- immutable aggregate snapshots
- bounded-semaphore admission control
- a lock-backed atomic counter (Python has no portable stdlib AtomicLong)
- optimistic version validation with a short compare-and-set lock
- pessimistic locking across the complete state transition
- a concurrent one-spot collision test
"""

from __future__ import annotations

import argparse
import math
import threading
from abc import ABC, abstractmethod
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from enum import Enum, auto
from typing import Final, Iterable

CALLER_COUNT: Final = 40
ENTRY_PERMITS: Final = 8
MAX_OPTIMISTIC_RETRIES: Final = 128
CENTS_PER_HOUR: Final = 500
SECONDS_PER_HOUR: Final = 3_600


class VehicleType(Enum):
    MOTORCYCLE = auto()
    CAR = auto()
    ELECTRIC_CAR = auto()
    TRUCK = auto()


class SpotType(Enum):
    MOTORCYCLE = auto()
    COMPACT = auto()
    LARGE = auto()
    ELECTRIC = auto()


class SpotStatus(Enum):
    AVAILABLE = auto()
    OCCUPIED = auto()
    OUT_OF_SERVICE = auto()


class LockingMode(Enum):
    OPTIMISTIC = "optimistic"
    PESSIMISTIC = "pessimistic"


@dataclass(frozen=True, slots=True)
class Vehicle:
    registration: str
    vehicle_type: VehicleType

    def __post_init__(self) -> None:
        if not self.registration.strip():
            raise ValueError("Vehicle registration is required")


@dataclass(frozen=True, slots=True)
class ParkingSpot:
    spot_id: str
    spot_type: SpotType
    distance_from_entry: int
    status: SpotStatus = SpotStatus.AVAILABLE
    current_ticket_id: str | None = None

    def __post_init__(self) -> None:
        if not self.spot_id.strip():
            raise ValueError("Spot ID is required")
        if self.distance_from_entry < 0:
            raise ValueError("Distance cannot be negative")
        has_owner = self.current_ticket_id is not None
        if (self.status is SpotStatus.OCCUPIED) != has_owner:
            raise ValueError(
                "Occupied spots require a ticket owner and other states forbid one"
            )

    @property
    def is_available(self) -> bool:
        return self.status is SpotStatus.AVAILABLE

    def can_fit(self, vehicle: Vehicle) -> bool:
        compatible_spots: dict[VehicleType, frozenset[SpotType]] = {
            VehicleType.MOTORCYCLE: frozenset(
                {SpotType.MOTORCYCLE, SpotType.COMPACT, SpotType.LARGE}
            ),
            VehicleType.CAR: frozenset({SpotType.COMPACT, SpotType.LARGE}),
            VehicleType.ELECTRIC_CAR: frozenset(
                {SpotType.ELECTRIC, SpotType.COMPACT, SpotType.LARGE}
            ),
            VehicleType.TRUCK: frozenset({SpotType.LARGE}),
        }
        return self.spot_type in compatible_spots[vehicle.vehicle_type]

    def occupy(self, ticket_id: str) -> ParkingSpot:
        if not self.is_available:
            raise RuntimeError(
                f"Spot {self.spot_id} is not available; refresh candidate state"
            )
        return replace(
            self,
            status=SpotStatus.OCCUPIED,
            current_ticket_id=ticket_id,
        )

    def release_owned_by(self, ticket_id: str) -> ParkingSpot:
        if (
            self.status is not SpotStatus.OCCUPIED
            or self.current_ticket_id != ticket_id
        ):
            raise RuntimeError(
                f"Spot {self.spot_id} is not owned by ticket {ticket_id}; "
                "check for a duplicate or stale exit request"
            )
        return replace(
            self,
            status=SpotStatus.AVAILABLE,
            current_ticket_id=None,
        )


@dataclass(frozen=True, slots=True)
class ParkingTicket:
    ticket_id: str
    vehicle: Vehicle
    spot_id: str
    entered_at: datetime


@dataclass(frozen=True, slots=True)
class Receipt:
    ticket_id: str
    spot_id: str
    exited_at: datetime
    fee_in_cents: int


@dataclass(frozen=True, slots=True)
class LotState:
    """Immutable aggregate state replaced atomically as one business transition."""

    spots: tuple[ParkingSpot, ...]
    active_tickets: tuple[ParkingTicket, ...]
    version: int = 0


class SpotAllocationStrategy(ABC):
    @abstractmethod
    def select(
        self,
        vehicle: Vehicle,
        spots: Iterable[ParkingSpot],
    ) -> ParkingSpot | None:
        """Return the best available compatible spot without mutating state."""


class NearestCompatibleSpotStrategy(SpotAllocationStrategy):
    def select(
        self,
        vehicle: Vehicle,
        spots: Iterable[ParkingSpot],
    ) -> ParkingSpot | None:
        candidates = (
            spot
            for spot in spots
            if spot.is_available and spot.can_fit(vehicle)
        )
        return min(
            candidates,
            key=lambda spot: (spot.distance_from_entry, spot.spot_id),
            default=None,
        )


class PricingStrategy(ABC):
    @abstractmethod
    def fee_in_cents(
        self,
        ticket: ParkingTicket,
        exited_at: datetime,
    ) -> int:
        """Calculate a non-negative fee for a ticket."""


class HourlyPricingStrategy(PricingStrategy):
    def __init__(self, cents_per_hour: int) -> None:
        if cents_per_hour < 0:
            raise ValueError("Hourly rate cannot be negative")
        self._cents_per_hour = cents_per_hour

    def fee_in_cents(
        self,
        ticket: ParkingTicket,
        exited_at: datetime,
    ) -> int:
        parked_seconds = max(
            0,
            math.floor((exited_at - ticket.entered_at).total_seconds()),
        )
        billable_hours = max(1, math.ceil(parked_seconds / SECONDS_PER_HOUR))
        return billable_hours * self._cents_per_hour


class ParkingFullError(RuntimeError):
    """Raised when no compatible spot can be selected."""


class ConcurrentAllocationError(RuntimeError):
    """Raised when bounded optimistic retries are exhausted."""


class AtomicCounter:
    """Portable lock-backed atomic counter for Python's standard library."""

    def __init__(self) -> None:
        self._value = 0
        self._lock = threading.Lock()

    def increment_and_get(self) -> int:
        with self._lock:
            self._value += 1
            return self._value


class ParkingLot:
    """Thread-safe aggregate supporting optimistic and pessimistic transitions.

    Optimistic mode snapshots under a short lock, computes outside the lock, and
    swaps only if the snapshot identity is still current. Pessimistic mode holds
    the same reentrant lock across selection and mutation. The immutable LotState
    contains spots and tickets so each swap preserves the complete invariant.
    """

    def __init__(
        self,
        spots: Iterable[ParkingSpot],
        allocation_strategy: SpotAllocationStrategy,
        pricing_strategy: PricingStrategy,
        locking_mode: LockingMode,
        entry_permits: int,
    ) -> None:
        spot_tuple = tuple(spots)
        if not spot_tuple:
            raise ValueError("At least one parking spot is required")
        if len({spot.spot_id for spot in spot_tuple}) != len(spot_tuple):
            raise ValueError("Parking spot IDs must be unique")
        if entry_permits <= 0:
            raise ValueError("Entry permit count must be positive")

        self._state = LotState(spot_tuple, ())
        self._allocation_strategy = allocation_strategy
        self._pricing_strategy = pricing_strategy
        self._locking_mode = locking_mode
        self._entry_semaphore = threading.BoundedSemaphore(entry_permits)
        self._ticket_sequence = AtomicCounter()
        self._state_lock = threading.RLock()

    def park(self, vehicle: Vehicle) -> ParkingTicket:
        self._entry_semaphore.acquire()
        try:
            ticket_id = f"T-{self._ticket_sequence.increment_and_get()}"
            entered_at = datetime.now(timezone.utc)
            if self._locking_mode is LockingMode.OPTIMISTIC:
                return self._park_optimistically(vehicle, ticket_id, entered_at)
            return self._park_pessimistically(vehicle, ticket_id, entered_at)
        finally:
            self._entry_semaphore.release()

    def exit(self, ticket_id: str) -> Receipt:
        if not ticket_id.strip():
            raise ValueError("Ticket ID is required")
        exited_at = datetime.now(timezone.utc)
        if self._locking_mode is LockingMode.OPTIMISTIC:
            return self._exit_optimistically(ticket_id, exited_at)
        return self._exit_pessimistically(ticket_id, exited_at)

    def _park_optimistically(
        self,
        vehicle: Vehicle,
        ticket_id: str,
        entered_at: datetime,
    ) -> ParkingTicket:
        for _ in range(MAX_OPTIMISTIC_RETRIES):
            current = self._snapshot()
            next_state, ticket = self._build_park_transition(
                current,
                vehicle,
                ticket_id,
                entered_at,
            )
            if self._compare_and_set(current, next_state):
                return ticket
        raise ConcurrentAllocationError(
            "Parking allocation exceeded the optimistic retry limit; "
            "retry the request with backoff"
        )

    def _park_pessimistically(
        self,
        vehicle: Vehicle,
        ticket_id: str,
        entered_at: datetime,
    ) -> ParkingTicket:
        with self._state_lock:
            next_state, ticket = self._build_park_transition(
                self._state,
                vehicle,
                ticket_id,
                entered_at,
            )
            self._state = next_state
            return ticket

    def _exit_optimistically(
        self,
        ticket_id: str,
        exited_at: datetime,
    ) -> Receipt:
        for _ in range(MAX_OPTIMISTIC_RETRIES):
            current = self._snapshot()
            next_state, receipt = self._build_exit_transition(
                current,
                ticket_id,
                exited_at,
            )
            if self._compare_and_set(current, next_state):
                return receipt
        raise ConcurrentAllocationError(
            "Parking exit exceeded the optimistic retry limit; "
            "retry the request with backoff"
        )

    def _exit_pessimistically(
        self,
        ticket_id: str,
        exited_at: datetime,
    ) -> Receipt:
        with self._state_lock:
            next_state, receipt = self._build_exit_transition(
                self._state,
                ticket_id,
                exited_at,
            )
            self._state = next_state
            return receipt

    def _snapshot(self) -> LotState:
        with self._state_lock:
            return self._state

    def _compare_and_set(self, expected: LotState, update: LotState) -> bool:
        # Python's stdlib has no portable lock-free object CAS. This short lock
        # provides CAS semantics while policy computation remains outside it.
        with self._state_lock:
            if self._state is not expected:
                return False
            self._state = update
            return True

    def _build_park_transition(
        self,
        current: LotState,
        vehicle: Vehicle,
        ticket_id: str,
        entered_at: datetime,
    ) -> tuple[LotState, ParkingTicket]:
        selected = self._allocation_strategy.select(vehicle, current.spots)
        if selected is None:
            raise ParkingFullError(
                f"No compatible spot is currently available for {vehicle.vehicle_type.name}"
            )
        ticket = ParkingTicket(ticket_id, vehicle, selected.spot_id, entered_at)
        next_spots = tuple(
            spot.occupy(ticket_id) if spot.spot_id == selected.spot_id else spot
            for spot in current.spots
        )
        next_state = LotState(
            next_spots,
            current.active_tickets + (ticket,),
            current.version + 1,
        )
        return next_state, ticket

    def _build_exit_transition(
        self,
        current: LotState,
        ticket_id: str,
        exited_at: datetime,
    ) -> tuple[LotState, Receipt]:
        ticket = next(
            (
                active_ticket
                for active_ticket in current.active_tickets
                if active_ticket.ticket_id == ticket_id
            ),
            None,
        )
        if ticket is None:
            raise RuntimeError(
                f"Active ticket {ticket_id} was not found; "
                "check for a duplicate exit request"
            )
        if not any(spot.spot_id == ticket.spot_id for spot in current.spots):
            raise RuntimeError(
                f"Ticket {ticket_id} refers to missing spot {ticket.spot_id}"
            )

        next_spots = tuple(
            spot.release_owned_by(ticket_id)
            if spot.spot_id == ticket.spot_id
            else spot
            for spot in current.spots
        )
        next_tickets = tuple(
            active_ticket
            for active_ticket in current.active_tickets
            if active_ticket.ticket_id != ticket_id
        )
        next_state = LotState(next_spots, next_tickets, current.version + 1)
        receipt = Receipt(
            ticket_id,
            ticket.spot_id,
            exited_at,
            self._pricing_strategy.fee_in_cents(ticket, exited_at),
        )
        return next_state, receipt

    @property
    def available_spot_count(self) -> int:
        return sum(spot.is_available for spot in self._snapshot().spots)

    @property
    def active_ticket_count(self) -> int:
        return len(self._snapshot().active_tickets)

    @property
    def state_version(self) -> int:
        return self._snapshot().version


def run_concurrent_demo(mode: LockingMode) -> None:
    parking_lot = ParkingLot(
        spots=(ParkingSpot("A-1", SpotType.COMPACT, 1),),
        allocation_strategy=NearestCompatibleSpotStrategy(),
        pricing_strategy=HourlyPricingStrategy(CENTS_PER_HOUR),
        locking_mode=mode,
        entry_permits=ENTRY_PERMITS,
    )
    start_gate = threading.Barrier(CALLER_COUNT + 1)

    def attempt_park(index: int) -> ParkingTicket | None:
        start_gate.wait()
        try:
            return parking_lot.park(Vehicle(f"CAR-{index}", VehicleType.CAR))
        except ParkingFullError:
            return None

    with ThreadPoolExecutor(max_workers=CALLER_COUNT) as executor:
        futures = [executor.submit(attempt_park, index) for index in range(CALLER_COUNT)]
        start_gate.wait()
        winners = [ticket for future in futures if (ticket := future.result()) is not None]

    assert len(winners) == 1, "Exactly one concurrent caller must win"
    assert parking_lot.active_ticket_count == 1, "Exactly one ticket must be active"
    assert parking_lot.available_spot_count == 0, "The single spot must be occupied"

    receipt = parking_lot.exit(winners[0].ticket_id)
    assert parking_lot.active_ticket_count == 0, "Exit must close the active ticket"
    assert parking_lot.available_spot_count == 1, "Exit must release the spot"

    print("Python Parking Lot concurrency demo passed")
    print(f"Mode: {mode.name}")
    print(f"Concurrent callers: {CALLER_COUNT}")
    print(f"Successful allocations: {len(winners)}")
    print(f"Receipt fee: {receipt.fee_in_cents} cents")
    print(f"Final state version: {parking_lot.state_version}")


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "mode",
        nargs="?",
        choices=tuple(mode.value for mode in LockingMode),
        default=LockingMode.OPTIMISTIC.value,
        help="Concurrency control mode (default: optimistic)",
    )
    return parser.parse_args()


if __name__ == "__main__":
    arguments = parse_arguments()
    run_concurrent_demo(LockingMode(arguments.mode))
