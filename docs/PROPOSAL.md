# Original Proposal — Particle Collision Anomaly Detector

*This is the original OOP course proposal. The shipped implementation went
beyond it in several ways — see the "Known limitations / next steps"
section of the main [README](../README.md) for an honest comparison.*

## 1. Problem Statement

In large-scale physics experiments, most famously conducted at CERN's Large
Hadron Collider (LHC), billions of particle collision events are recorded
per second. The overwhelming majority are ordinary, predictable events that
obey well-known physical laws. Within these continuous streams of data are
rare, scientifically significant anomalous events whose energy signatures
deviate from the predictions of established physical models. Such instances
may indicate new particles, measurement faults, or previously unknown
physical phenomena, and are therefore significant for advancing scientific
research.

The core problem: how can software automatically distinguish normal from
anomalous collisions in a high-volume, continuous stream of events, without
manual human inspection of every event?

**The science behind it:** when two particles collide, conservation laws
dictate the expected outcome — conservation of momentum (p = mv) and, in a
perfectly elastic collision, conservation of kinetic energy (KE = ½mv²). In
inelastic collisions, total energy is conserved but redistributed into heat,
radiation, or particle creation. A collision is classified as anomalous when
its measured post-collision energy output deviates from the predicted value
by a large margin, under current models.

*(Note: the proposal used simplified Newtonian formulas for tractability;
the implementation uses full relativistic kinematics instead.)*

## 2. Proposed Solution

A Java-based, object-oriented system that:

1. Simulates particle motion and collisions in a 2D environment.
2. Applies physics equations for momentum and energy conservation at every
   collision.
3. Uses z-score statistical analysis to detect anomalous energy outputs.
4. Optionally includes a live graphical interface (JavaFX/Swing)
   visualizing the simulation and highlighting flagged anomalies.

**How it works:** particles move according to velocity vectors; on overlap,
a collision is resolved via momentum/energy equations. The resulting
difference is logged as a `CollisionEvent` and passed to an
`AnomalyDetector`, which computes a z-score against a rolling mean/standard
deviation:

```
z = (x − μ) / σ
```

where x is the observed deviation from expected energy, μ is the rolling
mean, and σ is the rolling standard deviation. If |z| exceeds a threshold
(default 2.5), the event is flagged as anomalous.

### Tentative OOP model

| Class / Interface | Type | Responsibility |
|---|---|---|
| `Particle` | Concrete class | Position, velocity, mass, charge, radius |
| `Vector2D` | Utility class | 2D vector operations |
| `PhysicsLaw` | Interface | `applyLaw(Particle a, Particle b)` for polymorphism |
| `MomentumConservation` | Implements `PhysicsLaw` | Post-collision velocity vectors |
| `EnergyConservation` | Implements `PhysicsLaw` | Expected post-collision KE, validates consistency |
| `CollisionEngine` | Concrete class | Detects overlaps, resolves collisions, generates events |
| `CollisionEvent` | Immutable data class | Timestamp, particle IDs, pre/post KE, anomaly flag |
| `AnomalyDetector` | Concrete class | Rolling mean/σ, z-score, flags anomalies |
| `SimulationEngine` | Concrete class | Runs the loop, coordinates engine + detector + logging/GUI |
| `SimulationRenderer` | Concrete class (optional) | JavaFX/Swing live visualization |
| `SimulationConfig` | Config class | User-tunable parameters |
| `EventLogger` | Utility class | Records `CollisionEvent`s to memory/CSV |

## 3. Impact

Automated anomaly detection is essential at the scale of the LHC, where the
volume of collision data makes manual inspection impossible — what an
algorithm doesn't flag is effectively invisible to science. Systems like
this one are analogous to the triggers that helped surface candidate events
in the 2012 discovery of the Higgs boson, and the same statistical
principles (streaming data, rolling statistics, z-score thresholding)
transfer directly to fields like financial fraud detection, industrial
sensor monitoring, network intrusion detection, and medical diagnostics.

## 4. Resources Required

- **Software:** VS Code + Java Extension Pack, JavaFX/Swing (optional GUI),
  JUnit 5 for physics/detection unit tests.
- **Hardware:** personal laptop, any modern OS.
- **Knowledge resources:** Serway & Jewett, *Physics for Scientists and
  Engineers*; Y. Daniel Liang, *Introduction to Java Programming* (10th
  ed.); Robert A. Donnelly, *Business Statistics* (3rd ed.); Oracle Java SE
  17 documentation; the [CERN Open Data
  Portal](https://opendata.cern.ch).
