#!/usr/bin/env python3
"""Offline wire regression for deekseep-agent.py."""

from __future__ import annotations

import contextlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
from pathlib import Path
import threading
from types import SimpleNamespace
from unittest import mock


SCRIPT = Path(__file__).with_name("deekseep-agent.py")
SPEC = importlib.util.spec_from_file_location("deekseep_agent", SCRIPT)
assert SPEC and SPEC.loader
agent = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(agent)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    bodies: list[dict[str, object]] = []

    def log_message(self, *_args: object) -> None:
        return

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("content-length", "0"))
        body = json.loads(self.rfile.read(length))
        self.__class__.bodies.append(body)
        assert body["stream"] is True
        if self.path == "/v1/chat/completions":
            assert self.headers["authorization"] == "Bearer test-key"
            frames = [
                'data: {"choices":[{"delta":{"reasoning_content":"plan"}}]}\n\n',
                ": deekseep-ping\n\n",
                'data: {"choices":[{"delta":{"content":"OPENAI_OK"}}]}\n\n',
                "data: [DONE]\n\n",
            ]
        elif self.path == "/v1/messages":
            assert self.headers["x-api-key"] == "test-key"
            assert self.headers["anthropic-version"] == agent.ANTHROPIC_VERSION
            frames = [
                'event: message_start\ndata: {"type":"message_start","message":{"content":[]}}\n\n',
                ": deekseep-ping\n\n",
                'event: content_block_delta\ndata: {"type":"content_block_delta",'
                '"index":0,"delta":{"type":"text_delta","text":"ANTHROPIC_OK"}}\n\n',
                'event: message_stop\ndata: {"type":"message_stop"}\n\n',
            ]
        else:
            self.send_error(404)
            return
        payload = "".join(frames).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.wfile.flush()


def main() -> None:
    Handler.bodies.clear()
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_port}"
    try:
        capture = io.StringIO()
        with contextlib.redirect_stdout(capture):
            answer, reasoning = agent.perform_request(
                "openai", base + "/v1", "test-key", "deepseek-chat",
                [{"role": "user", "content": "test"}], None, True, True, 5, False,
                session_id="openai-session")
        assert answer == "OPENAI_OK" and reasoning == "plan"
        assert "[思考]" in capture.getvalue() and "[回答]" in capture.getvalue()
        assert Handler.bodies[-1]["user"] == "openai-session"

        capture = io.StringIO()
        with contextlib.redirect_stdout(capture):
            answer, reasoning = agent.perform_request(
                "anthropic", base, "test-key", "deepseek-chat",
                [{"role": "user", "content": "test"}], None, False, True, 5, False,
                session_id="anthropic-session")
        assert answer == "ANTHROPIC_OK" and reasoning == ""
        assert capture.getvalue().strip() == "ANTHROPIC_OK"
        assert Handler.bodies[-1]["metadata"] == {"user_id": "anthropic-session"}
    finally:
        server.shutdown()
        server.server_close()

    session_ids = iter(("session-1", "session-2", "session-3", "session-4"))
    prompts = iter(("first", "/new", "second", "/reset", "third", "/clear",
                    "fourth", "/quit"))
    calls: list[tuple[str | None, list[dict[str, str]]]] = []

    def fake_ask(_args: object, _protocol: str, _base_url: str, current_key: str,
                 _explicit_key: bool, messages: list[dict[str, str]], _thinking: bool,
                 session_id: str | None = None) -> tuple[str, str, str]:
        calls.append((session_id, [dict(message) for message in messages]))
        return f"answer-{len(calls)}", "", current_key

    args = SimpleNamespace(think=False, model="deepseek-chat", system=None,
                           non_stream=False, timeout=5, debug=False, retries=0)
    with mock.patch.object(agent, "new_session_id", side_effect=lambda: next(session_ids)), \
            mock.patch.object(agent, "ask_with_retry", side_effect=fake_ask), \
            mock.patch("builtins.input", side_effect=lambda _prompt="": next(prompts)), \
            contextlib.redirect_stdout(io.StringIO()):
        assert agent.interactive(args, "anthropic", "http://127.0.0.1", "test-key", True) == 0
    assert [call[0] for call in calls] == ["session-1", "session-2", "session-3", "session-4"]
    assert [[message["content"] for message in call[1]] for call in calls] == [
        ["first"], ["second"], ["third"], ["fourth"]
    ]
    print("deekseep-agent wire regression OK")


if __name__ == "__main__":
    main()
