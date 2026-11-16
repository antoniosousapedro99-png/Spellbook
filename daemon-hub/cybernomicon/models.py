"""Pydantic request/response models for the hub API."""
from __future__ import annotations

from pydantic import BaseModel, Field


class CastSpellRequest(BaseModel):
    params: dict = Field(default_factory=dict)


class AddTargetRequest(BaseModel):
    cidr: str
    label: str = ""
    note: str = ""


class SummonDaemonRequest(BaseModel):
    objective: str
    scope: str | None = None


class ArchiveRequest(BaseModel):
    kind: str
    title: str
    detail: str = ""
    severity: str = "info"
