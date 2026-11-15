#!/usr/bin/env python3
"""Explicit, credential-safe live protocol checks. Dry-run is the default; no APK credential injection."""
from __future__ import annotations
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import signal
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ALLOWED_ACTIONS = {"happy", "calm", "curious", "cuddle", "wave"}
SECRET_NAME = "CUTEDOG_DEEPSEEK_API_KEY"
MAX_RESPONSE = 262_144
PROMPTS = [
    ("chinese_companionship", "这是公开接口验收的虚构测试情境：我叫小林，正在准备中文阅读考试，这周六想和朋友去图书馆。今天先和我打个招呼吧。"),
    ("multi_turn_context", "我刚才说周六想去哪里，和谁一起？只回答这些已知事实，不要猜测。"),
    ("emotional_response", "今天复习了很久，有点累也有点烦。先听我说两句，不用急着夸我或给一大段大道理。"),
    ("practical_help", "现在给我一个轻量的25分钟阅读复习安排，包含短暂休息。不要声称已经替我创建系统提醒。"),
    ("validated_action_intent", "来抱抱，陪我休息一下。如果适合，请在文字末尾使用约定的一个动作标记。"),
]

def endpoint_url(base: str) -> str:
    base = base.strip().rstrip("/")
    url = urllib.parse.urlsplit(base)
    if url.scheme != "https" or url.hostname != "api.deepseek.com" or url.username or url.password or url.query or url.fragment:
        raise ValueError("Only the explicitly authorized HTTPS api.deepseek.com service is supported; no credentials in URLs.")
    if url.port not in (None, 443):
        raise ValueError("Use the provider's standard HTTPS port.")
    return base if base.endswith("/chat/completions") else base + "/chat/completions"

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Authorization is never forwarded to another address.

def safe_identifier(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    return value[:128] if re.fullmatch(r"[\w.:/-]{1,128}", value) else None

def system_prompt() -> str:
    source = Path(__file__).resolve().parent.parent / "app/src/main/java/com/cutedog/app/chat/ChatPolicy.kt"
    match = re.search(r'const val SYSTEM_PROMPT = """(.*?)"""', source.read_text(), re.S)
    if not match:
        raise ValueError("Cannot locate the app's actual system persona.")
    return match.group(1) + "\n你的显示名字是糯米。本轮是虚构测试输入，不包含真实用户隐私。"

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="Actually send five synthetic prompt requests; the provider may charge usage fees.")
    parser.add_argument("--base-url", default=os.environ.get("CUTEDOG_AI_BASE_URL", "https://api.deepseek.com"))
    parser.add_argument("--model", default=os.environ.get("CUTEDOG_AI_MODEL", "deepseek-flash"), help="Exact requested model ID. No model substitution or retry is performed.")
    parser.add_argument("--output", type=Path, default=Path("artifacts/live-ai-verification.json"))
    args = parser.parse_args()
    endpoint = endpoint_url(args.base_url)
    if not args.model or len(args.model) > 128 or any(ord(c) < 32 for c in args.model):
        raise ValueError("Invalid model identifier.")
    present = bool(os.environ.get(SECRET_NAME))
    if not args.execute:
        print(json.dumps({"mode": "dry_run", "networkRequests": 0, "endpoint": endpoint, "exactRequestedModel": args.model,
                          "credentialVariable": SECRET_NAME, "credentialPresent": present,
                          "prompts": [name for name, _ in PROMPTS], "note": "A proxy credential placeholder is accepted when bound; values are never printed."}, ensure_ascii=False, indent=2))
        return 0
    if not present:
        print(f"Blocked: configure {SECRET_NAME} securely in environment settings, then retry. No request sent.", file=sys.stderr)
        return 2
    key = os.environ[SECRET_NAME]
    if len(key) > 4096 or any(ord(c) < 32 for c in key):
        raise ValueError("Credential binding contains unsupported header characters.")
    # Keep the platform's HTTPS proxy and maintained CA trust. No certificate verification bypass.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler(), urllib.request.HTTPSHandler(context=ssl.create_default_context()), NoRedirect())
    messages = [{"role": "system", "content": system_prompt()}]
    evidence = {"schemaVersion": 1, "startedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "service": endpoint,
                "requestedModel": args.model, "inputType": "synthetic non-private acceptance prompts",
                "credentialSource": SECRET_NAME, "checks": [], "protocolStatus": "running", "conversationQuality": "requires_human_review"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        raise ValueError("Choose a new output path; prior live evidence is never overwritten.")
    def persist() -> None:
        encoded = json.dumps(evidence, ensure_ascii=False, indent=2)
        args.output.write_text(encoded.replace(key, "[REDACTED]") + "\n")
    persist()
    for name, prompt in PROMPTS:
        messages.append({"role": "user", "content": prompt})
        payload = json.dumps({"model": args.model, "messages": messages, "max_tokens": 800, "stream": False}, ensure_ascii=False).encode()
        req = urllib.request.Request(endpoint, data=payload, headers={"Authorization": "Bearer " + key, "Content-Type": "application/json; charset=utf-8", "Accept": "application/json"}, method="POST")
        started = time.monotonic()
        check: dict = {"name": name, "prompt": prompt, "status": "failed"}
        def expired(_signal, _frame):
            raise TimeoutError("Live request deadline reached")
        previous_handler = signal.signal(signal.SIGALRM, expired)
        signal.setitimer(signal.ITIMER_REAL, 60)
        try:
            with opener.open(req, timeout=60) as response:
                raw = response.read(MAX_RESPONSE + 1)
                request_id = safe_identifier(response.headers.get("x-request-id"))
                if len(raw) > MAX_RESPONSE:
                    raise ValueError("Provider response exceeded the 256 KiB protocol limit.")
                data = json.loads(raw)
            if not isinstance(data, dict):
                raise ValueError("Provider response must be a JSON object.")
            choices = data.get("choices")
            if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
                raise ValueError("Provider did not return Chat Completions choices.")
            message = choices[0].get("message")
            text = message.get("content") if isinstance(message, dict) else None
            if not isinstance(text, str) or not text.strip() or len(text) > 20_000:
                raise ValueError("Provider did not return usable bounded text.")
            suffix = re.search(r"\s*<action:([a-z_]+)>\s*$", text)
            action = suffix.group(1) if suffix else None
            if action is not None and action not in ALLOWED_ACTIONS:
                raise ValueError("Provider returned an unsupported action intent; the app would discard it.")
            cleaned = text[:suffix.start()].strip() if suffix else text.strip()
            if not cleaned:
                raise ValueError("Only an action marker was returned, without companion text.")
            # Responses use synthetic prompts only; never persist auth headers or credential values.
            if key in cleaned:
                raise ValueError("Response unexpectedly contained credential material; response omitted.")
            usage = data.get("usage")
            if not isinstance(usage, dict):
                usage = {}
            check.update({"status": "passed", "requestId": request_id or safe_identifier(data.get("id")),
                          "returnedModel": safe_identifier(data.get("model")), "answer": cleaned, "actionIntent": action,
                          "milliseconds": round((time.monotonic() - started) * 1000),
                          "usage": {k: v for k, v in usage.items() if k in ("prompt_tokens", "completion_tokens", "total_tokens") and isinstance(v, int)}})
            if name == "multi_turn_context":
                check["knownContextMentioned"] = "图书馆" in cleaned and "朋友" in cleaned
                if not check["knownContextMentioned"]:
                    check["status"] = "failed"
                    check["error"] = "The answer did not retain both explicitly supplied context facts."
            messages.append({"role": "assistant", "content": text})
        except urllib.error.HTTPError as error:
            check.update({"httpStatus": error.code, "requestId": safe_identifier(error.headers.get("x-request-id")),
                          "error": "Provider rejected the exact configured model/request. Check account, model availability and quota; no fallback model was used."})
            error.close()  # Never persist a provider error body that may echo credential details.
        except (urllib.error.URLError, OSError):
            check["error"] = "Network/TLS/timeout failure; system certificate validation remains enabled."
        except (ValueError, KeyError, TypeError, UnicodeError) as error:
            # These messages are authored above or generic parser descriptions, never response bodies.
            check["error"] = "Protocol validation failed: " + (str(error) if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError) else "invalid response schema")
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            signal.signal(signal.SIGALRM, previous_handler)
        evidence["checks"].append(check)
        persist()
        print(json.dumps({"name": name, "status": check["status"], "httpStatus": check.get("httpStatus"), "requestId": check.get("requestId")}, ensure_ascii=False).replace(key, "[REDACTED]"))
        if check["status"] != "passed":
            evidence["protocolStatus"] = "blocked" if check.get("httpStatus") in (401, 403, 404) else "failed"
            evidence["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
            persist()
            return 1
    evidence["protocolStatus"] = "passed"
    evidence["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
    persist()
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as error:
        print("Configuration error: " + str(error), file=sys.stderr)
        raise SystemExit(2)
