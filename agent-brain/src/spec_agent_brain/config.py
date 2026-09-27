"""文件名:config.py

用途:从环境变量解析服务配置。

Brain 永远不会拿到数据库凭证,也不持有模型厂商的 API key:模型推理
统一经由 Java 内部推理 broker 转发。

模式是显式的两值枚举:`fake`(确定性离线实现,仅供测试/离线演示)或
`broker`(正式模式,回调 Java 内部推理 broker)。未知值——包括拼写
错误——一律在启动时失败,绝不能静默回落到 fake 制造"AI 正常工作"
的假象;broker 模式还要求完整的 broker 配置(broker URL 与内部密钥)。
"""

import os
from dataclasses import dataclass

ALLOWED_MODEL_MODES = ("fake", "broker")


@dataclass(frozen=True)
class Settings:
    internal_secret: str
    model_mode: str  # 只能是 "fake" 或 "broker"
    broker_url: str
    broker_timeout_seconds: float

    @property
    def auth_enabled(self) -> bool:
        return bool(self.internal_secret)


class ConfigurationError(Exception):
    """启动期配置错误:服务必须拒绝以错误配置运行(fail closed)。"""


def load_settings() -> Settings:
    # strip():cmd.exe 里 `set X=value && next` 的写法会把分隔用的空格泄漏进
    # 值里;曾经因为尾部多了一个空格导致 secret 不匹配(每次 decision 调用
    # 都 401)并悄悄禁用了 broker 模式。在入口处统一归一化。
    model_mode = os.environ.get("SPEC_AGENT_BRAIN_MODEL_MODE", "fake").strip().lower()
    if model_mode not in ALLOWED_MODEL_MODES:
        raise ConfigurationError(
            f"SPEC_AGENT_BRAIN_MODEL_MODE must be one of {list(ALLOWED_MODEL_MODES)}, "
            f"got {model_mode!r}. The service refuses to run with an unknown mode "
            "instead of silently falling back to the deterministic fake client."
        )
    broker_url = os.environ.get(
        "SPEC_AGENT_INTERNAL_BROKER_URL",
        "http://localhost:8080/internal/v1/model-inference",
    ).strip()
    internal_secret = os.environ.get("SPEC_AGENT_BRAIN_INTERNAL_SECRET", "").strip()
    if model_mode == "broker":
        if not broker_url.startswith(("http://", "https://")):
            raise ConfigurationError(
                "broker mode requires a valid SPEC_AGENT_INTERNAL_BROKER_URL "
                f"(http/https), got {broker_url!r}"
            )
        if not internal_secret:
            raise ConfigurationError(
                "broker mode requires SPEC_AGENT_BRAIN_INTERNAL_SECRET: the Java "
                "internal broker rejects unauthenticated calls, so an empty secret "
                "would turn every inference call into a 401."
            )
    return Settings(
        internal_secret=internal_secret,
        model_mode=model_mode,
        broker_url=broker_url,
        # 需与 Java 侧保持一致(spec.agent.brain.read-timeout-seconds):
        # 过早掐断 broker 调用会浪费一次已完成的模型响应,而模型厂商较慢时
        # 曾观察到单次往返耗时 128-208 秒。
        broker_timeout_seconds=float(os.environ.get("SPEC_AGENT_BROKER_TIMEOUT_SECONDS", "300")),
    )
