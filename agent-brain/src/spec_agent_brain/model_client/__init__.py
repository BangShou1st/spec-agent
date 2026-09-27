"""文件名:__init__.py

用途:model_client 子包的对外出口,定义 brain 的模型客户端边界,统一导出
消息/补全数据类、客户端协议以及 broker 与 fake 两种实现。
"""

from .base import BrokerTimeoutError, ChatMessage, Completion, ModelClient, ModelClientError
from .broker_client import BrokerModelClient
from .fake import FakeModelClient

__all__ = [
    "ChatMessage",
    "Completion",
    "ModelClient",
    "ModelClientError",
    "BrokerTimeoutError",
    "BrokerModelClient",
    "FakeModelClient",
]
