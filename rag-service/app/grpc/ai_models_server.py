from __future__ import annotations

import importlib
import logging

import grpc  # type: ignore[import]

from app.config import get_settings
from app.db.session import AsyncSessionLocal
from app.grpc_stubs.loader import ensure_ai_models_stubs, ensure_rag_stubs
from app.ingestion.chunker import RecursiveCharacterTextSplitter
from app.ingestion.embedding_pipeline import EmbeddingPipeline
from app.ingestion.extractor import TextExtractor
from app.ingestion.file_loader import FileLoader
from app.llm.provider_router import ProviderRouter
from app.orchestration.pipeline_executor import PipelineExecutor
from app.repositories.ai_model_repository import AIModelRepository
from app.repositories.user_key_repository import UserKeyRepository
from app.retrieval.reranker import Reranker
from app.retrieval.vector_search import VectorSearch
from app.security.encryption import encrypt_key
from app.security.user_permission_checker import UserPermissionChecker
from app.storage.qdrant_client import get_qdrant_client

log = logging.getLogger(__name__)
settings = get_settings()


def _metadata_value(context: grpc.aio.ServicerContext, key: str) -> str:
    for item_key, value in context.invocation_metadata():
        if item_key == key:
            return value
    return ""


async def _require_service_auth(context: grpc.aio.ServicerContext) -> str:
    service_secret = _metadata_value(context, "x-service-secret")
    user_id = _metadata_value(context, "x-user-id")

    if service_secret != settings.grpc_service_secret:
        await context.abort(grpc.StatusCode.UNAUTHENTICATED, "Invalid service secret")
    if not user_id:
        await context.abort(grpc.StatusCode.UNAUTHENTICATED, "Missing user id")
    return user_id


class _AiModelService:
    async def ListModels(self, request, context):  # noqa: N802
        user_id = await _require_service_auth(context)

        async with AsyncSessionLocal() as session:
            repo = AIModelRepository(session)
            models = await repo.list_active_with_user_key_flag(user_id)

        pb2 = importlib.import_module("app.grpc_stubs.ai_models_pb2")
        model_items = [
            pb2.AiModelDto(
                model_id=item["model_id"],
                model_name=item["model_name"],
                provider=item["provider"],
                context_length=item["context_length"],
                supports_streaming=item["supports_streaming"],
                user_key_configured=item["user_key_configured"],
                platform_key_available=item["platform_key_available"],
                description=item.get("description") or "",
            )
            for item in models
        ]
        return pb2.ListModelsResponse(models=model_items)

    async def CreateUserApiKey(self, request, context):  # noqa: N802
        return await self._upsert_key(request, context, status_text="created")

    async def UpdateUserApiKey(self, request, context):  # noqa: N802
        return await self._upsert_key(request, context, status_text="updated")

    async def DeleteUserApiKey(self, request, context):  # noqa: N802
        user_id = await _require_service_auth(context)
        if not request.model_id:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "model_id is required")

        async with AsyncSessionLocal() as session:
            key_repo = UserKeyRepository(session)
            deleted = await key_repo.delete(user_id=user_id, model_id=request.model_id)

        if not deleted:
            await context.abort(grpc.StatusCode.NOT_FOUND, "API key not found")

        pb2 = importlib.import_module("app.grpc_stubs.ai_models_pb2")
        return pb2.ApiKeyResponse(model_id=request.model_id, status="deleted")

    async def _upsert_key(self, request, context, status_text: str):
        user_id = await _require_service_auth(context)
        if not request.model_id or not request.api_key:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "model_id and api_key are required")

        async with AsyncSessionLocal() as session:
            model_repo = AIModelRepository(session)
            model = await model_repo.get_by_id(request.model_id)
            if model is None:
                await context.abort(grpc.StatusCode.NOT_FOUND, "Model not found")

            encrypted = encrypt_key(request.api_key)
            key_repo = UserKeyRepository(session)
            await key_repo.upsert(
                user_id=user_id,
                model_id=request.model_id,
                encrypted_api_key=encrypted,
            )

        pb2 = importlib.import_module("app.grpc_stubs.ai_models_pb2")
        return pb2.ApiKeyResponse(model_id=request.model_id, status=status_text)


class _RagService:
    async def IngestFile(self, request, context):  # noqa: N802
        user_id = await _require_service_auth(context)
        if not request.file_id:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "file_id is required")
        if not request.storage_path:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "storage_path is required")
        if not request.content_type:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "content_type is required")

        owner_id = request.owner_id or user_id
        pb2 = importlib.import_module("app.grpc_stubs.rag_pb2")
        try:
            loader = FileLoader()
            extractor = TextExtractor()
            splitter = RecursiveCharacterTextSplitter()
            pipeline = EmbeddingPipeline()

            content = loader.load(request.storage_path)
            result = extractor.extract(content, request.content_type, request.file_name)
            chunks = splitter.split(result.text)
            count = await pipeline.embed_and_store(
                chunks=chunks,
                file_id=request.file_id,
                owner_id=owner_id,
                user_id=user_id,
                folder_id=request.folder_id or None,
                extraction_version=result.extraction_version,
            )
            return pb2.IngestFileResponse(
                status="ok",
                file_id=request.file_id,
                chunks_stored=count,
            )
        except FileNotFoundError as exc:
            await context.abort(grpc.StatusCode.NOT_FOUND, str(exc))
        except PermissionError as exc:
            await context.abort(grpc.StatusCode.PERMISSION_DENIED, str(exc))
        except Exception as exc:  # noqa: BLE001
            log.error("Ingestion error: %s", exc)
            await context.abort(grpc.StatusCode.INTERNAL, "Ingestion failed")

    async def RetrieveChunks(self, request, context):  # noqa: N802
        user_id = await _require_service_auth(context)
        if not request.query:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "query is required")

        perm = UserPermissionChecker()
        allowed = await perm.get_allowed_file_ids(user_id, list(request.file_ids))

        searcher = VectorSearch()
        top_k = request.top_k if request.top_k > 0 else None
        raw = await searcher.search(request.query, allowed, top_k=top_k)

        reranker = Reranker()
        final = await reranker.rerank(request.query, raw)

        pb2 = importlib.import_module("app.grpc_stubs.rag_pb2")
        chunks = []
        for item in final:
            payload = item.get("payload", {})
            score = item.get("score")
            page_number = payload.get("page_number")
            chunks.append(
                pb2.RetrievedChunk(
                    score=float(score) if score is not None else 0.0,
                    file_id=str(payload.get("file_id") or ""),
                    page_number=int(page_number) if page_number is not None else 0,
                    chunk_text_preview=str(payload.get("chunk_text") or "")[:300],
                )
            )

        return pb2.RetrieveChunksResponse(
            query=request.query,
            chunk_count=len(chunks),
            chunks=chunks,
        )

    async def DeleteFileVectors(self, request, context):  # noqa: N802
        await _require_service_auth(context)
        if not request.file_id:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "file_id is required")

        pb2 = importlib.import_module("app.grpc_stubs.rag_pb2")
        try:
            await get_qdrant_client().delete_by_file_id(request.file_id)
            return pb2.DeleteFileVectorsResponse(status="ok", file_id=request.file_id)
        except Exception as exc:  # noqa: BLE001
            log.error("Vector deletion error: %s", exc)
            await context.abort(grpc.StatusCode.INTERNAL, "Vector deletion failed")

    async def CancelGeneration(self, request, context):  # noqa: N802
        await _require_service_auth(context)
        if not request.request_id:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "request_id is required")

        PipelineExecutor.cancel(request.request_id)
        pb2 = importlib.import_module("app.grpc_stubs.rag_pb2")
        return pb2.CancelGenerationResponse(
            status="cancellation_requested",
            request_id=request.request_id,
        )

    async def ListProviders(self, request, context):  # noqa: N802
        await _require_service_auth(context)
        router_inst = ProviderRouter()
        pb2 = importlib.import_module("app.grpc_stubs.rag_pb2")
        return pb2.ListProvidersResponse(
            available_providers=router_inst.available_providers()
        )

    async def CollectionInfo(self, request, context):  # noqa: N802
        await _require_service_auth(context)
        pb2 = importlib.import_module("app.grpc_stubs.rag_pb2")
        try:
            client = get_qdrant_client()
            raw_client = await client._get_client()
            info = await raw_client.get_collection(settings.qdrant_collection)
            vectors_count = getattr(info, "vectors_count", None)
            return pb2.CollectionInfoResponse(
                collection=settings.qdrant_collection,
                vectors_count=int(vectors_count) if vectors_count is not None else 0,
                status=str(getattr(info, "status", "unknown")),
                error="",
            )
        except Exception as exc:  # noqa: BLE001
            return pb2.CollectionInfoResponse(
                collection=settings.qdrant_collection,
                vectors_count=0,
                status="unknown",
                error=str(exc),
            )


class AiModelsGrpcServer:
    def __init__(self) -> None:
        ensure_ai_models_stubs()
        ensure_rag_stubs()
        self.server = grpc.aio.server()
        pb2_grpc = importlib.import_module("app.grpc_stubs.ai_models_pb2_grpc")
        rag_pb2_grpc = importlib.import_module("app.grpc_stubs.rag_pb2_grpc")
        pb2_grpc.add_AiModelServiceServicer_to_server(_AiModelService(), self.server)
        rag_pb2_grpc.add_RagServiceServicer_to_server(_RagService(), self.server)
        self.bind_addr = f"{settings.rag_grpc_host}:{settings.rag_grpc_port}"
        self.server.add_insecure_port(self.bind_addr)

    async def start(self) -> None:
        await self.server.start()
        log.info("RAG gRPC server started on %s", self.bind_addr)

    async def stop(self) -> None:
        await self.server.stop(grace=5)
        log.info("RAG gRPC server stopped")
