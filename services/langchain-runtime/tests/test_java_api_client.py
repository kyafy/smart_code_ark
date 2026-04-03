import unittest

import httpx

from app.deepagent.config import CallbackConfig
from app.deepagent.tools.java_api_client import JavaApiClient, JavaApiError


class JavaApiClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_notify_sandbox_ready_unwraps_api_response_data(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.path, "/api/internal/preview/task-1/sandbox-ready")
            return httpx.Response(
                200,
                json={
                    "code": 0,
                    "message": "ok",
                    "data": {"preview_url": "/api/preview/task-1/"},
                },
            )

        client = JavaApiClient(CallbackConfig(base_url="http://test", api_key="k", timeout=5))
        client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://test")
        try:
            preview_url = await client.notify_sandbox_ready("task-1", host_port=30001)
            self.assertEqual(preview_url, "/api/preview/task-1/")
        finally:
            await client.close()

    async def test_generate_file_content_accepts_bare_json(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.path, "/api/internal/model/generate-file")
            return httpx.Response(
                200,
                json={"content": "public class App {}"},
            )

        client = JavaApiClient(CallbackConfig(base_url="http://test", api_key="k", timeout=5))
        client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://test")
        try:
            content = await client.generate_file_content(
                file_path="backend/src/main/java/App.java",
                prd="prd",
                tech_stack="springboot mysql",
                project_structure="README.md",
            )
            self.assertEqual(content, "public class App {}")
        finally:
            await client.close()

    async def test_update_step_raises_on_api_response_business_failure(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.path, "/api/internal/task/t1/step-update")
            return httpx.Response(
                200,
                json={"code": 401, "message": "Invalid internal token", "data": None},
            )

        client = JavaApiClient(CallbackConfig(base_url="http://test", api_key="k", timeout=5))
        client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://test")
        try:
            with self.assertRaises(JavaApiError):
                await client.update_step("t1", "codegen_backend", "running", progress=10)
        finally:
            await client.close()


if __name__ == "__main__":
    unittest.main()
