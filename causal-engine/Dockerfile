FROM python:3.12-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
COPY requirements.txt .
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
RUN pip install --no-cache-dir --default-timeout=120 -i ${PIP_INDEX_URL} -r requirements.txt
COPY engine ./engine
COPY app.py .
EXPOSE 18003
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "18003"]
