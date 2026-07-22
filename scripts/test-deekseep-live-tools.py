#!/usr/bin/env python3
"""Live function-call and tool-result round-trip for the local DeepSeek gateway.

The script never prints the API key and only exposes a deterministic in-process echo tool. It is
intended for device validation after the gateway has already passed offline protocol tests.
"""

from __future__ import annotations

import argparse
import http.client
import importlib.util
import json
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


AGENT_PATH = Path(__file__).with_name("deekseep-agent.py")
SPEC = importlib.util.spec_from_file_location("deekseep_agent_live", AGENT_PATH)
assert SPEC and SPEC.loader
agent = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(agent)

TOOL_NAME = "local_echo"
TOOL_ARGUMENT = "DEEKSEEP_TOOL_ARGUMENT_OK"
TOOL_RESULT = "DEEKSEEP_TOOL_RESULT_OK"


def headers(protocol: str, key: str) -> dict[str, str]:
    value = {"Content-Type": "application/json"}
    if protocol == "openai":
        value["Authorization"] = f"Bearer {key}"
    else:
        value["x-api-key"] = key
        value["Authorization"] = f"Bearer {key}"
        value["anthropic-version"] = agent.ANTHROPIC_VERSION
    return value


def connection(base_url: str) -> tuple[http.client.HTTPConnection, str]:
    parsed = urlparse(base_url)
    cls = http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
    conn = cls(parsed.hostname or "127.0.0.1", parsed.port or 80, timeout=190)
    return conn, parsed.path.rstrip("/")


def post(base_url: str, path: str, key: str, protocol: str,
         body: dict[str, Any]) -> http.client.HTTPResponse:
    conn, prefix = connection(base_url)
    # Keep the connection reachable through the response object until its body has been read.
    conn.request("POST", prefix + path,
                 body=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                 headers=headers(protocol, key))
    response = conn.getresponse()
    response._deekseep_connection = conn  # type: ignore[attr-defined]
    return response


def json_response(response: http.client.HTTPResponse) -> dict[str, Any]:
    raw = response.read()
    try:
        payload = json.loads(raw.decode("utf-8"))
    finally:
        response.close()
        getattr(response, "_deekseep_connection").close()
    if response.status < 200 or response.status >= 300:
        error = payload.get("error", payload) if isinstance(payload, dict) else payload
        raise RuntimeError(f"HTTP {response.status}: {error}")
    if not isinstance(payload, dict):
        raise RuntimeError("gateway returned a non-object JSON response")
    return payload


def sse_events(response: http.client.HTTPResponse) -> list[dict[str, Any]]:
    if response.status < 200 or response.status >= 300:
        return [json_response(response)]
    events: list[dict[str, Any]] = []
    try:
        for _name, data in agent._events(response):
            if not data or data == "[DONE]":
                continue
            value = json.loads(data)
            if isinstance(value, dict):
                events.append(value)
    finally:
        response.close()
        getattr(response, "_deekseep_connection").close()
    return events


def anthropic_round_trip(base_url: str, key: str, model: str) -> None:
    user = {"role": "user", "content":
            f"必须调用 {TOOL_NAME}，把 value 设为 {TOOL_ARGUMENT}，不要直接回答。"}
    tool = {"name": TOOL_NAME, "description": "Returns value unchanged.",
            "input_schema": {"type": "object", "properties": {
                "value": {"type": "string"}}, "required": ["value"]}}
    first = {"model": model, "max_tokens": 2048, "messages": [user], "tools": [tool],
             "tool_choice": {"type": "tool", "name": TOOL_NAME}, "stream": True}
    events = sse_events(post(base_url, "/v1/messages", key, "anthropic", first))
    blocks: dict[int, dict[str, Any]] = {}
    partial: dict[int, list[str]] = {}
    for event in events:
        kind = event.get("type")
        index = int(event.get("index", -1))
        if kind == "content_block_start" and isinstance(event.get("content_block"), dict):
            blocks[index] = dict(event["content_block"])
            partial[index] = []
        elif kind == "content_block_delta":
            delta = event.get("delta") or {}
            if delta.get("type") == "input_json_delta":
                partial.setdefault(index, []).append(str(delta.get("partial_json") or ""))
    call_index = next((i for i, block in blocks.items()
                       if block.get("type") == "tool_use"), None)
    if call_index is None:
        raise RuntimeError("Anthropic SSE did not contain a tool_use block")
    call = blocks[call_index]
    arguments = json.loads("".join(partial.get(call_index, [])) or "{}")
    if call.get("name") != TOOL_NAME or arguments.get("value") != TOOL_ARGUMENT:
        raise RuntimeError(f"unexpected Anthropic tool call: {call.get('name')} {arguments}")
    assistant_content = []
    for index in sorted(blocks):
        block = blocks[index]
        if block.get("type") == "tool_use":
            assistant_content.append({"type": "tool_use", "id": block["id"],
                                      "name": block["name"], "input": arguments})
    followup = {"model": model, "max_tokens": 2048, "tools": [tool],
                "tool_choice": {"type": "none"}, "stream": False,
                "messages": [user, {"role": "assistant", "content": assistant_content},
                             {"role": "user", "content": [{"type": "tool_result",
                                 "tool_use_id": call["id"], "content": TOOL_RESULT}]}]}
    payload = json_response(post(base_url, "/v1/messages", key, "anthropic", followup))
    text = "".join(str(block.get("text") or "") for block in payload.get("content", [])
                   if isinstance(block, dict) and block.get("type") == "text")
    if not text:
        raise RuntimeError("Anthropic follow-up did not return text after tool_result")
    print("Anthropic SSE tool_use + tool_result round-trip OK")


def openai_round_trip(base_url: str, key: str, model: str) -> None:
    user = {"role": "user", "content":
            f"必须调用 {TOOL_NAME}，把 value 设为 {TOOL_ARGUMENT}，不要直接回答。"}
    tool = {"type": "function", "function": {"name": TOOL_NAME,
            "description": "Returns value unchanged.", "parameters": {"type": "object",
                "properties": {"value": {"type": "string"}}, "required": ["value"]}}}
    first = {"model": model, "messages": [user], "tools": [tool],
             "tool_choice": {"type": "function", "function": {"name": TOOL_NAME}},
             "stream": True}
    events = sse_events(post(base_url, "/chat/completions", key, "openai", first))
    calls: dict[int, dict[str, str]] = {}
    for event in events:
        choices = event.get("choices") or []
        delta = choices[0].get("delta", {}) if choices else {}
        for item in delta.get("tool_calls") or []:
            index = int(item.get("index", 0))
            call = calls.setdefault(index, {"id": "", "name": "", "arguments": ""})
            call["id"] += str(item.get("id") or "")
            function = item.get("function") or {}
            call["name"] += str(function.get("name") or "")
            call["arguments"] += str(function.get("arguments") or "")
    if not calls:
        raise RuntimeError("OpenAI SSE did not contain tool_calls")
    call = calls[min(calls)]
    arguments = json.loads(call["arguments"] or "{}")
    if call["name"] != TOOL_NAME or arguments.get("value") != TOOL_ARGUMENT:
        raise RuntimeError(f"unexpected OpenAI tool call: {call['name']} {arguments}")
    assistant = {"role": "assistant", "content": None, "tool_calls": [{"id": call["id"],
        "type": "function", "function": {"name": call["name"],
                                            "arguments": call["arguments"]}}]}
    followup = {"model": model, "messages": [user, assistant,
                {"role": "tool", "tool_call_id": call["id"], "content": TOOL_RESULT}],
                "tools": [tool], "tool_choice": "none", "stream": False}
    payload = json_response(post(base_url, "/chat/completions", key, "openai", followup))
    choices = payload.get("choices") or []
    text = str((choices[0].get("message") or {}).get("content") or "") if choices else ""
    if not text:
        raise RuntimeError("OpenAI follow-up did not return text after tool result")
    print("OpenAI SSE tool_call + tool result round-trip OK")


def openai_responses_round_trip(base_url: str, key: str, model: str) -> None:
    tool = {"type": "function", "name": TOOL_NAME,
            "description": "Returns value unchanged.", "parameters": {"type": "object",
                "properties": {"value": {"type": "string"}}, "required": ["value"]}}
    first = {"model": model,
             "input": f"必须调用 {TOOL_NAME}，把 value 设为 {TOOL_ARGUMENT}。",
             "tools": [tool], "tool_choice": {"type": "function", "name": TOOL_NAME},
             "parallel_tool_calls": False, "stream": True}
    events = sse_events(post(base_url, "/responses", key, "openai", first))
    completed = next((event.get("response") for event in reversed(events)
                      if event.get("type") == "response.completed"), None)
    if not isinstance(completed, dict):
        raise RuntimeError("Responses SSE did not contain response.completed")
    output = completed.get("output") or []
    call = next((item for item in output if isinstance(item, dict)
                 and item.get("type") == "function_call"), None)
    if call is None:
        raise RuntimeError("Responses SSE did not contain function_call output")
    arguments = json.loads(str(call.get("arguments") or "{}"))
    if call.get("name") != TOOL_NAME or arguments.get("value") != TOOL_ARGUMENT:
        raise RuntimeError(f"unexpected Responses function call: {call.get('name')} {arguments}")
    followup = {"model": model, "previous_response_id": completed["id"], "stream": False,
                "input": [{"type": "function_call_output", "call_id": call["call_id"],
                           "output": TOOL_RESULT}]}
    payload = json_response(post(base_url, "/responses", key, "openai", followup))
    text = ""
    for item in payload.get("output") or []:
        if not isinstance(item, dict) or item.get("type") != "message":
            continue
        for part in item.get("content") or []:
            if isinstance(part, dict) and part.get("type") == "output_text":
                text += str(part.get("text") or "")
    if not text:
        raise RuntimeError("Responses follow-up did not return text after function_call_output")
    print("OpenAI Responses SSE function_call + previous_response_id output OK")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--protocol", choices=("auto", "openai", "anthropic"), default="auto")
    parser.add_argument("--model", default="deepseek-chat")
    args = parser.parse_args()
    info = agent.read_gateway_info()
    protocol = info.get("protocol", "openai") if args.protocol == "auto" else args.protocol
    key = info.get("api_key", "")
    if not key:
        raise SystemExit("No local API key found")
    root = agent.normalize_root(info.get("base_url") or agent.DEFAULT_ROOT)
    if protocol == "anthropic":
        anthropic_round_trip(root, key, args.model)
    else:
        openai_round_trip(root + "/v1", key, args.model)
        openai_responses_round_trip(root + "/v1", key, args.model)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
