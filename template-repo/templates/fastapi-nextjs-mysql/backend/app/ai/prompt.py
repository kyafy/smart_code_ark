"""Prompt template builder with named placeholder replacement."""

from __future__ import annotations


class PromptBuilder:
    """Simple prompt builder using {{variable}} placeholders."""

    def __init__(self, template: str) -> None:
        self._template = template
        self._variables: dict[str, str] = {}

    def set(self, key: str, value: str | None) -> "PromptBuilder":
        self._variables[key] = value or ""
        return self

    def build(self) -> str:
        result = self._template
        for key, value in self._variables.items():
            result = result.replace("{{" + key + "}}", value)
        return result
