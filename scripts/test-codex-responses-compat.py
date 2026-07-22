#!/usr/bin/env python3
"""Drive the installed Codex CLI through a disposable Responses API tool loop.

The mock server controls the only returned tool call and the workspace lives under a
TemporaryDirectory.  CODEX_HOME is separate, user configuration/rules are ignored, and the
session is ephemeral, so this regression cannot log out or overwrite the operator's Codex login.
"""

from __future__ import annotations

import gzip
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
from typing import Any


PROBE_FILE = "codex_openai_probe.txt"
PROBE_CONTENT = "CODEX_RESPONSES_TOOL_OK\n"
CALL_ID = "call_deekseep_codex_probe"
ITEM_ID = "item_deekseep_codex_probe"


def usage() -> dict[str, Any]:
    return {
        "input_tokens": 16,
        "input_tokens_details": {"cached_tokens": 0},
        "output_tokens": 8,
        "output_tokens_details": {"reasoning_tokens": 0},
        "total_tokens": 24,
    }


def frame(event: str, sequence: int, **payload: Any) -> str:
    data = {"type": event, "sequence_number": sequence, **payload}
    return f"event: {event}\ndata: {json.dumps(data, separators=(',', ':'))}\n\n"


def tool_stream(probe_path: Path) -> bytes:
    patch = (
        "*** Begin Patch\n"
        f"*** Add File: {probe_path}\n"
        "+CODEX_RESPONSES_TOOL_OK\n"
        "*** End Patch"
    )
    added = {
        "id": ITEM_ID,
        "type": "custom_tool_call",
        "status": "in_progress",
        "call_id": CALL_ID,
        "name": "apply_patch",
        "input": "",
    }
    done = {**added, "status": "completed", "input": patch}
    response = {
        "id": "resp_deekseep_codex_tool",
        "object": "response",
        "status": "in_progress",
        "model": "gpt-5.4",
        "output": [],
    }
    completed = {
        **response,
        "status": "completed",
        "output": [done],
        "usage": usage(),
        "end_turn": False,
    }
    return "".join(
        [
            frame("response.created", 1, response=response),
            frame("response.in_progress", 2, response=response),
            frame("response.output_item.added", 3, output_index=0, item=added),
            frame(
                "response.custom_tool_call_input.delta",
                4,
                item_id=ITEM_ID,
                call_id=CALL_ID,
                output_index=0,
                delta=patch,
            ),
            frame(
                "response.custom_tool_call_input.done",
                5,
                item_id=ITEM_ID,
                call_id=CALL_ID,
                output_index=0,
                input=patch,
            ),
            frame("response.output_item.done", 6, output_index=0, item=done),
            frame("response.completed", 7, response=completed),
        ]
    ).encode()


def final_stream() -> bytes:
    text = "CODEX_OPENAI_TOOL_LOOP_OK"
    added = {
        "id": "msg_deekseep_codex_probe",
        "type": "message",
        "status": "in_progress",
        "role": "assistant",
        "phase": "final_answer",
        "content": [],
    }
    done = {
        **added,
        "status": "completed",
        "content": [
            {"type": "output_text", "text": text, "annotations": []},
        ],
    }
    response = {
        "id": "resp_deekseep_codex_final",
        "object": "response",
        "status": "in_progress",
        "model": "gpt-5.4",
        "output": [],
    }
    completed = {
        **response,
        "status": "completed",
        "output": [done],
        "usage": usage(),
        "end_turn": True,
    }
    return "".join(
        [
            frame("response.created", 1, response=response),
            frame("response.in_progress", 2, response=response),
            frame("response.output_item.added", 3, output_index=0, item=added),
            frame(
                "response.output_text.delta",
                4,
                item_id=done["id"],
                output_index=0,
                content_index=0,
                delta=text,
            ),
            frame("response.output_item.done", 5, output_index=0, item=done),
            frame("response.completed", 6, response=completed),
        ]
    ).encode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    bodies: list[dict[str, Any]] = []
    errors: list[str] = []
    thread_id: str | None = None
    session_id: str | None = None
    probe_path: Path | None = None

    def log_message(self, *_args: object) -> None:
        return

    def do_POST(self) -> None:  # noqa: N802
        try:
            assert self.path == "/v1/responses", f"unexpected path {self.path}"
            raw = self.rfile.read(int(self.headers.get("content-length", "0")))
            if self.headers.get("content-encoding", "").lower() == "gzip":
                raw = gzip.decompress(raw)
            body = json.loads(raw)
            self.__class__.bodies.append(body)
            self._validate_common(body)

            outputs = [
                item
                for item in body.get("input", [])
                if isinstance(item, dict)
                and item.get("type") == "custom_tool_call_output"
            ]
            if outputs:
                assert len(outputs) == 1, "Codex returned an unexpected tool-output count"
                assert outputs[0].get("call_id") == CALL_ID, "tool call_id was not preserved"
                assert outputs[0].get("output"), "apply_patch output is empty"
                payload = final_stream()
            else:
                self._validate_initial(body)
                assert self.__class__.probe_path is not None
                payload = tool_stream(self.__class__.probe_path)

            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(payload)
            self.wfile.flush()
        except Exception as exc:  # report assertions to the parent process deterministically
            self.__class__.errors.append(f"{type(exc).__name__}: {exc}")
            payload = json.dumps(
                {"error": {"type": "invalid_request_error", "message": "probe rejected"}}
            ).encode()
            self.send_response(400)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(payload)

    def _validate_common(self, body: dict[str, Any]) -> None:
        assert self.headers.get("authorization") == "Bearer isolated-test-key"
        assert body.get("model") == "gpt-5.4"
        assert body.get("stream") is True
        assert body.get("store") is False
        assert body.get("tool_choice") == "auto"
        assert isinstance(body.get("instructions"), str) and body["instructions"]
        assert isinstance(body.get("prompt_cache_key"), str)
        metadata = body.get("client_metadata")
        assert isinstance(metadata, dict), "Codex client_metadata is missing"
        assert isinstance(metadata.get("thread_id"), str) and metadata["thread_id"]
        assert isinstance(metadata.get("session_id"), str) and metadata["session_id"]
        assert self.headers.get("thread-id") == metadata["thread_id"]
        assert self.headers.get("session-id") == metadata["session_id"]
        assert self.headers.get("x-client-request-id") == metadata["thread_id"]
        if self.__class__.thread_id is None:
            self.__class__.thread_id = metadata["thread_id"]
            self.__class__.session_id = metadata["session_id"]
        else:
            assert metadata["thread_id"] == self.__class__.thread_id
            assert metadata["session_id"] == self.__class__.session_id

    @staticmethod
    def _validate_initial(body: dict[str, Any]) -> None:
        serialized_input = json.dumps(body.get("input", []), ensure_ascii=False)
        assert "CODEX_TOOL_LOOP_PROBE" in serialized_input
        tools = body.get("tools")
        assert isinstance(tools, list) and tools
        assert any(
            isinstance(tool, dict)
            and tool.get("type") == "custom"
            and tool.get("name") == "apply_patch"
            for tool in tools
        ), "Codex did not load its custom apply_patch tool"


def main() -> None:
    codex = shutil.which("codex")
    if not codex:
        raise SystemExit("codex executable not found")
    repository = Path(__file__).resolve().parents[1]

    Handler.bodies.clear()
    Handler.errors.clear()
    Handler.thread_id = None
    Handler.session_id = None
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    try:
        # Keep the disposable workspace below the checked-out project root. Codex's patch
        # approval boundary intentionally rejects a sibling system-temp directory even when
        # `-C` points at it; nesting here preserves workspace-write isolation without touching
        # tracked files, and TemporaryDirectory removes the whole probe afterward.
        with tempfile.TemporaryDirectory(
            prefix=".deekseep-codex-responses-", dir=repository
        ) as root:
            root_path = Path(root)
            workspace = root_path / "workspace"
            codex_home = root_path / "codex-home"
            workspace.mkdir()
            codex_home.mkdir()
            Handler.probe_path = workspace / PROBE_FILE
            subprocess.run(
                ["git", "init", "-q"], cwd=workspace, check=True,
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            base_url = f"http://127.0.0.1:{server.server_port}/v1"
            env = os.environ.copy()
            env["CODEX_HOME"] = str(codex_home)
            env["DEEKSEEP_CODEX_PROBE_KEY"] = "isolated-test-key"
            command = [
                codex,
                "exec",
                "--ignore-user-config",
                "--ignore-rules",
                "--ephemeral",
                # Codex 0.144.5 on Termux rejects custom apply_patch as outside-project when
                # approval=never and workspace-write are combined, even in this disposable Git
                # root. The fake server below controls the sole deterministic tool call, and both
                # CODEX_HOME and the workspace are temporary, so no untrusted model output runs.
                "--dangerously-bypass-approvals-and-sandbox",
                "--color",
                "never",
                "-C",
                str(workspace),
                "-m",
                "gpt-5.4",
                "-c",
                'model_provider="deekseep_probe"',
                "-c",
                'model_providers.deekseep_probe.name="Deekseep Probe"',
                "-c",
                f"model_providers.deekseep_probe.base_url={json.dumps(base_url)}",
                "-c",
                'model_providers.deekseep_probe.env_key="DEEKSEEP_CODEX_PROBE_KEY"',
                "-c",
                'model_providers.deekseep_probe.wire_api="responses"',
                "Create codex_openai_probe.txt with the exact requested content using "
                "apply_patch, then report success. Marker: CODEX_TOOL_LOOP_PROBE",
            ]
            result = subprocess.run(
                command,
                cwd=workspace,
                env=env,
                stdin=subprocess.DEVNULL,
                text=True,
                capture_output=True,
                timeout=60,
                check=False,
            )
            if Handler.errors:
                raise AssertionError("; ".join(Handler.errors))
            if result.returncode != 0:
                diagnostic = (result.stdout + "\n" + result.stderr)[-3000:]
                raise AssertionError(
                    f"Codex exited with {result.returncode}:\n{diagnostic}"
                )
            assert len(Handler.bodies) == 2, (
                f"expected one tool request and one continuation, got {len(Handler.bodies)}"
            )
            probe_path = workspace / PROBE_FILE
            if not probe_path.exists():
                diagnostic = (result.stdout + "\n" + result.stderr)[-5000:]
                tool_outputs = [
                    item
                    for body in Handler.bodies
                    for item in body.get("input", [])
                    if isinstance(item, dict)
                    and item.get("type") == "custom_tool_call_output"
                ]
                raise AssertionError(
                    "Codex returned a tool result but did not create the probe file.\n"
                    f"Tool outputs: {tool_outputs!r}\n"
                    f"Advertised tools: {Handler.bodies[0].get('tools')!r}\n"
                    f"Instructions: {Handler.bodies[0].get('instructions')!r}\n"
                    f"{diagnostic}"
                )
            assert probe_path.read_text() == PROBE_CONTENT
            assert "CODEX_OPENAI_TOOL_LOOP_OK" in result.stdout + result.stderr
    finally:
        server.shutdown()
        server.server_close()

    print(
        "Codex Responses compatibility OK "
        "(isolated CODEX_HOME, custom apply_patch, tool output, final answer)"
    )


if __name__ == "__main__":
    main()
