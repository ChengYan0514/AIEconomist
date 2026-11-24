# Causal Engine Sidecar

原样包裹 `ClaimInEconomics` 因果抽取算法的无数据库 HTTP Sidecar 服务，供 AI Economist Java 后端通过 `PAPERFLOW_CAUSAL_ENGINE_URL` 对接。

## 本地启动

```bash
python3 -m venv causal-engine/.venv
causal-engine/.venv/bin/pip install -r causal-engine/requirements.txt
CAUSAL_CHAT_API_KEY=... CAUSAL_EMBEDDING_API_KEY=... \
  causal-engine/.venv/bin/uvicorn app:app --app-dir causal-engine --host 127.0.0.1 --port 18003
```

## Docker Compose 启动（默认随基础服务启动）

模型参数与 API Key 可在平台管理台「系统管理 → 服务配置」中动态配置，容器启动时无需预先写入密钥：

```bash
docker compose up -d --build causal-engine
curl --fail http://127.0.0.1:18003/v1/health
```
