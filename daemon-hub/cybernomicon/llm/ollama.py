"""Minimal async client for a local Ollama server.

Ollama runs entirely on the operator's machine (default http://localhost:11434),
so no data leaves the local network. We deliberately disable httpx's env-based
proxy handling (`trust_env=False`): the hub may sit behind an HTTPS proxy for
outbound traffic, but the Ollama endpoint is always local and must be reached
directly.

Only the two calls the planner needs are implemented: a liveness/listing probe
and a JSON-mode chat completion.
"""
from __future__ import annotations

import json
from typing import Any

import httpx


class OllamaError(RuntimeError):
    """Raised when the local model server is unreachable or errors."""


class OllamaClient:
    def __init__(self, base_url: str, model: str, timeout: float = 120.0):
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout = timeout

    async def is_available(self) -> bool:
        """True if the Ollama server answers and has the configured model."""
        try:
            async with httpx.AsyncClient(trust_env=False,
                                         timeout=5.0) as client:
                r = await client.get(f"{self.base_url}/api/tags")
                r.raise_for_status()
                tags = r.json().get("models", [])
        except (httpx.HTTPError, ValueError):
            return False
        # Match the FULL tag (e.g. "llama3.2:1b"), not just the family name —
        # having llama3.2:3b must not make llama3.2:1b report as available.
        names = {m.get("name", "") for m in tags}
        want = self.model if ":" in self.model else f"{self.model}:latest"
        return want in names or self.model in names

    async def list_models(self) -> list[str]:
        try:
            async with httpx.AsyncClient(trust_env=False,
                                         timeout=5.0) as client:
                r = await client.get(f"{self.base_url}/api/tags")
                r.raise_for_status()
                return [m.get("name", "") for m in r.json().get("models", [])]
        except (httpx.HTTPError, ValueError) as e:
            raise OllamaError(str(e)) from e

    async def chat_json(self, messages: list[dict],
                        temperature: float = 0.2) -> dict[str, Any]:
        """Run a chat completion constrained to a JSON object and parse it.

        Uses Ollama's `format: "json"` so the model must emit valid JSON. We
        still parse defensively and raise OllamaError on anything malformed.
        """
        payload = {
            "model": self.model,
            "messages": messages,
            "stream": False,
            "format": "json",
            "options": {"temperature": temperature},
        }
        try:
            async with httpx.AsyncClient(trust_env=False,
                                         timeout=self.timeout) as client:
                r = await client.post(f"{self.base_url}/api/chat", json=payload)
                r.raise_for_status()
                body = r.json()
        except httpx.HTTPError as e:
            raise OllamaError(f"Ollama request failed: {e}") from e
        except ValueError as e:
            raise OllamaError(f"Ollama returned non-JSON envelope: {e}") from e

        content = (body.get("message") or {}).get("content", "")
        if not content:
            raise OllamaError("Ollama returned an empty message")
        try:
            return json.loads(content)
        except json.JSONDecodeError as e:
            raise OllamaError(
                f"model did not return valid JSON: {e}: {content[:200]!r}"
            ) from e
