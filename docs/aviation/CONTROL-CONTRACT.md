# Flight controls: Java 17 implementation contract

Revision 4; binding technical contract, paired with the player-facing
[FLIGHT-CONTROLS-RU.md](FLIGHT-CONTROLS-RU.md). Revision 4 clarifies mouse-option
bounds and ACK wording; it does not change wire format or flight-model semantics.
This document fixes implementation boundaries and API names. It does not claim
that the native adapters or acceptance scenarios have passed. Existing native
identities, movement, force calculation, collision, damage, energy and NBT remain.

## Ownership and dependency direction

Sources are under `modules/aviation/src/main/java/dev/frontier/aviation/`.

| Package / files | Owner | Responsibility |
| --- | --- | --- |
| `control/*` except `FlightControlModel.java` | B | Pure Java 17 value types below; no Minecraft, Forge or client imports |
| `control/FlightControlModel.java` and pure model tests | C | Deterministic control model; no service, entity, key or network access |
| `control/nativebridge/*`, native interception mixins | D | Native sampling/writes, audited native hooks, persistence bridge |
| `control/server/*`, `control/net/*`, service/codec tests | E | One server service, lease lifecycle, scheduling, wire protocol |
| `control/client/*`, client input/render mixins, HUD | Root/client owner | Input, prediction, reconciliation and observer interpolation |
| `LightAviation.java`, build/config/registration files | Root | Integration and registration only |

`model -> value types`; `native implementation -> native interface + value
types`; `server service -> model + native interface`; `client -> model + native
interface + network`. Native mixins call the installed callback interface below,
never the server implementation. The service owns all server sessions; the
adapter owns no competing lease map or control model. A callback must not invoke
the native engine method recursively.

## Pure public API (freeze these names and component order)

Every declaration is a separate public file in `dev.frontier.aviation.control`
unless explicitly nested. Records are immutable. All supplied numbers are finite;
constructors reject non-finite values. Wire validation rejects malformed values
before construction. Angles and angular rates use **degrees and degrees/tick**,
with the same sign and axis convention as native `XRot`, `YRot`, `ZRot`.

```java
public enum FlightKind { AIRCRAFT, HELICOPTER }
public enum EnginePhase { OFF, STARTING, RUNNING, STOPPING }
public enum ModeRequest { KEEP, ENABLE, DISABLE }
public enum ControlStatus {
    OK, UNSUPPORTED, NOT_PILOT, BUSY, INVALID_LEASE, STALE_SEQUENCE,
    INVALID_INPUT, TIMEOUT, NO_ENERGY, DAMAGED, UNSUPPORTED_MODE, RELEASED
}
public enum ReleaseReason {
    REQUESTED, DISMOUNT, SEAT_CHANGE, DEATH, DIMENSION_CHANGE,
    DISCONNECT, VEHICLE_REMOVED, SERVER_STOP
}

public record FlightProfile(
    String id, FlightKind kind, int responseTicks, int dampingTicks,
    int startupTicks
) {}

public final class FlightProfiles {
    private FlightProfiles() {}
    public static java.util.Optional<FlightProfile> find(String entityTypeId);
}

public record PilotCommand(
    long sequence, float pitchAxis, float yawAxis, float rollAxis,
    float throttleAxis, boolean brake, boolean secondaryBrake, boolean boost,
    ModeRequest ignition, ModeRequest gear, ModeRequest hover
) {
    public static PilotCommand neutral(long sequence);
    public PilotCommand withoutTransientInput();
}

public record FlightState(
    float pitch, float yaw, float roll,
    float pitchRate, float yawRate, float rollRate,
    float targetThrottle, float effectivePower,
    EnginePhase enginePhase, int phaseTicks, float spool,
    boolean gearDown, boolean hover, int throttleHoldTicks
) {}

public record FlightEnvironment(
    long tick, float speed, boolean onGround,
    boolean engineHealthy, boolean energyAvailable,
    boolean gearAvailable, boolean hoverAvailable, boolean nativeEmergency,
    AxisCaps caps,
    float idlePower, float maximumPower, float boostMaximumPower,
    float throttleIncrease, float throttleDecrease, float secondaryDecrease,
    int throttleRampUpTicks, int throttleRampDownTicks,
    float airborneMinimumPower, float secondaryMinimumPower,
    float airBrakePowerCap, float rotorSpool, float boostThrottleIncrease
) {
    public record AxisCaps(
        float pitchNegative, float pitchPositive,
        float yawNegative, float yawPositive,
        float rollNegative, float rollPositive
    ) {}
}

public record FlightOutput(
    FlightState state, float nativePower, boolean propulsionEnabled,
    boolean brake, boolean secondaryBrake, boolean boost, ControlStatus status
) {}

public record FlightTelemetry(
    FlightState state, float speed, float maximumPower,
    boolean brake, boolean secondaryBrake, boolean boost,
    ControlStatus status, long serverTick, long acknowledgedSequence
) {}

public record ControlLease(
    java.util.UUID vehicleId, java.util.UUID controllerId, long epoch
) {}

public record FlightSnapshot(
    String dimensionId, int entityId, java.util.UUID vehicleId,
    java.util.UUID controllerId, long epoch, FlightTelemetry telemetry
) {}

public final class FlightControlModel {
    private FlightControlModel() {}
    public static FlightOutput step(
        FlightProfile profile, FlightState previous,
        PilotCommand command, FlightEnvironment environment);
    public static FlightState initial(
        float pitch, float yaw, float roll, float targetThrottle,
        EnginePhase phase, boolean gearDown, boolean hover);
    public static float wrapDegrees(float angle);
}
```

`FlightSnapshot.controllerId` alone may be null (no active lease); all other
reference components are non-null. Epoch is positive and is never reused within
one server instance; sequence is nonnegative and strictly increases within its
lease. Telemetry acknowledgment is `-1` until the first applied command.

The player-facing defaults are not a promise of an Aviation-specific sensitivity
setting: mouse sensitivity and vanilla Y inversion come from Minecraft options,
while the additional aircraft inversion flag is read from Superb Warfare's
`ControlConfig.INVERT_AIRCRAFT_CONTROL`. Do not describe either as a new Aviation
config option unless one is registered in this mod. The captured cursor delta is
consumed once per local simulation step and clamped to `[-1,1]`; there is no
separate Aviation sensitivity multiplier or second smoothing filter. The pure
`FlightMouseInput` helper clamps finite sensitivity to `[0,1]` and substitutes
`0.5` for non-finite input; the game option itself is expected to remain in range.

`PilotCommand.neutral(sequence)` has zero axes, false held buttons and KEEP
requests. `withoutTransientInput()` also changes all mode requests to KEEP and
does not change sequence. It does not erase persistent modes from `FlightState`.

`targetThrottle` is normalized `[0,1]`; `effectivePower` and `nativePower` use
native power units (aircraft maximum normally 1, helicopter maximum .12).
`effectivePower` means the native power command allowed to produce propulsion;
it is zero in OFF/STARTING/STOPPING. `nativePower` may be idle power while
STARTING and is distinct because native rotor animation, sounds and energy read
power. Telemetry includes the normal maximum power for meaningful HUD scaling.
Spool is `[0,1]`; it represents startup progress in STARTING and sampled native
rotor residual in STOPPING. It is not an additional rotor simulator.

`AxisCaps` are nonnegative magnitudes, already incorporating the current native
speed/ground/damage authority. Zero means no authority. Native rotor/spin or
damage perturbations are not smuggled into these six caps. The native emergency
branch continues to own its forced attitude changes.

## Profiles (exact supported registry IDs)

| Profile id | Registry IDs | Response / damping / start ticks |
| --- | --- | --- |
| `fighter` | `frontier_aviation:f16`, `frontier_aviation:j10`, `superbwarfare:j_16`, `superbwarfare:kv_16` | 4 / 2 / 40 |
| `propeller` | `superbwarfare:ju_87`, `superbwarfare:tom_6` | 5 / 3 / 40 |
| `attack` | `frontier_aviation:su25`, `deepstrike:su24m`, `superbwarfare:a_10a` | 6 / 3 / 40 |
| `ac130` | `superbwarfare:ac_130h` | 8 / 4 / 60 |
| `ah6` | `superbwarfare:ah_6` | 5 / 3 / 40 |
| `mi28` | `superbwarfare:mi_28` | 7 / 4 / 60 |

Only `ah6` and `mi28` have HELICOPTER kind. `find` returns empty for every other
ID, including UAVs and ground vehicles. Registry identity, not Java class alone,
is the scope gate: Frontier aircraft share the native Su24 class.

## Native interface and callback signatures

The following files belong to D in `control.nativebridge`. These types may use
Minecraft common classes, but must never import client-only classes.

```java
public interface NativeFlightAccess {
    boolean supports(net.minecraft.world.entity.Entity vehicle);
    FlightProfile profile(net.minecraft.world.entity.Entity vehicle);
    net.minecraft.world.entity.LivingEntity pilot(net.minecraft.world.entity.Entity vehicle);
    FlightState initialState(net.minecraft.world.entity.Entity vehicle);
    FlightEnvironment sample(net.minecraft.world.entity.Entity vehicle, long tick);
    void apply(net.minecraft.world.entity.Entity vehicle, FlightOutput output);
    FlightState captureAttitude(net.minecraft.world.entity.Entity vehicle, FlightState control);
    void savePersistent(net.minecraft.world.entity.Entity vehicle, FlightState state);
}

public final class FlightNativeHooks {
    public interface Callbacks {
        boolean manages(net.minecraft.world.entity.Entity vehicle);
        void beforePhysics(net.minecraft.world.entity.Entity vehicle);
        void afterPhysics(net.minecraft.world.entity.Entity vehicle);
    }
    public static void install(Callbacks callbacks);
    public static boolean manages(net.minecraft.world.entity.Entity vehicle);
    public static void beforePhysics(net.minecraft.world.entity.Entity vehicle);
    public static void afterPhysics(net.minecraft.world.entity.Entity vehicle);
}

public final class NativeFlightAdapter implements NativeFlightAccess {
    public static final NativeFlightAdapter INSTANCE;
    // implements exactly the NativeFlightAccess methods above
}
```

`pilot` returns null when seat0 is empty or not a LivingEntity. It inspects SBW
ordered seat0 / `getFirstPassenger`, never vanilla `getControllingPassenger`.
Native hooks call `beforePhysics` once at the entry to the selected EngineUtils
Aircraft/Helicopter/Tom6 routine, before any conflicting writes or force reads.
`afterPhysics` runs after that same routine. Callback dispatch chooses logical
server or installed client handler by `vehicle.level().isClientSide`.
Root installs a common-side dispatcher implementing Callbacks; server handler
is E, client handler is supplied through a client-safe registration boundary.
Before installation `manages` returns false and other callbacks are no-ops.

`apply` writes current attitude and this tick's native control outputs before
native forces. It caches only the output needed by narrowly scoped interception
sites for that entity/tick; it never calls physics or steps the model. Its
transient cache is cleared on removal/world unload. `captureAttitude` replaces
pitch/yaw/roll and the observed effectivePower/native gear/hover in the supplied
immutable control state after permitted native changes; it preserves target,
engine phase/progress and angular rates. EffectivePower is observed native power
only while RUNNING, otherwise zero. This is the single post-native readback.
Normal controlled-airborne native writers are suppressed at their write sites.

## Server service signatures

The server implementation belongs to E in `control.server`. All entry points
run on the server thread; packet handlers enqueue work. The service accepts any
LivingEntity (including a seated NPC), without Player casts.

```java
public final class FlightControlService {
    public static FlightControlService get(net.minecraft.server.MinecraftServer server);
    public java.util.Optional<ControlLease> acquire(
        net.minecraft.world.entity.LivingEntity controller,
        net.minecraft.world.entity.Entity vehicle);
    public ControlStatus submit(
        net.minecraft.world.entity.LivingEntity controller,
        ControlLease lease, PilotCommand command);
    public java.util.Optional<FlightTelemetry> read(net.minecraft.world.entity.Entity vehicle);
    public void release(ControlLease lease, ReleaseReason reason);
    public boolean manages(net.minecraft.world.entity.Entity vehicle);
    public void beforePhysics(net.minecraft.world.entity.Entity vehicle);
    public void afterPhysics(net.minecraft.world.entity.Entity vehicle);
    public void releaseController(java.util.UUID controllerId, ReleaseReason reason);
    public void removeVehicle(java.util.UUID vehicleId, ReleaseReason reason);
    public void close();
}
```

Exactly one instance belongs to one MinecraftServer, released on server stop.
`acquire` validates supported registry ID, same level, live vehicle/controller,
actual seat0 identity, and absence of a competing valid lease. Same owner asking
again returns its existing lease; a handoff issues a new epoch. An unsuccessful
acquire does not steal authority. `submit` compares server-owned controller
identity, vehicle UUID, epoch and increasing sequence; range/finite validation
is done here as well as in the codec. These checks apply equally to NPCs.

`manages` selects supported manually controlled entities and retained managed
entities after dismount, including engine rundown. The native adapter's
`supports` remains a type predicate; the service owns authority selection.
Native authority is `VehicleEntity.getLoiterActive()` or the persistent-data UUID
marker `frontierIndustryVehicleDriver`. Existing loiter is not claimed merely by
a type match; explicit valid seat0 acquire cancels loiter before starting manual
control. The Industry marker rejects acquire until its controller releases it.
Su24 `order()` and `missionRoute()` are weapon missions and must NOT exclude the
piloted aircraft. DeepStrike ActiveFlights missile missions are outside these12
types. Industry currently rejects native SBW aircraft routes; the marker guard
also preserves that separation if an existing extension has placed one.

## Deterministic model semantics

One `step` is exactly one 20 Hz simulation tick; there is no wall-time delta in
the pure model. The native environment is a snapshot taken at the engine routine
HEAD. Constructors do not silently turn NaN into zero; the caller must reject
bad commands and retain the last valid state. The accepted command axes are in
`[-1,1]`. Target throttle is `[0,1]`; spool is `[0,1]`; phaseTicks is nonnegative;
throttleHoldTicks is signed and bounded to the environment's ramp maximum.

For each angular axis choose the positive or negative cap according to requested
sign (or current rate sign while damping). Desired rate is axis times cap. Move
the current **degrees/tick** rate toward the desired rate by `cap/responseTicks`
while input is held, or toward zero by `cap/dampingTicks` with zero input. On a
sign reversal first move toward zero, clamp at zero for that tick, then begin
the other direction on the next tick. Never cross through zero in one reversal
step. Clamp stored rate to the current directional cap before integration so
loss of native authority takes effect immediately. Integrate each angle once.
Yaw and roll wrap to `[-180,180)`; pitch uses native permitted attitude bounds
in the adapter. Native ground, explicit-hover and emergency correction runs at
its native point and is captured afterwards. The model performs no passive
airborne leveling. With nativeEmergency true commanded rates are zero; the
native emergency routine continues to rotate the vehicle.

The adapter computes zero-input caps from the pinned EngineInfo and the current
speed, velocity projections, attitude and rotor state. It never measures caps
by applying synthetic controls or running a second native tick. Native maximum
pitch/yaw magnitudes are aircraft `3.5*pitchSpeed` and
`(.3+3.2*abs(calculateY(roll)))*yawSpeed` airborne (`yawSpeed` on ground);
Tom6 `4*pitchSpeed` and `3*(abs(clamp(roll/60,-1.5,1.5))+1)*yawSpeed`.
Use native saturation ceilings with native speed authority normalized by the
maximum native slope, rather than one mouse-unit derivative as the full axis
cap. The exact aircraft pitch cap is
`3.5*pitchSpeed*clamp(forwardDot-.24,.1,.2)/.2`; yaw airborne is
`nativeYawLimit*yawSpeed*min(1,.24*speed)`, and ground yaw is
`yawSpeed*clamp(.6*speed,0,.75)/.75`. Tom6 pitch cap is
`4*pitchSpeed*min(max(speed-.1,.01),.9)/.9`; yaw is
`nativeYawLimit*yawSpeed*min((onGround?1.5:.9)*max(speed-.06,.1),.9)/.9`.
Pitch/roll pilot caps on the ground may be zero where native ground leveling
owns them. Aircraft roll cap is `6*rollSpeed`; Tom6 uses
`(16/3)*rollSpeed*max(0,1-abs(pitch/90))`, from the respective audited sustained
native deltaRot envelopes. The old accumulator is suppressed, not stacked onto
the new model rates. This cap mapping adds no speed/lift/linear-force rewrite.

For helicopters define `rotorAuthority=clamp(rotor/.12,0,1)`: airborne pitch cap
is `1.5*pitchSpeed*rotor`, roll cap is `70*rotor*rollSpeed` from the audited native
sustained bank envelope, and yaw cap is `10*yawSpeed*rotorAuthority` airborne,
`.1*yawSpeed*rotorAuthority` ground. Explicit hover applies native yaw multiplier
.5 and native roll multiplier.05. Native unconstrained mouse pitch/roll has no
finite saturation separate from the mouse sender's bounds: this finite API
envelope is therefore provisional pending real flight measurement, especially
pitch responsiveness. Literal `.25*rotor` is a per-mouse-unit derivative, not
the full roll range. All figures need real-flight calibration/acceptance; do not
claim native maximum/response preservation from compilation alone. Native
tail-damage spin is added separately at its original writer, before forces.

`ModeRequest` is an **idempotent requested state**, not a toggle count. Key edges
are translated by the client to ENABLE/DISABLE. The server queues accepted frames
in sequence order and consumes at most one per model step. When the three-frame
queue is full, the newest queued frame and incoming frame are approximated as one:
mouse axes are added and saturated to [-1, 1], held keys use the latest values,
and each mode retains its last non-KEEP request. ACK covers the included frames
only after that aggregate is stepped; this does not mean they were individually
simulated. Saturated movement and temporal detail can be lost under overload.
When the queue is empty,
mouse axes are zero and held keys remain, with all mode requests set to KEEP. Failed ignition
requests are consumed too: restored energy cannot silently retry a failed start.
Repeated ENABLE while STARTING does not restart the timer. Repeated ENABLE while
RUNNING and DISABLE while OFF are no-ops. Sequence replay cannot repeat a mode.

Ignition ENABLE from OFF/STOPPING checks energy and engine health, enters
STARTING with phaseTicks=0 and idle native power, and sets effectivePower=0.
Each subsequent model tick advances phaseTicks; after exactly startupTicks
elapsed ticks state becomes RUNNING. Startup completion still requires native
health and energy. OFF/STARTING/STOPPING always produce propulsionEnabled=false.
The first RUNNING tick begins restoring actual power from idle at the native
rise rate; it does not jump to the remembered throttle. DISABLE immediately
enters STOPPING and zeros propulsion and requested native power immediately.
Native rotor inertia continues to decay through its original native lerp/drag;
the model does not maintain a second shaft-speed accumulator. STOPPING becomes
OFF when rotorSpool is below .001 (jets/no rotor report zero); at least one
STOPPING tick is observable. Rotor sampling/animation remains active without a
lease until this decay completes.
Energy loss or engine failure stops propulsion immediately, records NO_ENERGY or
DAMAGED, and requires a new ENABLE request. Native health/emergency damage is
still applied once by native code, not again by the pure model.

`maximumPower` is normal native maximum; `boostMaximumPower` is the native
available boosted maximum for that EngineInfo, independent of old native input flags;
it equals maximumPower only when the engine has no boost capability. Target maps
to `idlePower + targetThrottle*(maximumPower-idlePower)`;
with boost held, use boostMaximumPower in that expression. The remembered target
does not change when boost is released. ThrottleIncrease/Decrease/secondaryDecrease
are **native power units per tick before hold ramp**. `boostThrottleIncrease` supplies
the rise rate with boost held; the current PilotCommand selects this rate for
both target selection and actual power restoration. Aircraft report base rise
`.006*increment` airborne and `1.6*base` on ground; boosted rise is always
`1.6*base`, so ground boost does not multiply twice. Tom6/helicopters report
identical normal and boosted rise. Sampling never reads sprintInputDown to infer
these capabilities. The source-compatible older environment constructor defaults
boosted rise to normal rise. W/S adjusts normalized
target by `nativeDelta/(maximumPower-idlePower)` with the signed hold counter;
hold direction change resets its count, release sets it to zero. Helicopters use
the native 10-tick upward and 5-tick downward ramp; aircraft/Tom6 use ramp=1.
W/S may select a target while OFF but never starts ignition. Startup native power
is idle regardless of the selected target. Selected zero means native idle;
negative/reverse ground throttle is outside this new zero-to-full throttle UI.

Native rates: aircraft rise `.006*increment` (native ground/boost factor1.6),
fall `.006*decrement`, max1/boost3; Tom6 rise `.02*increment`, fall
`.01*decrement`, max1/boost2.2; heli rise `.0007*increment`, fall
`.001*decrement`, max.12. Heli airborne downward floors remain
`.058/liftSpeed` for S and `.035/liftSpeed` for Shift; ground floor is zero.
`airborneMinimumPower`/`secondaryMinimumPower` hold these native floors, which
limit a downward command in the air, not loading a saved target or shutdown.

Actual power moves toward the requested native power with these same native
rates. Releasing a brake restores toward the remembered target at the native
rise rate. Ground brake and aircraft secondaryBrake cap effective power at zero;
aircraft airborne brake/secondaryBrake cap it at airBrakePowerCap and engage
native drag/flaps. Brake never writes targetThrottle. Heli Space brakes horizontal
motion only: its normal collective request remains intact, and it must not be
mapped to native down/Shift. Heli secondaryBrake is the native stronger downward
throttle command and uses secondaryDecrease/secondaryMinimumPower. Ctrl requests
only the native boost capability; unsupported native boost adds no artificial
multiplier. EffectivePower in telemetry is the power after permitted native
damage/ground/hover writers, not the model's unadjusted pre-native request.

Hover ENABLE is accepted only for an available helicopter hover mode. Deliberate
nonzero pitch or roll cancels hover before native force computation; yaw alone
does not. Gear ENABLE means gear down and DISABLE gear up; unsupported gear/hover
requests return UNSUPPORTED_MODE without changing that mode. Automatic native
ground safety behavior remains permitted and is reflected in the next snapshot.

## One tick through native code

1. Native baseTick retains its position/velocity sync and native travel call.
   At the selected engine routine HEAD the installed dispatcher selects server,
   local-pilot prediction or observer mode. The server validates lease ownership
   against current seat0 before consuming commands.
2. A per-entity simulation-tick guard guarantees one model step on each side per tick.
   It uses server gameTime on the server and the monotonic client tick counter
   on the client, so a world-time synchronization cannot skip an input frame.
   Read native environment once, advance the model once, cache output, then
   `NativeFlightAccess.apply` writes model attitude, requested native power and
   native control flags. A duplicate callback reuses output without advancing.
3. The original engine routine continues exactly once. Narrow redirects block
   conflicting input/leveling/start writers before any later native force read.
   Native damage/energy/brake/rotor/lift/drag work remains. Do not cancel travel,
   replace engineWork, call move again or restore angles only at TAIL.
4. Retain forced native angle contributions at their original write locations.
   In particular heli yaw's mixed input/tail-damage writer keeps the zero-user
   native tail-damage drift, before final lift reads the attitude. Emergency
   pitch/yaw/roll and allowed ground/explicit-hover writes remain. TAIL
   captureAttitude records the final permitted attitude and native effective
   power/modes for the service/prediction state.
5. Native baseTick retains its single final move/collision path. Suppress only
   aircraftLoiter for a new managed manual session. Su24's post-super tick
   commandedThrottle setter is skipped for that session so it cannot undo the
   power already consumed by force calculation; never reapply pre-damage power.
6. Service publishes a snapshot on the 2-tick cadence and immediately on a real
   mode/status/lease transition or new observer. Native engine parsing may skip
   work on its first tick; this is not repaired by executing an extra engine tick.

Output caches are scoped to vehicle UUID, logical side and gameTime. Scoped
writers use the current output only when the same-side callback has claimed the
vehicle. All unsupported entities and native automated missions execute their
original methods. EngineStart/EngineStartOver reflect STARTING true/false,
RUNNING true/true, OFF/STOPPING false/false at every native start-writer site.
Native W-start reads/branches must be neutralized as well as setters, otherwise
native startup sounds can repeat. An accepted I start plays the native start
sound once where that engine has such a sound. Native engineRunning audible
state includes startup/rundown; Tom6's own override must be covered separately.

Native propulsion gating is limited to the final force-producing getter:
aircraft power ordinal16, heli rotor ordinal9, Tom6 power ordinal9 in the pinned
engine routines. Gate to zero while propulsionEnabled=false; keep lift from
forward motion for planes and preserve every other rotor/power getter. Native
energy is not simulated a second time (including Tom6's two native consumption
sites). The exact selected ordinals/counts and indirect updateRotation call are
asserted against pinned bytecode by D's structural checks.

## Lease, timeout and lifecycle

The server issues a positive monotonic long epoch from its service-wide counter,
not from a per-vehicle counter that resets on unload. State is keyed by vehicle
UUID with level identity checked every time; recycled numeric entity IDs cannot
inherit authority. The player channel resolves its sender server-side; no C2S
controller UUID can impersonate the sender. NPCs obtain leases only through the
same acquire API after occupying seat0. A client never invents a lease epoch.

Submit updates axes/held buttons and receipt time only for an accepted increasing
sequence. Rejecting a stale or malformed packet does not refresh the timeout.
After 500 ms without accepted input (monotonic elapsed time; also 10 simulation
ticks for deterministic tests), clear all axes including throttleAxis, brake,
secondaryBrake and boost; keep selected throttle, ignition, gear and hover. This
timeout revokes the lease; the client must obtain a new epoch before submitting
more frames. Do not retransmit toggle actions as heartbeats.

Dismount, seat reassignment, death, dimension change, disconnect and entity
removal revoke the lease immediately on their server lifecycle event, with a
seat/death/level check again before each engine step. Revocation clears held
input, pending mode requests and angular rates; it retains selected gas and
ignition/gear/hover, allowing native flight and engine behavior to continue
without old-owner input. A new owner starts from the actual current attitude,
zero rates and a new epoch. Old packets fail even if the same UUID remounts.
Remove/unload saves compatible persistent state then removes all transient
references; server stop releases the service and callback state. Chunk reload
and restart never resurrect lease, sequence, mode edges or old angular velocity.

Native flight authority taking over ends management for the current epoch,
including retained sessions with no lease. Publish one terminal snapshot with
no controller and telemetry status BUSY before removing that session and its
native output cache. Do not precede it with a RELEASED snapshot. The client
processes terminal BUSY before same-tick duplicate filtering, clears tracked
control/prediction and remembers the retired epoch so delayed packets cannot
revive it. A newer manual epoch is accepted. Ordinary RELEASED preserves managed
engine continuation. Visible native loiter also disables client prediction and
automatic lease requests; explicit validated acquire can still select manual
control. World unload clears client epoch tombstones.

## Network and client callback boundary

E provides these integration methods in `control.net.FlightNetwork`; root owns
the registration call and client installs its receiver from client setup only:

```java
public static void register();
public static void installClientReceiver(java.util.function.Consumer<FlightSnapshot> receiver);
public static void requestLease(net.minecraft.world.entity.Entity vehicle);
public static void submit(ControlLease lease, PilotCommand command);
public static void release(ControlLease lease);
public static void sendSnapshot(net.minecraft.server.level.ServerPlayer receiver,
                                FlightSnapshot snapshot);
```

All receiver deliveries occur on the client game thread. The common network
class imports no Minecraft client class. Before client receiver installation,
incoming snapshots are ignored safely. Server replies come from its registered
service; a client snapshot is never accepted as authority.

Use a new dedicated SimpleChannel `frontier_aviation:flight_controls`, with
exact-version acceptance combining Aviation artifact version and protocol
revision3 (for this branch `1.1.0:flight3`). Do not renumber existing messages or
modify other mod channels. Own message IDs: 0 acquire C2S, 1 command C2S,
2 release C2S, 3 snapshot S2C. Acquire carries dimension ID, entity ID and UUID;
command/release carries vehicle UUID and epoch (controller is packet sender).
Snapshot carries every FlightSnapshot component including explicit roll/rates,
targetThrottle, effectivePower, phase/spool, server tick and command ack.

The standalone codec is `control.net.FlightWireCodec`, using DataInput/DataOutput
so tests need no Forge runtime. E fixes its exact method surface in its own files;
network buffers are adapted to the same field order, not an independent codec.
Strings have a maximum of256 UTF-8 bytes, with strict byte length and decoding;
packet byte budget2048. Integers/enums/booleans, finite floats, range limits,
positive epochs and trailing bytes are validated. Payload is fixed-size except
dimension ID; there is no Java serialization, NBT payload or unbounded list.
Rate limits are per sender and checked before queuing server-thread work: at most
64 C2S packets per one-second window and 32 outstanding tasks. An overload queues
at most one revocation of that sender's flight leases, without replying to every
rejected packet; other senders keep independent budgets. Dropped input cannot be
followed by an ACK in the old epoch while the revocation is pending.
Up to three accepted frames are queued; overload coalesces the newest frames.
Each actual local prediction step sends one
new sequence, including unchanged held input; input sampling alone does not send
or advance the sequence. Pending mode edges survive KEEP updates until that step.
Rejected floods do not advance ack or receipt time. Snapshot cadence is every
2 server ticks (10 Hz), plus bounded immediate state changes/new observers.

Client snapshots are checked against dimension, entity UUID and epoch, then
serverTick. A newer epoch resets pending input/reconciliation history even for
the same owner. Older epochs/ticks are ignored. A tracking start receives a full
snapshot immediately, including roll; it does not wait for a pilot input change.
The acknowledged sequence retires input history only inside that epoch. The
server keeps received/consumed/applied sequences separate: receipt rejects
duplicates, and only a successfully stepped command advances the snapshot ACK.
History retirement uses sequence alone; client and server world clocks are not
compared. Individual queued frames align with server steps until overload causes
coalescing; a server tick without a new frame still advances held controls, so
mismatched TPS and correction behaviour require separate measurement. Timeout
or exceeding the rate limit resets the epoch.

The client accumulates raw mouse deltas once per client tick, applies current
sensitivity and inversion once, then maps aircraft mouse to pitch/yaw and A/D
to roll, or heli mouse to pitch/roll and A/D to yaw. No native smoothing values
or second low-pass filter feed these axes. C/free look, focus loss, GUI opening,
seat loss and dimension change clear accumulators before resuming. UI controls
use remappable KeyMappings; native weapons/decoys/seat/exit handling remains.

Only the local authoritative-lease owner predicts FlightControlModel at20 Hz.
Observer clients interpolate received attitude with shortest wrapped angular
distance; they do not simulate somebody else's input. Local reconciliation
replays only the **pure control model** from the acknowledged server state using
saved input/environment/angle-limit frames; never replay native physics, energy
or move. Each replay step applies its sampled native pitch/roll bounds, preserving
rates as native capture does. Emergency frames use unrestricted model bounds;
native emergency writers retain authority. Native readback that changes a
corrected axis resets only that axis's correction instead of creating hidden
out-of-bounds state.
New authoritative attitude is blended through one bounded correction mechanism
before that frame's native force read. Disable the native .1 yaw/pitch lerp only
for a new managed client session; retain native position/velocity sync. Update
native previous roll/yaw/pitch to the nearest equivalent of the final current
angle after physics: current-wrap(current-previous). Native render getters use
linear interpolation, so keeping old179/new-179 would otherwise show a full spin.
The client never publishes energy, damage or actual position
to the new protocol. A pilot waiting for lease/snapshot sends no duplicate native
flight controls; passenger aiming and UAV input remain on their original paths.

Old movement suppression masks only left/right/forward/back/up/down/sprint for
the owned seat0 manual aircraft, preserving fire/decoy bits and subsequent seat
and exit code. Old mouse suppression targets only that pilot's vehicle branch,
not the UAV branch and not a passenger's weapon-aim path. These scope conditions
are required on both sending and server-handling paths.

## Persistence and migration

D owns the persistence bridge. Store one additive nested compound under Forge
entity persistent data: `frontier_aviation:flight_controls` with keys
`Version` (int1), `Ignition` (phase name string), `TargetThrottle` (float[0,1]).
Native Power, Su24Throttle, gear, inventory, energy and other native fields keep
their exact names and formats. Do not add root-level replacement vehicle tags.
Saving does not mark a native mission as manually controlled.

Load extensionVersion1 after native data exists. RUNNING is restored as a desired
running state only if current native health/energy permit it; STARTING and
STOPPING become OFF, and target throttle is retained. No extension means a
legacy save: prefer a finite nonnegative native Su24Throttle when available,
otherwise native Power; normalize from the native idle-to-normal-max range and
clamp[0,1]. Restore RUNNING from healthy native Power greater than a finite
epsilon with usable energy; otherwise OFF. Native start/start-over flags are
transient in legacy saves and must not be required for this migration. Positive
Su24Throttle alone restores only target selection, never ignition.
This initialization never changes the old tags or silently starts a saved-off
engine. Corrupt extension values fall back safely to native legacy state; an
unknown future Version is left intact and is not rewritten asVersion1.

Lease/epoch/sequence, command receipt time, key/mouse accumulation, model angular
rates, pending mode requests, prediction history and startup progress are never
persisted. On load `initial` uses native current attitude, zero rates, phaseTicks0,
throttleHoldTicks0, native gear/hover and the migrated selection. Native Su24's
late commandedThrottle writer is disconnected only while a manual session is
managed; automated native missions retain their original throttle controller.

## Evidence required at integration

Pure tests cover finite/ranges, wrap boundaries, each response/damping profile,
reversal zero crossing, unchanged tilt on release, independent vehicles, throttle
hold ramps, remembered throttle across brake/boost, startup exact duration,
failed-start consumption, shutdown/no thrust, hover cancel and native floors.
Service/codec tests cover NPC LivingEntity use through the common API, authority,
wrong seat/dimension, stale epoch/sequence,500 ms timeout, event revocation,
handoff/reload, tracking snapshot roll, malformed/oversized bytes and exact codec
round trips. Structural native checks prove all pinned anchors exist, including
Tom6 override, Su24 late writer, indirect rotation and separate UAV branch.

Those tests do not substitute for actual fresh-world flight/rotor/gear/weapon
behavior. Root's report must separately record all12 types, dedicated server and
two clients, RTT0/160/300 ms, NPC/Tom6, spectator/passenger, handoff/reconnect,
chunk unload/restart/legacy migration, route/UAV/ground exclusions and measured
1/8/16 vehicle load. Package A performs documentation review only, not a game
run or build; complete README checks and final diff check belong to integration.

Current handoff scope (user clarification, 2026-10-26): the agent runs code,
contract, bytecode and build checks only. Actual gameplay and the world-bound
multiplayer/NPC/performance scenarios above are owned by the user. The final
code report identifies exercised pure policies separately from reviewed world
bindings; it must not infer game acceptance from compilation or old reports.
