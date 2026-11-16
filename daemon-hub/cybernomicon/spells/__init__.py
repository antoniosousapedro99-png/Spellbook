"""Initial spell library.

Importing this package registers every bundled spell into the process-wide
registry. New spell modules add themselves here (Phase V replaces this with
dynamic package discovery).
"""
from . import scry, identify, archive  # noqa: F401  (import = registration)
from .archive import bind_archive

__all__ = ["scry", "identify", "archive", "bind_archive"]
