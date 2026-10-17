"""Standard-library ADB client for Packet Capture's read-only v1 interface.

This client never sends a model prompt or retries a UI action. The task executor
owns that single-send boundary and persists the session/exchange correlation.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shlex
import subprocess
from typing import Any
from urllib.parse import quote, urlencode


class CaptureError(RuntimeError):
    pass


class PacketCaptureClient:
    def __init__(self, serial: str, adb: str = "adb", timeout: float = 40):
        if not serial or serial.startswith("-"):
            raise ValueError("An explicit device serial is required")
        self.serial, self.adb, self.timeout = serial, adb, timeout

    def _read(self, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        uri = "content://com.packetcapture.automation/v1/" + path
        if params:
            uri += "?" + urlencode({key: value for key, value in params.items() if value is not None})
        # adb forwards a shell command string to Android. Quote the whole URI on
        # that side as well as avoiding a shell on the Windows/Python side.
        command = "content read --uri " + shlex.quote(uri)
        try:
            completed = subprocess.run(
                [self.adb, "-s", self.serial, "exec-out", command],
                capture_output=True, timeout=self.timeout, check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise CaptureError("ADB unavailable or timed out") from exc
        if completed.returncode:
            raise CaptureError("ADB read failed; check device authorization and the export setting")
        try:
            result = json.loads(completed.stdout.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            # content can print an error with exit status 0; JSON is mandatory.
            raise CaptureError("No valid JSON; check Packet Capture installation and export setting") from exc
        if not isinstance(result, dict) or result.get("schemaVersion") != 1 or "error" in result:
            raise CaptureError("Unsupported schema or capture export unavailable")
        return result

    def status(self) -> dict[str, Any]:
        return self._read("status")

    def sessions(self) -> dict[str, Any]:
        return self._read("sessions")

    def answers(self, session_id: str, *, provider: str | None = None,
                prompt: str | None = None, started_after: int = 0,
                exchange_id: str | None = None) -> dict[str, Any]:
        if not session_id:
            raise ValueError("session_id is required")
        if started_after < 0:
            raise ValueError("started_after must be epoch milliseconds")
        return self._read("sessions/" + quote(session_id, safe="") + "/answers", {
            "provider": provider, "prompt": prompt, "startedAfter": started_after, "exchangeId": exchange_id,
        })

    @staticmethod
    def require_complete(batch: dict[str, Any]) -> dict[str, Any]:
        results = batch.get("results")
        if batch.get("schemaVersion") != 1 or batch.get("selectionStatus") != "unique_complete" or not isinstance(results, list) or len(results) != 1:
            raise CaptureError("Expected one complete answer; keep collecting without resending the prompt")
        answer = results[0]
        if not isinstance(answer, dict) or answer.get("status") != "complete" or not isinstance(answer.get("answer"), str) or not answer["answer"]:
            raise CaptureError("Invalid completed answer")
        return answer


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    sub.add_parser("sessions")
    answers = sub.add_parser("answers")
    answers.add_argument("--session", required=True)
    answers.add_argument("--provider")
    answers.add_argument("--prompt-file", type=Path, help="Exact UTF-8 prompt; no whitespace is stripped")
    answers.add_argument("--started-after", type=int, default=0)
    answers.add_argument("--exchange-id")
    answers.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()
    client = PacketCaptureClient(args.serial, args.adb)
    try:
        if args.command == "status":
            result = client.status()
        elif args.command == "sessions":
            result = client.sessions()
        else:
            prompt = args.prompt_file.read_text(encoding="utf-8") if args.prompt_file else None
            result = client.answers(args.session, provider=args.provider, prompt=prompt,
                                    started_after=args.started_after, exchange_id=args.exchange_id)
            if args.require_complete:
                client.require_complete(result)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (CaptureError, ValueError, OSError) as exc:
        parser.exit(1, f"{exc}\n")


if __name__ == "__main__":
    main()
