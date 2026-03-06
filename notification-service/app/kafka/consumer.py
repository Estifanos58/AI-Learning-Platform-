import json
import logging
import threading
from typing import Any

from kafka import KafkaConsumer, KafkaProducer

from app.config import Settings
from app.handlers.verification_handler import VerificationHandler
from app.utils.idempotency import InMemoryIdempotencyStore

logger = logging.getLogger(__name__)


class VerificationConsumer:
    def __init__(
        self,
        settings: Settings,
        verification_handler: VerificationHandler,
        idempotency_store: InMemoryIdempotencyStore,
    ) -> None:
        self._settings = settings
        self._verification_handler = verification_handler
        self._idempotency_store = idempotency_store
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None

        self._consumer = KafkaConsumer(
            settings.kafka_topic_email_verification,
            bootstrap_servers=settings.kafka_bootstrap_servers,
            group_id=settings.kafka_group_id,
            enable_auto_commit=True,
            auto_offset_reset='earliest',
            value_deserializer=lambda data: json.loads(data.decode('utf-8')),
            consumer_timeout_ms=1000,
        )
        self._producer = KafkaProducer(
            bootstrap_servers=settings.kafka_bootstrap_servers,
            value_serializer=lambda payload: json.dumps(payload).encode('utf-8'),
        )

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return

        self._thread = threading.Thread(target=self._run, daemon=True, name='verification-consumer')
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=5)
        self._consumer.close()
        self._producer.close()

    def _run(self) -> None:
        while not self._stop_event.is_set():
            for message in self._consumer:
                if self._stop_event.is_set():
                    break
                self._process_message(message)

    def _process_message(self, message: Any) -> None:
        payload = message.value if isinstance(message.value, dict) else {}
        event_id = str(payload.get('eventId', ''))
        user_id = str(payload.get('userId', ''))
        email = str(payload.get('email', '')).strip().lower()

        correlation_id = self._extract_correlation_id(message)

        if not event_id:
            logger.error(
                json.dumps(
                    {
                        'message': 'notification_event_missing_id',
                        'correlationId': correlation_id,
                        'userId': user_id,
                        'email': email,
                    }
                )
            )
            return

        if self._idempotency_store.is_processed(event_id):
            logger.info(
                json.dumps(
                    {
                        'message': 'notification_event_already_processed',
                        'correlationId': correlation_id,
                        'eventId': event_id,
                        'userId': user_id,
                        'email': email,
                    }
                )
            )
            return

        try:
            self._verification_handler.handle(payload, correlation_id)
            self._idempotency_store.mark_processed(event_id)
        except Exception as exc:
            logger.error(
                json.dumps(
                    {
                        'message': 'verification_email_failed',
                        'correlationId': correlation_id,
                        'eventId': event_id,
                        'userId': user_id,
                        'email': email,
                        'error': str(exc),
                    }
                )
            )
            self._publish_dead_letter(payload, correlation_id, str(exc))

    def _publish_dead_letter(self, payload: dict[str, Any], correlation_id: str, error: str) -> None:
        dead_letter_payload = {
            'correlationId': correlation_id,
            'error': error,
            'payload': payload,
        }
        self._producer.send(self._settings.kafka_topic_email_failed, dead_letter_payload)
        self._producer.flush()

    def _extract_correlation_id(self, message: Any) -> str:
        headers = getattr(message, 'headers', None) or []
        for key, value in headers:
            if key == 'correlationId' and value is not None:
                return value.decode('utf-8', errors='ignore')
        return ''
