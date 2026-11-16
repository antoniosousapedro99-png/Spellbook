"""Local LLM integration (Ollama) for the agent runtime."""
from .ollama import OllamaClient, OllamaError

__all__ = ["OllamaClient", "OllamaError"]
