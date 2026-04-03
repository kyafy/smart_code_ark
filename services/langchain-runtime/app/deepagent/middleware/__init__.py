"""Custom middleware for DeepAgent pipelines."""

from .code_quality import CodeQualityMiddleware
from .citation_trace import CitationTraceMiddleware
from .adaptive_rerank import AdaptiveRerankMiddleware
from .memory_bridge import MemoryBridgeMiddleware
from .smart_retry import SmartRetryMiddleware
from .dynamic_prompt import DynamicPromptMiddleware
from .context_compression import ContextCompressionMiddleware
from .adaptive_model_switch import AdaptiveModelSwitchMiddleware

__all__ = [
    "CodeQualityMiddleware",
    "CitationTraceMiddleware",
    "AdaptiveRerankMiddleware",
    "MemoryBridgeMiddleware",
    "SmartRetryMiddleware",
    "DynamicPromptMiddleware",
    "ContextCompressionMiddleware",
    "AdaptiveModelSwitchMiddleware",
]
