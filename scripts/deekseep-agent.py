#!/usr/bin/env python3
"""Tiny streaming client for the DeepSeek Local API gateway.

No third-party package is required.  With no positional prompt it opens an interactive chat;
with a prompt it performs one streaming request and exits.  `/think` toggles native DeepSeek
thinking for later turns.
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Any, Iterable
from urllib.parse import urlparse
import uuid


PRIVATE_INFO = Path("/data/data/com.deepseek.chat/files/deekseep_local_api.txt")
PRIVATE_KEY = Path("/data/data/com.deepseek.chat/files/deekseep_local_api_key")
PUBLIC_INFO = Path("/storage/emulated/0/Deekseep_API.txt")
DEFAULT_ROOT = "http://127.0.0.1:8765"
ANTHROPIC_VERSION = "2023-06-01"


class ApiFailure(RuntimeError):
    def __init__(self, status: int, message: str, code: str = "api_error", partial: str = ""):
        super().__init__(message)
        self.status = status
        self.code = code
        self.partial = partial


class StreamInterrupted(ApiFailure):
    pass


def _root_read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, PermissionError):
        pass
    try:
        completed = subprocess.run(
            ["su", "-c", f"cat {path}"],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
        if completed.returncode == 0:
            return completed.stdout
    except (OSError, subprocess.SubprocessError):
        pass
    return ""


def read_gateway_info() -> dict[str, str]:
    raw = _root_read(PRIVATE_INFO)
    if not raw:
        raw = _root_read(PUBLIC_INFO)
    info: dict[str, str] = {}
    for line in raw.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        info[key.strip().lower()] = value.strip()
    if "api_key" not in info:
        key = _root_read(PRIVATE_KEY).strip()
        if key:
            info["api_key"] = key
    return info


def normalize_root(value: str) -> str:
    base = (value or DEFAULT_ROOT).strip().rstrip("/")
    if base.endswith("/v1"):
        base = base[:-3]
    parsed = urlparse(base)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError(f"无效 base URL：{value}")
    return base


def resolve_configuration(args: argparse.Namespace) -> tuple[str, str, str, bool]:
    info = read_gateway_info()
    selected = info.get("protocol", "openai").lower()
    protocol = selected if args.protocol == "auto" else args.protocol
    if protocol not in {"openai", "anthropic"}:
        protocol = "openai"

    base_value = args.base_url or info.get("base_url") or DEFAULT_ROOT
    root = normalize_root(base_value)
    base_url = root + "/v1" if protocol == "openai" else root

    explicit = bool(args.api_key)
    if args.api_key:
        key = args.api_key
    elif protocol == "anthropic":
        key = (os.environ.get("DEEKSEEP_API_KEY")
               or os.environ.get("ANTHROPIC_AUTH_TOKEN")
               or os.environ.get("ANTHROPIC_API_KEY")
               or info.get("api_key", ""))
    else:
        key = (os.environ.get("DEEKSEEP_API_KEY")
               or os.environ.get("OPENAI_API_KEY")
               or info.get("api_key", ""))
    if not key or key == "not-generated":
        raise ValueError("没有找到 API Key；请先在 DeepSeek 本地 API 页面启动服务")
    return protocol, base_url, key.strip(), explicit


def endpoint_parts(base_url: str, protocol: str) -> tuple[str, str, int, str]:
    parsed = urlparse(base_url)
    scheme = parsed.scheme
    host = parsed.hostname or "127.0.0.1"
    port = parsed.port or (443 if scheme == "https" else 80)
    prefix = parsed.path.rstrip("/")
    path = (prefix + "/chat/completions") if protocol == "openai" else "/v1/messages"
    return scheme, host, port, path


def request_body(protocol: str, model: str, messages: list[dict[str, str]],
                 system: str | None, thinking: bool, stream: bool,
                 session_id: str | None = None) -> dict[str, Any]:
    if protocol == "openai":
        wire_messages: list[dict[str, str]] = []
        if system:
            wire_messages.append({"role": "system", "content": system})
        wire_messages.extend(messages)
        body: dict[str, Any] = {
            "model": model,
            "messages": wire_messages,
            "stream": stream,
        }
        if thinking:
            body["deep_think"] = True
        if stream:
            body["stream_options"] = {"include_usage": True}
        if session_id:
            body["user"] = session_id
        return body

    body = {
        "model": model,
        "max_tokens": 8192,
        "messages": messages,
        "stream": stream,
    }
    if system:
        body["system"] = system
    if thinking:
        body["thinking"] = {"type": "enabled", "budget_tokens": 4096}
    if session_id:
        body["metadata"] = {"user_id": session_id}
    return body


def new_session_id() -> str:
    return f"deekseep_agent_session_{uuid.uuid4().hex}"


class OutputPrinter:
    def __init__(self) -> None:
        self.reasoning_started = False
        self.answer_started = False
        self.printed = False

    def reasoning(self, value: str) -> None:
        if not value:
            return
        if not self.reasoning_started:
            sys.stdout.write("\n[思考]\n")
            self.reasoning_started = True
        sys.stdout.write(value)
        sys.stdout.flush()
        self.printed = True

    def answer(self, value: str) -> None:
        if not value:
            return
        if self.reasoning_started and not self.answer_started:
            sys.stdout.write("\n\n[回答]\n")
        self.answer_started = True
        sys.stdout.write(value)
        sys.stdout.flush()
        self.printed = True

    def finish(self) -> None:
        if self.printed:
            sys.stdout.write("\n")
            sys.stdout.flush()


def decode_error(status: int, raw: bytes) -> ApiFailure:
    message = raw.decode("utf-8", "replace").strip() or f"HTTP {status}"
    code = "http_error"
    try:
        payload = json.loads(message)
        error = payload.get("error", payload)
        if isinstance(error, dict):
            message = str(error.get("message") or message)
            code = str(error.get("code") or error.get("type") or code)
    except json.JSONDecodeError:
        pass
    return ApiFailure(status, message, code)


def _events(response: http.client.HTTPResponse) -> Iterable[tuple[str, str]]:
    event_name = ""
    data_lines: list[str] = []
    while True:
        raw = response.readline()
        if not raw:
            if event_name or data_lines:
                yield event_name, "\n".join(data_lines)
            return
        line = raw.decode("utf-8", "replace").rstrip("\r\n")
        if not line:
            if event_name or data_lines:
                yield event_name, "\n".join(data_lines)
            event_name = ""
            data_lines = []
            continue
        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event_name = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip())


def stream_response(protocol: str, response: http.client.HTTPResponse,
                    printer: OutputPrinter) -> tuple[str, str]:
    answer: list[str] = []
    reasoning: list[str] = []
    completed = False
    try:
        for event_name, data in _events(response):
            if protocol == "openai" and data == "[DONE]":
                completed = True
                break
            if not data:
                continue
            try:
                event = json.loads(data)
            except json.JSONDecodeError:
                continue
            if isinstance(event.get("error"), dict):
                error = event["error"]
                raise ApiFailure(502, str(error.get("message", "stream error")),
                                 str(error.get("code") or error.get("type") or "stream_error"),
                                 "".join(answer))
            if protocol == "openai":
                choices = event.get("choices") or []
                if not choices:
                    continue
                delta = choices[0].get("delta") or {}
                thought = delta.get("reasoning_content") or ""
                text = delta.get("content") or ""
                if thought:
                    reasoning.append(thought)
                    printer.reasoning(thought)
                if text:
                    answer.append(text)
                    printer.answer(text)
            else:
                kind = event.get("type") or event_name
                if kind == "content_block_delta":
                    delta = event.get("delta") or {}
                    if delta.get("type") == "thinking_delta":
                        thought = str(delta.get("thinking") or "")
                        reasoning.append(thought)
                        printer.reasoning(thought)
                    elif delta.get("type") == "text_delta":
                        text = str(delta.get("text") or "")
                        answer.append(text)
                        printer.answer(text)
                elif kind == "message_stop":
                    completed = True
                    break
                elif kind == "error":
                    error = event.get("error") or {}
                    raise ApiFailure(502, str(error.get("message", "stream error")),
                                     str(error.get("type", "stream_error")), "".join(answer))
    except (OSError, http.client.HTTPException) as exc:
        raise StreamInterrupted(502, f"SSE 连接中断：{exc}", "sse_interrupted",
                                "".join(answer)) from exc
    if not completed:
        raise StreamInterrupted(502, "SSE 在终止事件前关闭", "sse_incomplete",
                                "".join(answer))
    return "".join(answer), "".join(reasoning)


def buffered_response(protocol: str, response: http.client.HTTPResponse,
                      printer: OutputPrinter) -> tuple[str, str]:
    raw = response.read()
    if response.status < 200 or response.status >= 300:
        raise decode_error(response.status, raw)
    try:
        payload = json.loads(raw.decode("utf-8"))
    except json.JSONDecodeError as exc:
        raise ApiFailure(502, "服务返回了无效 JSON", "invalid_response") from exc
    answer: list[str] = []
    reasoning: list[str] = []
    if protocol == "openai":
        choices = payload.get("choices") or []
        if choices:
            message = choices[0].get("message") or {}
            thought = str(message.get("reasoning_content") or "")
            text = str(message.get("content") or "")
            if thought:
                reasoning.append(thought)
                printer.reasoning(thought)
            if text:
                answer.append(text)
                printer.answer(text)
    else:
        for block in payload.get("content") or []:
            if block.get("type") == "thinking":
                value = str(block.get("thinking") or "")
                reasoning.append(value)
                printer.reasoning(value)
            elif block.get("type") == "text":
                value = str(block.get("text") or "")
                answer.append(value)
                printer.answer(value)
    return "".join(answer), "".join(reasoning)


def perform_request(protocol: str, base_url: str, key: str, model: str,
                    messages: list[dict[str, str]], system: str | None,
                    thinking: bool, stream: bool, timeout: float,
                    debug: bool, session_id: str | None = None) -> tuple[str, str]:
    scheme, host, port, path = endpoint_parts(base_url, protocol)
    connection_type = http.client.HTTPSConnection if scheme == "https" else http.client.HTTPConnection
    connection = connection_type(host, port, timeout=timeout)
    body = request_body(protocol, model, messages, system, thinking, stream, session_id)
    headers = {"Content-Type": "application/json"}
    if protocol == "openai":
        headers["Authorization"] = f"Bearer {key}"
    else:
        headers["x-api-key"] = key
        headers["Authorization"] = f"Bearer {key}"
        headers["anthropic-version"] = ANTHROPIC_VERSION
    if debug:
        print(f"[debug] POST {scheme}://{host}:{port}{path} protocol={protocol} "
              f"thinking={thinking} stream={stream}", file=sys.stderr)
    printer = OutputPrinter()
    try:
        connection.request("POST", path, body=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                           headers=headers)
        response = connection.getresponse()
        if response.status < 200 or response.status >= 300:
            raise decode_error(response.status, response.read())
        result = (stream_response(protocol, response, printer) if stream
                  else buffered_response(protocol, response, printer))
        printer.finish()
        return result
    finally:
        connection.close()


def ask_with_retry(args: argparse.Namespace, protocol: str, base_url: str, key: str,
                   explicit_key: bool, messages: list[dict[str, str]],
                   thinking: bool, session_id: str | None = None) -> tuple[str, str, str]:
    current_key = key
    attempts = max(0, args.retries) + 1
    for attempt in range(attempts):
        try:
            answer, reasoning = perform_request(
                protocol, base_url, current_key, args.model, messages, args.system,
                thinking, not args.non_stream, args.timeout, args.debug, session_id)
            return answer, reasoning, current_key
        except ApiFailure as exc:
            if exc.partial:
                raise
            if exc.status == 401 and not explicit_key:
                refreshed = read_gateway_info().get("api_key", "").strip()
                if refreshed and refreshed != current_key and attempt + 1 < attempts:
                    current_key = refreshed
                    print("[重试] 检测到密钥已变化，已重新读取当前 Key", file=sys.stderr)
                    continue
            transient = exc.status in {429, 502, 503, 504}
            if transient and attempt + 1 < attempts:
                delay = min(8, 2 ** attempt)
                print(f"[重试] {exc.code}: {exc}；{delay} 秒后重试", file=sys.stderr)
                time.sleep(delay)
                continue
            raise
    raise ApiFailure(500, "请求重试耗尽")


def print_config(protocol: str, base_url: str, model: str, thinking: bool) -> None:
    print(f"协议：{protocol}\n地址：{base_url}\n模型：{model}\n深度思考：{'开' if thinking else '关'}")


def interactive(args: argparse.Namespace, protocol: str, base_url: str, key: str,
                explicit_key: bool) -> int:
    messages: list[dict[str, str]] = []
    thinking = bool(args.think)
    session_id = new_session_id()
    print("DeepSeek 微型 Agent（流式）")
    print("命令：/think [on|off]、/clear、/new、/reset、/config、/help、/quit")
    print_config(protocol, base_url, args.model, thinking)
    while True:
        try:
            prompt = input("\n你> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0
        if not prompt:
            continue
        if prompt.startswith("/"):
            parts = prompt.split()
            command = parts[0].lower()
            if command in {"/quit", "/exit", "/q"}:
                return 0
            if command in {"/clear", "/new", "/reset"}:
                messages.clear()
                session_id = new_session_id()
                print("新对话已创建")
                continue
            if command == "/config":
                print_config(protocol, base_url, args.model, thinking)
                continue
            if command == "/help":
                print("/think 切换；/think on 开启；/think off 关闭；"
                      "/clear、/new、/reset 新建对话；/quit 退出")
                continue
            if command == "/think":
                if len(parts) > 1 and parts[1].lower() in {"on", "1", "true", "开"}:
                    thinking = True
                elif len(parts) > 1 and parts[1].lower() in {"off", "0", "false", "关"}:
                    thinking = False
                else:
                    thinking = not thinking
                print(f"深度思考已{'开启' if thinking else '关闭'}")
                continue
            print("未知命令；输入 /help 查看可用命令")
            continue

        messages.append({"role": "user", "content": prompt})
        print("AI> ", end="", flush=True)
        try:
            answer, _, key = ask_with_retry(
                args, protocol, base_url, key, explicit_key, messages, thinking, session_id)
            messages.append({"role": "assistant", "content": answer})
        except ApiFailure as exc:
            messages.pop()
            print(f"\n[错误 {exc.status}/{exc.code}] {exc}", file=sys.stderr)
            if exc.partial:
                print("[提示] 已收到部分内容，为避免重复执行，本轮不会自动重放。", file=sys.stderr)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="DeepSeek 本地 API 流式微型 Agent")
    parser.add_argument("prompt", nargs="*", help="直接发送后退出；省略则进入交互模式")
    parser.add_argument("--protocol", choices=("auto", "openai", "anthropic"), default="auto")
    parser.add_argument("--base-url", help="覆盖自动读取的本地 API 地址")
    parser.add_argument("--api-key", help="覆盖自动读取的 API Key")
    parser.add_argument("--model", default="deepseek-chat")
    parser.add_argument("--system", help="附加 system 提示")
    parser.add_argument("--think", action="store_true", help="首轮开启深度思考")
    parser.add_argument("--non-stream", action="store_true", help="使用普通 JSON 而不是 SSE")
    parser.add_argument("--timeout", type=float, default=190.0)
    parser.add_argument("--retries", type=int, default=1,
                        help="无任何输出时的客户端重试次数，默认 1")
    parser.add_argument("--debug", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        protocol, base_url, key, explicit_key = resolve_configuration(args)
    except ValueError as exc:
        print(f"配置错误：{exc}", file=sys.stderr)
        return 2
    if not args.prompt:
        return interactive(args, protocol, base_url, key, explicit_key)
    messages = [{"role": "user", "content": " ".join(args.prompt)}]
    try:
        ask_with_retry(args, protocol, base_url, key, explicit_key, messages,
                       bool(args.think), new_session_id())
        return 0
    except ApiFailure as exc:
        print(f"[错误 {exc.status}/{exc.code}] {exc}", file=sys.stderr)
        if exc.partial:
            print("[提示] SSE 已有部分输出，未自动重放，以免产生重复副作用。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
