"""Runtime configuration for the Cybernomicon Daemon Hub.

Configuration is read from environment variables so the hub can run the same
way in a developer shell, a container, or a CI job. Nothing security-relevant
is hard-coded; secrets come from the environment or a generated local file.
"""
from __future__ import annotations

import os
import secrets
from dataclasses import dataclass, field
from pathlib import Path


def _default_data_dir() -> Path:
    # Prefer an explicit directory, otherwise keep state next to the package.
    env = os.environ.get("CYBERNOMICON_DATA_DIR")
    if env:
        return Path(env).expanduser()
    return Path(__file__).resolve().parent.parent / "var"


@dataclass(frozen=True)
class Settings:
    """Immutable settings snapshot loaded once at process start."""

    data_dir: Path = field(default_factory=_default_data_dir)
    db_path: Path = field(default=Path())  # filled in __post_init__-style below
    host: str = os.environ.get("CYBERNOMICON_HOST", "127.0.0.1")
    port: int = int(os.environ.get("CYBERNOMICON_PORT", "8787"))

    # TLS (optional in local/lab; strongly recommended for anything else).
    tls_certfile: str | None = os.environ.get("CYBERNOMICON_TLS_CERT") or None
    tls_keyfile: str | None = os.environ.get("CYBERNOMICON_TLS_KEY") or None

    # Default per-spell execution ceiling; individual spells may lower it.
    default_spell_timeout: float = float(
        os.environ.get("CYBERNOMICON_SPELL_TIMEOUT", "30")
    )

    # --- Agent planner -------------------------------------------------
    # "deterministic" (default, no LLM) or "local" (an Ollama model).
    planner: str = os.environ.get("CYBERNOMICON_PLANNER", "deterministic")
    ollama_url: str = os.environ.get(
        "CYBERNOMICON_OLLAMA_URL", "http://localhost:11434")
    ollama_model: str = os.environ.get("CYBERNOMICON_OLLAMA_MODEL", "llama3.1")
    # Ceiling on a single model call; local models can be slow on first token.
    llm_timeout: float = float(os.environ.get("CYBERNOMICON_LLM_TIMEOUT", "120"))
    # Hard cap on planning iterations when using the LLM planner.
    llm_max_steps: int = int(os.environ.get("CYBERNOMICON_LLM_MAX_STEPS", "12"))

    def with_paths(self) -> "Settings":
        data_dir = self.data_dir
        data_dir.mkdir(parents=True, exist_ok=True)
        object.__setattr__(self, "db_path", data_dir / "cybernomicon.db")
        return self

    @property
    def tls_enabled(self) -> bool:
        return bool(self.tls_certfile and self.tls_keyfile)


def load_settings() -> Settings:
    return Settings().with_paths()


def load_or_create_api_token(settings: Settings) -> str:
    """Return the shared bearer token, creating one on first run.

    The token authenticates the Android client to the hub. It is stored with
    owner-only permissions. In production this would be per-client and rotated;
    for the Phase I foundation a single securely-generated token is enough.
    """
    env_token = os.environ.get("CYBERNOMICON_API_TOKEN")
    if env_token:
        return env_token

    token_file = settings.data_dir / "api_token"
    if token_file.exists():
        return token_file.read_text(encoding="utf-8").strip()

    token = secrets.token_urlsafe(32)
    token_file.write_text(token, encoding="utf-8")
    try:
        token_file.chmod(0o600)
    except OSError:
        pass  # Best effort on platforms without POSIX perms.
    return token
