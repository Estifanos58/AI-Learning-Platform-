# How to Run the Notification Service (FastAPI)

This guide is written for someone who has never run a FastAPI project before.

The service is in `notification-service/` and exposes:
- Health endpoint: `GET /health`
- Swagger docs UI: `GET /docs`

## 1. What you need before starting

Install these tools first:

1. Python 3.12+
2. `pip` (usually installed with Python)
3. Docker and Docker Compose (recommended, for Kafka)

Check your tools:

```bash
python3 --version
pip --version
docker --version
docker compose version
```

## 2. Open the correct folder

From your project root:

```bash
cd notification-service
pwd
```

Make sure `pwd` ends with `.../notification-service`.

## 3. Create and activate a virtual environment

A virtual environment keeps this project dependencies separate from your system Python.

```bash
python3 -m venv .venv
source .venv/bin/activate
```

After activation, your shell prompt usually shows `(.venv)`.

## 4. Install dependencies

```bash
pip install -r requirements.txt
```

This installs FastAPI, Uvicorn, Kafka client, and settings libraries.

## 5. Configure environment variables

Create a `.env` file in `notification-service/` (optional, but recommended):

```bash
cat > .env << 'EOF'
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
KAFKA_GROUP_ID=notification-service-v1
KAFKA_TOPIC_EMAIL_VERIFICATION=user.email.verification.v1
KAFKA_TOPIC_EMAIL_FAILED=notification.email.failed.v1

SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=
SMTP_PASSWORD=
SMTP_FROM=no-reply@aiplatform.local
EOF
```

Notes:
- Keep `SMTP_USERNAME` and `SMTP_PASSWORD` empty if you only want to test startup and `/health`.
- Fill SMTP values when you want real email sending.

## 6. Start Kafka (recommended)

From the project root (one level above `notification-service`):

```bash
cd ..
docker compose up -d kafka
docker compose ps kafka
```

Then return to the service folder:

```bash
cd notification-service
```

Important:
- The service can now start even if Kafka is down.
- If Kafka is down, you will see Kafka connection errors in logs, but FastAPI and `/health` still run.
- Email verification events will not be consumed until Kafka is available.

## 7. Run the FastAPI app

Use Uvicorn to run the app:

```bash
uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

What this means:
- `app.main:app` points to the FastAPI object named `app` in `app/main.py`.
- `--reload` restarts the server automatically when files change (good for development).

Expected startup logs include lines similar to:

```text
Uvicorn running on http://127.0.0.1:8000
Application startup complete.
```

## 8. Verify everything works

In a second terminal (keep the first terminal running):

```bash
curl http://127.0.0.1:8000/health
```

Expected response:

```json
{"status":"ok"}
```

Open API docs in browser:
- `http://127.0.0.1:8000/docs`

## 9. Stop the service

Press `Ctrl+C` in the terminal where Uvicorn is running.

To stop Kafka:

```bash
cd ..
docker compose stop kafka
```

## Troubleshooting (first-time setup)

### A) `uvicorn: command not found`

Cause: virtual environment not activated.

Fix:

```bash
source .venv/bin/activate
uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

### B) `Address already in use` on port 8000

Cause: another process is already using port 8000.

Fix:

```bash
uvicorn app.main:app --host 127.0.0.1 --port 8080 --reload
```

Then test `http://127.0.0.1:8080/health`.

### C) Kafka connection errors in logs

Examples:
- `NoBrokersAvailable`
- `ECONNREFUSED localhost:9092`

Fix:

```bash
cd ..
docker compose up -d kafka
docker compose ps kafka
```

Then restart Uvicorn.

### D) Emails are not sent

Common causes:
- `SMTP_USERNAME` or `SMTP_PASSWORD` missing
- SMTP provider blocks login

Fix:
- Set valid SMTP credentials in `.env`
- For Gmail, use an App Password (not your normal account password)
- Restart Uvicorn after updating `.env`