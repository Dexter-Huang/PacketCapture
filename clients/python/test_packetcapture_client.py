import json
import subprocess
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse
import shlex

from packetcapture_client import CaptureError, PacketCaptureClient


class ClientTests(unittest.TestCase):
    def test_unicode_and_shell_characters_round_trip(self):
        prompt = "中文 ' & $(echo secret)\n流式响应"
        with patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0, b'{"schemaVersion":1}', b"")) as run:
            PacketCaptureClient("test-device").answers("session", provider="yuanbao", prompt=prompt)
        argv = run.call_args.args[0]
        self.assertEqual(argv[:4], ["adb", "-s", "test-device", "exec-out"])
        remote = shlex.split(argv[4])
        self.assertEqual(remote[:3], ["content", "read", "--uri"])
        self.assertEqual(parse_qs(urlparse(remote[3]).query)["prompt"], [prompt])
        self.assertNotIn("shell", run.call_args.kwargs)

    def test_error_text_with_zero_exit_is_not_an_answer(self):
        with patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0, b"Permission denied", b"")):
            with self.assertRaises(CaptureError):
                PacketCaptureClient("test-device").status()

    def test_error_object_and_unknown_schema_rejected(self):
        for result in ({"schemaVersion": 1, "error": "unavailable"}, {"schemaVersion": 2}):
            with patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0, json.dumps(result).encode(), b"")):
                with self.assertRaises(CaptureError):
                    PacketCaptureClient("test-device").status()

    def test_ambiguous_or_partial_never_accepted(self):
        for state, results in (("ambiguous", [{"status": "complete", "answer": "a"}] * 2),
                               ("unique_incomplete", [{"status": "incomplete", "answer": "partial"}]),
                               ("none", []), ("unique_complete", [])):
            with self.assertRaises(CaptureError):
                PacketCaptureClient.require_complete({"schemaVersion": 1, "selectionStatus": state, "results": results})
        result = {"status": "complete", "answer": "完整答案"}
        self.assertEqual(result, PacketCaptureClient.require_complete({
            "schemaVersion": 1, "selectionStatus": "unique_complete", "results": [result]}))

    def test_timeout_does_not_retry(self):
        with patch("subprocess.run", side_effect=subprocess.TimeoutExpired("adb", 1)) as run:
            with self.assertRaises(CaptureError):
                PacketCaptureClient("test-device").status()
        self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
