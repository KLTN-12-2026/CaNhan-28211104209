#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kiem thu tu dong cho script import_locations_pilot.py (F06):
- Kiem thu pre-validation toan bo dataset truoc mutation.
- Kiem thu so sanh metadata & phat hien conflict (conflict-safe).
- Kiem thu dry-run, idempotence va auth failure qua mock HTTP server.
"""

import http.server
import io
import json
import math
import sys
import tempfile
import threading
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import import_locations_pilot
from import_locations_pilot import (
    validate_dataset,
    metadata_matches,
    import_pilot_locations,
)


class MockBackendHandler(http.server.BaseHTTPRequestHandler):
    fail_location_at = None
    categories = [{"id": "cat-1", "name": "Bảo tàng & Văn hóa"}]
    locations = []
    auth_token = "valid-admin-token"

    def do_GET(self):
        auth = self.headers.get("Authorization", "")
        if auth != f"Bearer {self.auth_token}":
            self.send_response(401)
            self.end_headers()
            self.wfile.write(b'{"error": "Unauthorized"}')
            return

        if self.path == "/api/v1/admin/location-categories":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(self.categories).encode("utf-8"))
        elif self.path == "/api/v1/admin/locations":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(self.locations).encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        auth = self.headers.get("Authorization", "")
        if auth != f"Bearer {self.auth_token}":
            self.send_response(401)
            self.end_headers()
            self.wfile.write(b'{"error": "Unauthorized"}')
            return

        content_len = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(content_len).decode("utf-8"))

        if self.path == "/api/v1/admin/location-categories":
            new_cat = {"id": f"cat-{len(self.categories)+1}", "name": body["name"]}
            self.categories.append(new_cat)
            self.send_response(201)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(new_cat).encode("utf-8"))
        elif self.path == "/api/v1/admin/locations":
            if len(self.locations) == self.fail_location_at:
                self.send_response(503)
                self.end_headers()
                self.wfile.write(b'{"error":"Injected partial failure"}')
                return
            new_loc = dict(body)
            new_loc["id"] = f"loc-{len(self.locations)+1}"
            self.locations.append(new_loc)
            self.send_response(201)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(new_loc).encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass  # Tat log server khi chay test


class ImportLocationsPilotTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        MockBackendHandler.categories = [{"id": "cat-1", "name": "Bảo tàng & Văn hóa"}]
        MockBackendHandler.locations = []
        cls.server = http.server.HTTPServer(("127.0.0.1", 0), MockBackendHandler)
        cls.port = cls.server.server_port
        cls.base_url = f"http://127.0.0.1:{cls.port}"
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def test_01_pilot_json_pre_validation(self):
        json_path = Path(__file__).parent / "locations-pilot.json"
        with open(json_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        errors = validate_dataset(data)
        self.assertEqual(errors, [], f"Dataset pilot hop le phai co 0 loi validation: {errors}")

    def test_02_validation_catches_invalid_coords_and_duplicates(self):
        bad_data = {
            "schemaVersion": "kdrive-locations-pilot-v1",
            "locations": [
                {"name": "Điểm 1", "categoryName": "Cat", "latitude": 21.0, "longitude": 105.8},  # Ha Noi (ngoai Da Nang)
                {"name": "Điểm 1", "categoryName": "Cat", "latitude": 16.0, "longitude": 108.2},  # Duplicate name
                {"name": "Điểm 3", "categoryName": "Cat", "latitude": 16.0, "longitude": 108.2, "metadata": {"timezone": "UTC"}},  # Bad tz
                {"name": "Điểm 4", "categoryName": "Cat", "latitude": 16.0, "longitude": 108.2, "metadata": {"timezone": "Asia/Ho_Chi_Minh", "durationMinutes": 60}} # Missing evidence
            ]
        }
        errors = validate_dataset(bad_data)
        self.assertTrue(len(errors) >= 4)
        self.assertTrue(any("ngoai vung" in e for e in errors))
        self.assertTrue(any("trung lap" in e for e in errors))
        self.assertTrue(any("Asia/Ho_Chi_Minh" in e for e in errors))
        self.assertTrue(any("durationEvidence" in e for e in errors))

    def test_03_metadata_matching(self):
        m1 = {"timezone": "Asia/Ho_Chi_Minh", "durationMinutes": 60, "hoursEvidence": {"url": "https://a.test"}}
        m2 = {"timezone": "Asia/Ho_Chi_Minh", "durationMinutes": 60, "hoursEvidence": {"url": "https://a.test"}}
        m_diff = {"timezone": "Asia/Ho_Chi_Minh", "durationMinutes": 90, "hoursEvidence": {"url": "https://a.test"}}
        self.assertTrue(metadata_matches(m1, m2))
        self.assertFalse(metadata_matches(m1, m_diff))
        self.assertFalse(metadata_matches(m1, None))

    def test_04_auth_failure_reported(self):
        json_path = Path(__file__).parent / "locations-pilot.json"
        res = import_pilot_locations(json_path, self.base_url, token="invalid-token", dry_run=False)
        self.assertTrue(len(res["errors"]) > 0)
        self.assertTrue("401" in res["errors"][0])

    def test_05_dry_run_creates_nothing(self):
        json_path = Path(__file__).parent / "locations-pilot.json"
        res = import_pilot_locations(json_path, self.base_url, token="valid-admin-token", dry_run=True)
        self.assertTrue(res["dryRun"])
        self.assertTrue(res["validationSuccess"])
        self.assertEqual(len(MockBackendHandler.locations), 0, "Dry-run tuyet doi khong ghi locations vao server.")
        self.assertEqual(len(res["locationsCreated"]), 8)

    def test_06_apply_first_run_then_second_run_idempotent(self):
        json_path = Path(__file__).parent / "locations-pilot.json"
        # Run 1: Apply thuc te
        res1 = import_pilot_locations(json_path, self.base_url, token="valid-admin-token", dry_run=False)
        self.assertEqual(len(res1["errors"]), 0)
        self.assertEqual(len(res1["locationsCreated"]), 8)
        self.assertEqual(len(MockBackendHandler.locations), 8)

        # Run 2: Idempotent apply lan 2 -> Tat ca phai duoc bo qua (skipped)
        res2 = import_pilot_locations(json_path, self.base_url, token="valid-admin-token", dry_run=False)
        self.assertEqual(len(res2["errors"]), 0)
        self.assertEqual(len(res2["locationsCreated"]), 0, "Lan 2 khong duoc tao them bat ky ban ghi nao.")
        self.assertEqual(len(res2["locationsSkipped"]), 8, "Toan bo 8 ban ghi phai duoc skipped vi da ton tai trung khop.")
        self.assertEqual(len(MockBackendHandler.locations), 8)

    def test_07_conflict_detection_when_server_facts_drift(self):
        json_path = Path(__file__).parent / "locations-pilot.json"
        # Thay doi toa do cua 1 dia diem tren mock server de gia lap facts bi drift
        orig_lat = MockBackendHandler.locations[0]["latitude"]
        MockBackendHandler.locations[0]["latitude"] = orig_lat + 0.000001

        res = import_pilot_locations(json_path, self.base_url, token="valid-admin-token", dry_run=False)
        self.assertEqual(len(res["locationsConflict"]), 1, "Phai phat hien conflict do toa do bi drift.")
        self.assertEqual(len(res["locationsSkipped"]), 7)

        # Restore
        MockBackendHandler.locations[0]["latitude"] = orig_lat

    def test_11_malformed_dataset_is_rejected_before_http(self):
        source = Path(__file__).parent / "locations-pilot.json"
        data = json.loads(source.read_text(encoding="utf-8"))
        data["locations"][-1]["metadata"]["weekly"]["MONDAY"] = {
            "state": "INTERVALS",
            "intervals": [{"start": "99:00", "end": "17:00", "nextDay": False}],
        }
        data["locations"][0]["latitude"] = True
        data["locations"][1]["status"] = []

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text(json.dumps(data), encoding="utf-8")
            with patch("import_locations_pilot.http_request") as request:
                report = import_pilot_locations(path, "http://127.0.0.1:1", "admin-token", dry_run=False)

        self.assertFalse(report["validationSuccess"])
        self.assertTrue(any("vi do" in error for error in report["errors"]))
        self.assertTrue(any("status" in error for error in report["errors"]))
        self.assertTrue(any("start" in error for error in report["errors"]))
        request.assert_not_called()

    def test_12_non_object_values_do_not_crash_validation(self):
        data = json.loads((Path(__file__).parent / "locations-pilot.json").read_text(encoding="utf-8"))
        data["locations"][0]["metadata"]["weekly"]["MONDAY"]["state"] = []
        data["locations"][0]["metadata"]["exceptions"] = {"not-a-date": {"state": "CLOSED", "intervals": []}}

        errors = validate_dataset(data)

        self.assertTrue(any("state" in error for error in errors))
        self.assertTrue(any("ngay exception" in error for error in errors))

    def test_08_adverse_probe_metadata_matches_weekly_and_exceptions(self):
        m_base = {
            "timezone": "Asia/Ho_Chi_Minh",
            "durationMinutes": 60,
            "hoursEvidence": {"url": "https://a.test", "verifiedAt": "2026-10-01T00:00:00Z"},
            "weekly": {
                "MONDAY": {"state": "INTERVALS", "intervals": [{"start": "08:00", "end": "17:00", "nextDay": False}]}
            },
            "exceptions": {}
        }
        # 1. Đổi weekly
        m_diff_weekly = {
            "timezone": "Asia/Ho_Chi_Minh",
            "durationMinutes": 60,
            "hoursEvidence": {"url": "https://a.test", "verifiedAt": "2026-10-01T00:00:00Z"},
            "weekly": {
                "MONDAY": {"state": "INTERVALS", "intervals": [{"start": "08:00", "end": "18:00", "nextDay": False}]}
            },
            "exceptions": {}
        }
        self.assertFalse(metadata_matches(m_base, m_diff_weekly), "Doi weekly phai tra ve False.")

        # 2. Đổi exceptions
        m_diff_exceptions = {
            "timezone": "Asia/Ho_Chi_Minh",
            "durationMinutes": 60,
            "hoursEvidence": {"url": "https://a.test", "verifiedAt": "2026-10-01T00:00:00Z"},
            "weekly": {
                "MONDAY": {"state": "INTERVALS", "intervals": [{"start": "08:00", "end": "17:00", "nextDay": False}]}
            },
            "exceptions": {
                "2026-11-20": {"state": "CLOSED", "intervals": []}
            }
        }
        self.assertFalse(metadata_matches(m_base, m_diff_exceptions), "Them ngay ngoai le phai tra ve False.")

        # 3. Đổi verifiedAt
        m_diff_evidence = {
            "timezone": "Asia/Ho_Chi_Minh",
            "durationMinutes": 60,
            "hoursEvidence": {"url": "https://a.test", "verifiedAt": "2026-10-15T00:00:00Z"},
            "weekly": {
                "MONDAY": {"state": "INTERVALS", "intervals": [{"start": "08:00", "end": "17:00", "nextDay": False}]}
            },
            "exceptions": {}
        }
        self.assertFalse(metadata_matches(m_base, m_diff_evidence), "Doi verifiedAt phai tra ve False.")

    def test_09_metadata_conflict_triggers_conflict_report(self):
        json_path = Path(__file__).parent / "locations-pilot.json"
        # Thay đổi weekly của địa điểm thứ nhất trên mock server
        orig_meta = MockBackendHandler.locations[0]["metadata"]
        altered_meta = dict(orig_meta) if orig_meta else {}
        altered_meta["weekly"] = {"MONDAY": {"state": "CLOSED", "intervals": []}}
        MockBackendHandler.locations[0]["metadata"] = altered_meta

        res = import_pilot_locations(json_path, self.base_url, token="valid-admin-token", dry_run=False)
        self.assertEqual(len(res["locationsConflict"]), 1, "Phai phat hien conflict do metadata weekly bi thay doi.")
        self.assertTrue("metadata/hours facts diff" in res["locationsConflict"][0]["reason"])

        # Restore
        MockBackendHandler.locations[0]["metadata"] = orig_meta

    def test_10_partial_failure_then_safe_rerun(self):
        previous = MockBackendHandler.locations
        MockBackendHandler.locations = []
        MockBackendHandler.fail_location_at = 3
        json_path = Path(__file__).parent / "locations-pilot.json"
        try:
            failed = import_pilot_locations(json_path, self.base_url, "valid-admin-token", dry_run=False)
            self.assertEqual(len(failed["locationsCreated"]), 3)
            self.assertTrue(failed["errors"])
            self.assertEqual(len(MockBackendHandler.locations), 3)
            first_ids = [loc["id"] for loc in MockBackendHandler.locations]
            MockBackendHandler.fail_location_at = None
            recovered = import_pilot_locations(json_path, self.base_url, "valid-admin-token", dry_run=False)
            self.assertEqual(recovered["errors"], [])
            self.assertEqual(len(recovered["locationsCreated"]), 5)
            self.assertEqual(len(recovered["locationsSkipped"]), 3)
            self.assertEqual(len(MockBackendHandler.locations), 8)
            self.assertEqual([loc["id"] for loc in MockBackendHandler.locations[:3]], first_ids)
        finally:
            MockBackendHandler.locations = previous
            MockBackendHandler.fail_location_at = None
    def test_13_invalid_boundary_schedules_return_field_path_errors(self):
        source = json.loads((Path(__file__).parent / "locations-pilot.json").read_text(encoding="utf-8"))
        cases = []

        empty_next_day = json.loads(json.dumps(source))
        weekly = empty_next_day["locations"][0]["metadata"]["weekly"]
        weekly["MONDAY"] = {
            "state": "INTERVALS",
            "intervals": [{"start": "23:00", "end": "01:00", "nextDay": True}],
        }
        weekly["TUESDAY"] = {"state": "INTERVALS", "intervals": []}
        cases.append((empty_next_day, "locations[0].weekly.TUESDAY"))

        bad_time_next_day = json.loads(json.dumps(source))
        weekly = bad_time_next_day["locations"][0]["metadata"]["weekly"]
        weekly["MONDAY"] = {
            "state": "INTERVALS",
            "intervals": [{"start": "23:00", "end": "01:00", "nextDay": True}],
        }
        weekly["TUESDAY"] = {
            "state": "INTERVALS",
            "intervals": [{"start": "invalid", "end": "03:00", "nextDay": False}],
        }
        cases.append((bad_time_next_day, "locations[0].weekly.TUESDAY.intervals[0].start"))

        bad_next_day_type = json.loads(json.dumps(source))
        bad_next_day_type["locations"][0]["metadata"]["weekly"]["MONDAY"] = {
            "state": "INTERVALS",
            "intervals": [{"start": "23:00", "end": "01:00", "nextDay": "true"}],
        }
        cases.append((bad_next_day_type, "locations[0].weekly.MONDAY.intervals[0].nextDay"))

        empty_exception = json.loads(json.dumps(source))
        exceptions = empty_exception["locations"][0]["metadata"]["exceptions"]
        exceptions["2026-11-08"] = {
            "state": "INTERVALS",
            "intervals": [{"start": "23:00", "end": "01:00", "nextDay": True}],
        }
        exceptions["2026-11-09"] = {"state": "INTERVALS", "intervals": []}
        cases.append((empty_exception, "locations[0].exceptions.2026-11-09"))

        for data, field_path in cases:
            with self.subTest(field_path=field_path):
                errors = validate_dataset(data)
                self.assertTrue(any(field_path in error for error in errors), errors)

    def test_14_non_finite_coordinates_are_rejected_with_field_paths(self):
        source = json.loads((Path(__file__).parent / "locations-pilot.json").read_text(encoding="utf-8"))
        for field in ("latitude", "longitude"):
            for value in (math.nan, math.inf, -math.inf):
                with self.subTest(field=field, value=value):
                    data = json.loads(json.dumps(source))
                    data["locations"][0][field] = value
                    errors = validate_dataset(data)
                    self.assertTrue(
                        any(f"locations[0].{field}" in error for error in errors),
                        errors,
                    )

    def test_15_invalid_cli_report_exits_before_any_http_request(self):
        data = json.loads((Path(__file__).parent / "locations-pilot.json").read_text(encoding="utf-8"))
        weekly = data["locations"][0]["metadata"]["weekly"]
        weekly["MONDAY"] = {
            "state": "INTERVALS",
            "intervals": [{"start": "23:00", "end": "01:00", "nextDay": True}],
        }
        weekly["TUESDAY"] = {"state": "INTERVALS", "intervals": []}
        data["locations"][0]["latitude"] = math.nan
        data["locations"][1]["longitude"] = math.inf

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text(json.dumps(data, ensure_ascii=False, allow_nan=True), encoding="utf-8")
            output = io.StringIO()
            arguments = [
                "import_locations_pilot.py",
                "--json", str(path),
                "--base-url", self.base_url,
                "--token", "valid-admin-token",
                "--apply",
            ]
            with patch.object(sys, "argv", arguments), patch.object(import_locations_pilot, "http_request") as request:
                with redirect_stdout(output), self.assertRaises(SystemExit) as exit_info:
                    import_locations_pilot.main()

        self.assertEqual(exit_info.exception.code, 1)
        report = json.loads(output.getvalue())
        self.assertFalse(report["validationSuccess"])
        self.assertTrue(any("locations[0].weekly.TUESDAY" in error for error in report["errors"]))
        self.assertTrue(any("locations[0].latitude" in error for error in report["errors"]))
        self.assertTrue(any("locations[1].longitude" in error for error in report["errors"]))
        request.assert_not_called()

    def test_16_valid_overnight_unknown_closed_and_nonoverlapping_hours_remain_valid(self):
        source = json.loads((Path(__file__).parent / "locations-pilot.json").read_text(encoding="utf-8"))
        metadata = source["locations"][0]["metadata"]
        metadata["hoursEvidence"] = {
            "url": "https://example.test/hours",
            "verifiedAt": "2026-10-01T00:00:00Z",
        }
        metadata["weekly"]["MONDAY"] = {
            "state": "INTERVALS",
            "intervals": [
                {"start": "08:00", "end": "10:00", "nextDay": False},
                {"start": "10:00", "end": "12:00", "nextDay": False},
                {"start": "23:00", "end": "01:00", "nextDay": True},
            ],
        }
        metadata["weekly"]["TUESDAY"] = {
            "state": "INTERVALS",
            "intervals": [{"start": "01:00", "end": "03:00", "nextDay": False}],
        }
        metadata["weekly"]["WEDNESDAY"] = {"state": "CLOSED", "intervals": []}
        metadata["weekly"]["THURSDAY"] = {"state": "UNKNOWN", "intervals": []}

        self.assertEqual(validate_dataset(source), [])

    def test_17_unknown_and_closed_following_overnight_intervals_remain_valid(self):
        source = json.loads((Path(__file__).parent / "locations-pilot.json").read_text(encoding="utf-8"))
        for next_state in ("UNKNOWN", "CLOSED"):
            with self.subTest(next_state=next_state):
                data = json.loads(json.dumps(source))
                metadata = data["locations"][0]["metadata"]
                metadata["hoursEvidence"] = {
                    "url": "https://example.test/hours",
                    "verifiedAt": "2026-10-01T00:00:00Z",
                }
                metadata["weekly"]["MONDAY"] = {
                    "state": "INTERVALS",
                    "intervals": [{"start": "23:00", "end": "01:00", "nextDay": True}],
                }
                metadata["weekly"]["TUESDAY"] = {"state": next_state, "intervals": []}
                self.assertEqual(validate_dataset(data), [])


if __name__ == "__main__":
    unittest.main()
