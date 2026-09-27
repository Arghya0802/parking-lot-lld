#include <algorithm>
#include <atomic>
#include <barrier>
#include <chrono>
#include <cstdint>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <semaphore>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

// Single-file C++20 Parking Lot LLD reference implementation.
//
// Build and run:
//   clang++ -std=c++20 -pthread parking_lot_demo.cpp -o parking_lot_demo
//   ./parking_lot_demo optimistic
//   ./parking_lot_demo pessimistic
//
// Demonstrates Strategy and Dependency Inversion, immutable snapshots,
// counting semaphore admission control, atomic IDs, optimistic compare-and-set,
// a pessimistic mutex, and a concurrent one-spot collision test.
namespace parking {

constexpr int kCallerCount = 40;
constexpr int kEntryPermits = 8;
constexpr int kMaximumOptimisticRetries = 128;
constexpr std::int64_t kCentsPerHour = 500;
constexpr std::int64_t kSecondsPerHour = 3'600;

using Clock = std::chrono::system_clock;
using TimePoint = Clock::time_point;

enum class VehicleType {
    Motorcycle,
    Car,
    ElectricCar,
    Truck
};

enum class SpotType {
    Motorcycle,
    Compact,
    Large,
    Electric
};

enum class SpotStatus {
    Available,
    Occupied,
    OutOfService
};

enum class LockingMode {
    Optimistic,
    Pessimistic
};

std::string toString(LockingMode mode) {
    return mode == LockingMode::Optimistic ? "OPTIMISTIC" : "PESSIMISTIC";
}

LockingMode parseMode(const std::string& value) {
    if (value == "optimistic") {
        return LockingMode::Optimistic;
    }
    if (value == "pessimistic") {
        return LockingMode::Pessimistic;
    }
    throw std::invalid_argument(
        "Unknown locking mode '" + value
        + "'; expected optimistic or pessimistic");
}

struct Vehicle {
    std::string registration;
    VehicleType type;
};

struct ParkingSpot {
    std::string id;
    SpotType type;
    int distanceFromEntry;
    SpotStatus status;
    std::optional<std::string> currentTicketId;

    static ParkingSpot available(
        std::string id,
        SpotType type,
        int distanceFromEntry) {
        if (id.empty()) {
            throw std::invalid_argument("Spot ID is required");
        }
        if (distanceFromEntry < 0) {
            throw std::invalid_argument("Distance cannot be negative");
        }
        return ParkingSpot{
            std::move(id), type, distanceFromEntry, SpotStatus::Available, std::nullopt};
    }

    [[nodiscard]] bool isAvailable() const {
        return status == SpotStatus::Available;
    }

    [[nodiscard]] bool canFit(const Vehicle& vehicle) const {
        switch (vehicle.type) {
            case VehicleType::Motorcycle:
                return type == SpotType::Motorcycle
                    || type == SpotType::Compact
                    || type == SpotType::Large;
            case VehicleType::Car:
                return type == SpotType::Compact || type == SpotType::Large;
            case VehicleType::ElectricCar:
                return type == SpotType::Electric
                    || type == SpotType::Compact
                    || type == SpotType::Large;
            case VehicleType::Truck:
                return type == SpotType::Large;
        }
        return false;
    }

    [[nodiscard]] ParkingSpot occupy(const std::string& ticketId) const {
        if (!isAvailable()) {
            throw std::logic_error(
                "Spot " + id + " is not available; refresh candidate state");
        }
        ParkingSpot occupied = *this;
        occupied.status = SpotStatus::Occupied;
        occupied.currentTicketId = ticketId;
        return occupied;
    }

    [[nodiscard]] ParkingSpot releaseOwnedBy(const std::string& ticketId) const {
        if (status != SpotStatus::Occupied
            || !currentTicketId.has_value()
            || currentTicketId.value() != ticketId) {
            throw std::logic_error(
                "Spot " + id + " is not owned by ticket " + ticketId
                + "; check for a duplicate or stale exit request");
        }
        return available(id, type, distanceFromEntry);
    }
};

struct ParkingTicket {
    std::string id;
    Vehicle vehicle;
    std::string spotId;
    TimePoint enteredAt;
};

struct Receipt {
    std::string ticketId;
    std::string spotId;
    TimePoint exitedAt;
    std::int64_t feeInCents;
};

// One immutable snapshot contains both spots and tickets, so a single atomic
// pointer replacement commits the complete business transition.
struct LotState {
    std::map<std::string, ParkingSpot> spots;
    std::map<std::string, ParkingTicket> activeTickets;
    std::uint64_t version{0};
};

class SpotAllocationStrategy {
public:
    virtual ~SpotAllocationStrategy() = default;

    [[nodiscard]] virtual std::optional<std::string> select(
        const Vehicle& vehicle,
        const std::map<std::string, ParkingSpot>& spots) const = 0;
};

class NearestCompatibleSpotStrategy final : public SpotAllocationStrategy {
public:
    [[nodiscard]] std::optional<std::string> select(
        const Vehicle& vehicle,
        const std::map<std::string, ParkingSpot>& spots) const override {
        const ParkingSpot* selected = nullptr;
        for (const auto& [id, spot] : spots) {
            if (!spot.isAvailable() || !spot.canFit(vehicle)) {
                continue;
            }
            if (selected == nullptr
                || spot.distanceFromEntry < selected->distanceFromEntry
                || (spot.distanceFromEntry == selected->distanceFromEntry
                    && id < selected->id)) {
                selected = &spot;
            }
        }
        return selected == nullptr
            ? std::nullopt
            : std::optional<std::string>(selected->id);
    }
};

class PricingStrategy {
public:
    virtual ~PricingStrategy() = default;

    [[nodiscard]] virtual std::int64_t feeInCents(
        const ParkingTicket& ticket,
        TimePoint exitedAt) const = 0;
};

class HourlyPricingStrategy final : public PricingStrategy {
public:
    explicit HourlyPricingStrategy(std::int64_t centsPerHour)
        : centsPerHour_(centsPerHour) {
        if (centsPerHour < 0) {
            throw std::invalid_argument("Hourly rate cannot be negative");
        }
    }

    [[nodiscard]] std::int64_t feeInCents(
        const ParkingTicket& ticket,
        TimePoint exitedAt) const override {
        const auto duration = std::chrono::duration_cast<std::chrono::seconds>(
            exitedAt - ticket.enteredAt).count();
        const std::int64_t parkedSeconds = std::max<std::int64_t>(0, duration);
        const std::int64_t billableHours = std::max<std::int64_t>(
            1,
            (parkedSeconds + kSecondsPerHour - 1) / kSecondsPerHour);
        return billableHours * centsPerHour_;
    }

private:
    std::int64_t centsPerHour_;
};

class ParkingFullError final : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

class ConcurrentAllocationError final : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

class ParkingLot final {
public:
    ParkingLot(
        std::vector<ParkingSpot> spots,
        std::shared_ptr<const SpotAllocationStrategy> allocationStrategy,
        std::shared_ptr<const PricingStrategy> pricingStrategy,
        LockingMode lockingMode)
        : allocationStrategy_(std::move(allocationStrategy)),
          pricingStrategy_(std::move(pricingStrategy)),
          lockingMode_(lockingMode),
          entryPermits_(kEntryPermits) {
        if (!allocationStrategy_ || !pricingStrategy_) {
            throw std::invalid_argument("Strategies are required");
        }
        auto initial = std::make_shared<LotState>();
        for (auto& spot : spots) {
            const auto [iterator, inserted] = initial->spots.emplace(spot.id, std::move(spot));
            if (!inserted) {
                throw std::invalid_argument("Duplicate spot ID: " + iterator->first);
            }
        }
        if (initial->spots.empty()) {
            throw std::invalid_argument("At least one parking spot is required");
        }
        std::atomic_store(&state_, std::shared_ptr<const LotState>(std::move(initial)));
    }

    ParkingTicket park(const Vehicle& vehicle) {
        if (vehicle.registration.empty()) {
            throw std::invalid_argument("Vehicle registration is required");
        }
        PermitGuard permit(entryPermits_);
        const std::string ticketId = "T-" + std::to_string(
            ticketSequence_.fetch_add(1, std::memory_order_relaxed) + 1);
        const TimePoint enteredAt = Clock::now();
        return lockingMode_ == LockingMode::Optimistic
            ? parkOptimistically(vehicle, ticketId, enteredAt)
            : parkPessimistically(vehicle, ticketId, enteredAt);
    }

    Receipt exit(const std::string& ticketId) {
        if (ticketId.empty()) {
            throw std::invalid_argument("Ticket ID is required");
        }
        const TimePoint exitedAt = Clock::now();
        return lockingMode_ == LockingMode::Optimistic
            ? exitOptimistically(ticketId, exitedAt)
            : exitPessimistically(ticketId, exitedAt);
    }

    [[nodiscard]] std::size_t availableSpotCount() const {
        const auto current = std::atomic_load(&state_);
        return static_cast<std::size_t>(std::count_if(
            current->spots.begin(),
            current->spots.end(),
            [](const auto& entry) { return entry.second.isAvailable(); }));
    }

    [[nodiscard]] std::size_t activeTicketCount() const {
        return std::atomic_load(&state_)->activeTickets.size();
    }

    [[nodiscard]] std::uint64_t stateVersion() const {
        return std::atomic_load(&state_)->version;
    }

private:
    struct ParkTransition {
        std::shared_ptr<const LotState> nextState;
        ParkingTicket ticket;
    };

    struct ExitTransition {
        std::shared_ptr<const LotState> nextState;
        Receipt receipt;
    };

    class PermitGuard final {
    public:
        explicit PermitGuard(std::counting_semaphore<64>& semaphore)
            : semaphore_(semaphore) {
            semaphore_.acquire();
        }

        ~PermitGuard() {
            semaphore_.release();
        }

        PermitGuard(const PermitGuard&) = delete;
        PermitGuard& operator=(const PermitGuard&) = delete;

    private:
        std::counting_semaphore<64>& semaphore_;
    };

    ParkingTicket parkOptimistically(
        const Vehicle& vehicle,
        const std::string& ticketId,
        TimePoint enteredAt) {
        for (int attempt = 1; attempt <= kMaximumOptimisticRetries; ++attempt) {
            auto current = std::atomic_load_explicit(&state_, std::memory_order_acquire);
            ParkTransition transition = buildParkTransition(
                *current, vehicle, ticketId, enteredAt);
            if (std::atomic_compare_exchange_weak_explicit(
                    &state_,
                    &current,
                    transition.nextState,
                    std::memory_order_acq_rel,
                    std::memory_order_acquire)) {
                return transition.ticket;
            }
            std::this_thread::yield();
        }
        throw ConcurrentAllocationError(
            "Parking allocation exceeded the optimistic retry limit; retry with backoff");
    }

    ParkingTicket parkPessimistically(
        const Vehicle& vehicle,
        const std::string& ticketId,
        TimePoint enteredAt) {
        const std::lock_guard<std::mutex> guard(pessimisticStateMutex_);
        ParkTransition transition = buildParkTransition(
            *std::atomic_load(&state_), vehicle, ticketId, enteredAt);
        std::atomic_store(&state_, transition.nextState);
        return transition.ticket;
    }

    Receipt exitOptimistically(const std::string& ticketId, TimePoint exitedAt) {
        for (int attempt = 1; attempt <= kMaximumOptimisticRetries; ++attempt) {
            auto current = std::atomic_load_explicit(&state_, std::memory_order_acquire);
            ExitTransition transition = buildExitTransition(*current, ticketId, exitedAt);
            if (std::atomic_compare_exchange_weak_explicit(
                    &state_,
                    &current,
                    transition.nextState,
                    std::memory_order_acq_rel,
                    std::memory_order_acquire)) {
                return transition.receipt;
            }
            std::this_thread::yield();
        }
        throw ConcurrentAllocationError(
            "Parking exit exceeded the optimistic retry limit; retry with backoff");
    }

    Receipt exitPessimistically(const std::string& ticketId, TimePoint exitedAt) {
        const std::lock_guard<std::mutex> guard(pessimisticStateMutex_);
        ExitTransition transition = buildExitTransition(
            *std::atomic_load(&state_), ticketId, exitedAt);
        std::atomic_store(&state_, transition.nextState);
        return transition.receipt;
    }

    [[nodiscard]] ParkTransition buildParkTransition(
        const LotState& current,
        const Vehicle& vehicle,
        const std::string& ticketId,
        TimePoint enteredAt) const {
        const auto selectedId = allocationStrategy_->select(vehicle, current.spots);
        if (!selectedId.has_value()) {
            throw ParkingFullError(
                "No compatible spot is currently available for the requested vehicle");
        }

        auto next = std::make_shared<LotState>(current);
        ParkingSpot selected = next->spots.at(selectedId.value());
        selected = selected.occupy(ticketId);
        next->spots.insert_or_assign(selected.id, selected);
        ParkingTicket ticket{ticketId, vehicle, selected.id, enteredAt};
        next->activeTickets.insert_or_assign(ticketId, ticket);
        next->version = current.version + 1;
        return ParkTransition{std::shared_ptr<const LotState>(next), ticket};
    }

    [[nodiscard]] ExitTransition buildExitTransition(
        const LotState& current,
        const std::string& ticketId,
        TimePoint exitedAt) const {
        const auto ticketIterator = current.activeTickets.find(ticketId);
        if (ticketIterator == current.activeTickets.end()) {
            throw std::logic_error(
                "Active ticket " + ticketId
                + " was not found; check for a duplicate exit request");
        }
        const ParkingTicket ticket = ticketIterator->second;
        const auto spotIterator = current.spots.find(ticket.spotId);
        if (spotIterator == current.spots.end()) {
            throw std::logic_error(
                "Ticket " + ticketId + " refers to missing spot " + ticket.spotId);
        }

        auto next = std::make_shared<LotState>(current);
        const ParkingSpot released = spotIterator->second.releaseOwnedBy(ticketId);
        next->spots.insert_or_assign(released.id, released);
        next->activeTickets.erase(ticketId);
        next->version = current.version + 1;
        Receipt receipt{
            ticketId,
            released.id,
            exitedAt,
            pricingStrategy_->feeInCents(ticket, exitedAt)};
        return ExitTransition{std::shared_ptr<const LotState>(next), receipt};
    }

    std::shared_ptr<const LotState> state_;
    std::shared_ptr<const SpotAllocationStrategy> allocationStrategy_;
    std::shared_ptr<const PricingStrategy> pricingStrategy_;
    LockingMode lockingMode_;
    std::counting_semaphore<64> entryPermits_;
    std::atomic<std::uint64_t> ticketSequence_{0};
    std::mutex pessimisticStateMutex_;
};

void require(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error("Assertion failed: " + message);
    }
}

}  // namespace parking

int main(int argc, char* argv[]) {
    using namespace parking;
    try {
        const LockingMode mode = parseMode(argc > 1 ? argv[1] : "optimistic");
        ParkingLot parkingLot(
            {ParkingSpot::available("A-1", SpotType::Compact, 1)},
            std::make_shared<NearestCompatibleSpotStrategy>(),
            std::make_shared<HourlyPricingStrategy>(kCentsPerHour),
            mode);

        std::barrier startGate(kCallerCount + 1);
        std::atomic<int> successCount{0};
        std::mutex winningTicketMutex;
        std::vector<std::string> winningTicketIds;
        std::vector<std::thread> callers;
        callers.reserve(kCallerCount);

        for (int index = 0; index < kCallerCount; ++index) {
            callers.emplace_back([&, index] {
                startGate.arrive_and_wait();
                try {
                    const ParkingTicket ticket = parkingLot.park(
                        Vehicle{"CAR-" + std::to_string(index), VehicleType::Car});
                    successCount.fetch_add(1, std::memory_order_relaxed);
                    const std::lock_guard<std::mutex> guard(winningTicketMutex);
                    winningTicketIds.push_back(ticket.id);
                } catch (const ParkingFullError&) {
                    // Expected for every caller except the one atomic winner.
                }
            });
        }

        startGate.arrive_and_wait();
        for (auto& caller : callers) {
            caller.join();
        }

        require(successCount.load() == 1, "exactly one concurrent caller must win");
        require(parkingLot.activeTicketCount() == 1, "exactly one ticket must be active");
        require(parkingLot.availableSpotCount() == 0, "the single spot must be occupied");
        require(winningTicketIds.size() == 1, "the winner must have one ticket ID");

        const Receipt receipt = parkingLot.exit(winningTicketIds.front());
        require(parkingLot.activeTicketCount() == 0, "exit must close the active ticket");
        require(parkingLot.availableSpotCount() == 1, "exit must release the spot");

        std::cout << "C++ Parking Lot concurrency demo passed\n"
                  << "Mode: " << toString(mode) << '\n'
                  << "Concurrent callers: " << kCallerCount << '\n'
                  << "Successful allocations: " << successCount.load() << '\n'
                  << "Receipt fee: " << receipt.feeInCents << " cents\n"
                  << "Final state version: " << parkingLot.stateVersion() << '\n';
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Demo failed: " << error.what() << '\n';
        return 1;
    }
}
