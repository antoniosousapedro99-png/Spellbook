"""VEIL execution policy and operational-intensity presets.

Intensity controls how much network noise Cybernomicon is permitted to make
while running authorized tests. It is a dial for *our* traffic shaping — it has
nothing to do with hiding activity from anyone's monitoring. "quiet" is the
default: fewer concurrent probes and a deliberate inter-operation delay, for
low-noise authorized testing.
"""
from __future__ import annotations

from dataclasses import dataclass

# Presets: concurrency cap, delay between gated operations (seconds), and a
# ceiling on how many operations one run may perform.
INTENSITIES: dict[str, dict] = {
    "quiet":  {"max_concurrency": 8,   "inter_op_delay": 0.10, "max_ops_per_run": 2048},
    "normal": {"max_concurrency": 64,  "inter_op_delay": 0.0,  "max_ops_per_run": 8192},
    "active": {"max_concurrency": 256, "inter_op_delay": 0.0,  "max_ops_per_run": 20000},
}
DEFAULT_INTENSITY = "quiet"


@dataclass(frozen=True)
class VeilPolicy:
    intensity: str = DEFAULT_INTENSITY
    max_concurrency: int = 8
    inter_op_delay: float = 0.10
    max_ops_per_run: int = 2048

    @classmethod
    def for_intensity(cls, level: str) -> "VeilPolicy":
        key = level if level in INTENSITIES else DEFAULT_INTENSITY
        return cls(intensity=key, **INTENSITIES[key])

    def as_dict(self) -> dict:
        return {
            "intensity": self.intensity,
            "max_concurrency": self.max_concurrency,
            "inter_op_delay": self.inter_op_delay,
            "max_ops_per_run": self.max_ops_per_run,
        }
