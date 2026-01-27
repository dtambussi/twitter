FROM python:3.12-slim

WORKDIR /app

RUN pip install --no-cache-dir rich aiohttp

COPY config.json /app/
COPY demo_load_tester.py /app/

ENTRYPOINT ["python", "/app/demo_load_tester.py"]
