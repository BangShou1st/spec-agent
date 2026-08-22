FROM python:3.11-slim

WORKDIR /app

COPY pyproject.toml README.md ./
COPY src ./src

RUN pip install --no-cache-dir .

EXPOSE 8100

CMD ["uvicorn", "spec_agent_brain.app:app", "--host", "0.0.0.0", "--port", "8100"]
