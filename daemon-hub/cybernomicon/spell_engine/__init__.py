"""The Spell Engine package: registry, spell contract, and executor."""
from .base import (ExecutionContext, Spell, SpellRegistry, registry, spell)
from .engine import (ExecutionResult, PermissionDenied, SpellEngine,
                     SpellNotFound)

__all__ = [
    "ExecutionContext", "Spell", "SpellRegistry", "registry", "spell",
    "ExecutionResult", "PermissionDenied", "SpellEngine", "SpellNotFound",
]
