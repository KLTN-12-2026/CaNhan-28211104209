#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Importer idempotent cho bo dia diem pilot KDrive.
- Mac dinh o che do --dry-run (khong ghi du lieu).
- Validate toan bo dataset JSON truoc khi thuc hien bat ky mutation nao.
- So sanh toan dien cac facts (toa do, category, status, metadata) de phat hien conflict.
- Khong ghi de neu co xung dot du lieu (Conflict-safe).
- Khong xoa roi tao lai (tranh dangling foreign keys).
"""

import argparse
import io
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.parse import urlsplit

# Dam bao output UTF-8 tren Windows console
if sys.stdout.encoding != 'utf-8':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')


def http_request(url, method="GET", token=None, payload=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            content = resp.read().decode("utf-8")
            status = resp.status
            return status, json.loads(content) if content else None
    except urllib.error.HTTPError as err:
        err_body = err.read().decode("utf-8")
        try:
            parsed = json.loads(err_body)
        except Exception:
            parsed = {"error": err_body}
        return err.code, parsed


def validate_dataset(data):
    """Validate all importer payloads before any API request can mutate data."""
    errors = []
    days = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"]
    allowed_states = {"UNKNOWN", "CLOSED", "OPEN_24H", "INTERVALS"}

    def evidence_valid(evidence, prefix):
        if evidence is None:
            return False
        if not isinstance(evidence, dict):
            errors.append(f"{prefix}: evidence phai la object hoac null.")
            return False
        url = evidence.get("url")
        verified_at = evidence.get("verifiedAt")
        if not isinstance(url, str) or not url or len(url) > 500:
            errors.append(f"{prefix}: URL evidence khong hop le.")
            return False
        try:
            parsed = urlsplit(url)
            if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
                errors.append(f"{prefix}: URL evidence phai la http/https va khong chua userinfo.")
        except ValueError:
            errors.append(f"{prefix}: URL evidence khong hop le.")
        if not isinstance(verified_at, str):
            errors.append(f"{prefix}: verifiedAt phai la ISO-8601 co timezone.")
            return False
        try:
            parsed_time = datetime.fromisoformat(verified_at.replace("Z", "+00:00"))
            if parsed_time.tzinfo is None:
                raise ValueError("timezone missing")
            utc_time = parsed_time.astimezone(timezone.utc)
            if utc_time < datetime(2000, 1, 1, tzinfo=timezone.utc) or utc_time > datetime.now(timezone.utc):
                errors.append(f"{prefix}: verifiedAt nam ngoai khoang hop le.")
        except ValueError:
            errors.append(f"{prefix}: verifiedAt phai la ISO-8601 co timezone.")
            return False
        return True

    def validate_hours(value, prefix):
        if not isinstance(value, dict):
            errors.append(f"{prefix}: hours phai la object.")
            return "UNKNOWN", []
        state = value.get("state")
        intervals = value.get("intervals")
        if not isinstance(state, str) or state not in allowed_states:
            errors.append(f"{prefix}: state hours khong hop le.")
            state = "UNKNOWN"
        if not isinstance(intervals, list) or len(intervals) > 4:
            errors.append(f"{prefix}: intervals phai la danh sach toi da 4 khoang.")
            intervals = []
        if state == "INTERVALS" and not intervals:
            errors.append(f"{prefix}: INTERVALS can it nhat mot khoang.")
        if state != "INTERVALS" and intervals:
            errors.append(f"{prefix}: chi INTERVALS moi duoc co khoang gio.")

        parsed_intervals = []
        previous_end = -1
        for index, interval in enumerate(intervals):
            interval_prefix = f"{prefix}.intervals[{index}]"
            if not isinstance(interval, dict):
                errors.append(f"{interval_prefix}: khoang gio phai la object.")
                continue
            start = interval.get("start")
            end = interval.get("end")
            next_day = interval.get("nextDay")
            if not isinstance(start, str) or not re.fullmatch(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]", start):
                errors.append(f"{interval_prefix}: start phai theo HH:mm.")
                continue
            if not isinstance(end, str) or not re.fullmatch(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]", end):
                errors.append(f"{interval_prefix}: end phai theo HH:mm.")
                continue
            if not isinstance(next_day, bool):
                errors.append(f"{interval_prefix}: nextDay phai la boolean.")
                continue
            start_minute = int(start[:2]) * 60 + int(start[3:])
            end_minute = int(end[:2]) * 60 + int(end[3:]) + (1440 if next_day else 0)
            if end_minute <= start_minute or end_minute - start_minute > 1440 or start_minute < previous_end:
                errors.append(f"{interval_prefix}: khoang gio phai tang dan, khong chong lan va dai toi da 24 gio.")
            previous_end = end_minute
            parsed_intervals.append((start_minute, end_minute, end, next_day))
        return state, parsed_intervals

    def check_boundary(previous, current, prefix):
        if previous[0] != "INTERVALS" or current[0] in {"UNKNOWN", "CLOSED"}:
            return
        first_start = 0 if current[0] == "OPEN_24H" else current[1][0][0]
        for _, _, end, next_day in previous[1]:
            end_minute = int(end[:2]) * 60 + int(end[3:])
            if next_day and end_minute > first_start:
                errors.append(f"{prefix}: khoang qua dem chong voi lich ngay ke tiep.")

    if not isinstance(data, dict):
        return ["Dataset phai la JSON object."]
    if data.get("schemaVersion") != "kdrive-locations-pilot-v1":
        errors.append(f"schemaVersion khong hop le: {data.get('schemaVersion')}")

    locations = data.get("locations")
    if not isinstance(locations, list) or len(locations) < 4:
        errors.append("Danh sach locations phai chua it nhat 4 dia diem.")
        return errors

    seen_names = set()
    for index, location in enumerate(locations):
        prefix = f"locations[{index}]"
        if not isinstance(location, dict):
            errors.append(f"{prefix}: location phai la object.")
            continue

        name = location.get("name")
        if not isinstance(name, str) or not name.strip() or len(name.strip()) > 200:
            errors.append(f"{prefix}: name phai co 1-200 ky tu.")
        elif name.strip().casefold() in seen_names:
            errors.append(f"{prefix}: trung lap ten dia diem '{name}'.")
        else:
            seen_names.add(name.strip().casefold())

        category = location.get("categoryName")
        if not isinstance(category, str) or not category.strip() or len(category.strip()) > 100:
            errors.append(f"{prefix}: categoryName phai co 1-100 ky tu.")
        if not isinstance(location.get("status"), str) or location["status"] not in {"ACTIVE", "INACTIVE"}:
            errors.append(f"{prefix}: status phai la ACTIVE hoac INACTIVE.")

        provenance = location.get("provenance")
        if not isinstance(provenance, dict):
            errors.append(f"{prefix}: provenance phai la object.")
            provenance = {}
        for field in ("address", "coordinatesSource"):
            value = provenance.get(field)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"{prefix}: provenance.{field} khong duoc de trong.")

        for field, minimum, maximum, label in (
            ("latitude", Decimal("15.4"), Decimal("16.7"), "vi do"),
            ("longitude", Decimal("107.4"), Decimal("108.8"), "kinh do"),
        ):
            value = location.get(field)
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                errors.append(f"{prefix}: {label} phai la so huu han.")
                continue
            try:
                number = Decimal(str(value))
                if not number.is_finite() or number < minimum or number > maximum:
                    errors.append(f"{prefix}: {label} nam ngoai vung Da Nang - Hoi An.")
                if number.as_tuple().exponent < -7:
                    errors.append(f"{prefix}: {label} khong duoc co qua 7 chu so thap phan.")
            except InvalidOperation:
                errors.append(f"{prefix}: {label} khong hop le.")

        metadata = location.get("metadata")
        if not isinstance(metadata, dict):
            errors.append(f"{prefix}: metadata phai la object de bieu dien UNKNOWN/null ro rang.")
            continue
        if metadata.get("timezone") != "Asia/Ho_Chi_Minh":
            errors.append(f"{prefix}: timezone phai la Asia/Ho_Chi_Minh.")

        weekly = metadata.get("weekly")
        if not isinstance(weekly, dict):
            errors.append(f"{prefix}: weekly phai la object.")
            weekly = {}
        unknown_days = set(weekly) - set(days)
        if unknown_days:
            errors.append(f"{prefix}: weekly co ngay khong hop le: {sorted(unknown_days)}.")
        parsed_weekly = {}
        for day in days:
            parsed_weekly[day] = validate_hours(
                weekly.get(day, {"state": "UNKNOWN", "intervals": []}),
                f"{prefix}.weekly.{day}",
            )
        for index, day in enumerate(days):
            check_boundary(parsed_weekly[days[index - 1]], parsed_weekly[day], f"{prefix}.weekly.{day}")

        exceptions = metadata.get("exceptions")
        if not isinstance(exceptions, dict) or len(exceptions) > 60:
            errors.append(f"{prefix}: exceptions phai la object toi da 60 ngay.")
            exceptions = {}
        parsed_exceptions = {}
        for date_text, hours in exceptions.items():
            try:
                exception_date = date.fromisoformat(date_text)
                if exception_date.isoformat() != date_text or not 1900 <= exception_date.year <= 2200:
                    raise ValueError("invalid date")
            except (TypeError, ValueError):
                errors.append(f"{prefix}: ngay exception '{date_text}' khong hop le.")
                continue
            parsed_exceptions[exception_date] = validate_hours(hours, f"{prefix}.exceptions.{date_text}")
        for exception_date, hours in parsed_exceptions.items():
            previous = parsed_exceptions.get(exception_date.fromordinal(exception_date.toordinal() - 1),
                                              parsed_weekly[days[exception_date.weekday() - 1]])
            following = parsed_exceptions.get(exception_date.fromordinal(exception_date.toordinal() + 1),
                                               parsed_weekly[days[(exception_date.weekday() + 1) % 7]])
            check_boundary(previous, hours, f"{prefix}.exceptions.{exception_date}")
            check_boundary(hours, following, f"{prefix}.exceptions.{exception_date}")

        all_hours = list(parsed_weekly.values()) + list(parsed_exceptions.values())
        has_known_hours = any(state != "UNKNOWN" for state, _ in all_hours)
        hours_evidence = metadata.get("hoursEvidence")
        if has_known_hours and hours_evidence is None:
            errors.append(f"{prefix}: co gio da biet nhung thieu hoursEvidence.")
        elif not has_known_hours and hours_evidence is not None:
            errors.append(f"{prefix}: khong co gio da biet thi hoursEvidence phai la null.")
        elif hours_evidence is not None:
            evidence_valid(hours_evidence, f"{prefix}.hoursEvidence")

        duration = metadata.get("durationMinutes")
        duration_evidence = metadata.get("durationEvidence")
        if duration is not None:
            if isinstance(duration, bool) or not isinstance(duration, int) or not 1 <= duration <= 1440:
                errors.append(f"{prefix}: durationMinutes phai la so nguyen tu 1 den 1440.")
            if not isinstance(provenance.get("durationBasis"), str) or not provenance["durationBasis"].strip():
                errors.append(f"{prefix}: durationMinutes can provenance.durationBasis.")
            if duration_evidence is None:
                errors.append(f"{prefix}: co durationMinutes nhung thieu durationEvidence.")
            else:
                evidence_valid(duration_evidence, f"{prefix}.durationEvidence")
        elif duration_evidence is not None:
            errors.append(f"{prefix}: durationEvidence phai la null khi durationMinutes la null.")

    return errors


def canonicalize_intervals(intervals):
    if not intervals or not isinstance(intervals, list):
        return []
    res = []
    for iv in intervals:
        if isinstance(iv, dict):
            res.append({
                "start": iv.get("start", ""),
                "end": iv.get("end", ""),
                "nextDay": bool(iv.get("nextDay", False))
            })
    return sorted(res, key=lambda x: (x["start"], x["end"], x["nextDay"]))


def canonicalize_hours(h):
    if not h or not isinstance(h, dict):
        return {"state": "UNKNOWN", "intervals": []}
    state = h.get("state", "UNKNOWN")
    intervals = canonicalize_intervals(h.get("intervals", []))
    return {"state": state, "intervals": intervals}


def canonicalize_evidence(ev):
    if not ev or not isinstance(ev, dict):
        return None
    return {
        "url": ev.get("url", ""),
        "verifiedAt": str(ev.get("verifiedAt", "")) if ev.get("verifiedAt") is not None else ""
    }


def canonicalize_metadata(meta):
    if not meta or not isinstance(meta, dict):
        return None

    # Weekly
    weekly = {}
    days = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"]
    raw_weekly = meta.get("weekly") or {}
    for d in days:
        if d in raw_weekly:
            weekly[d] = canonicalize_hours(raw_weekly[d])

    # Exceptions
    exceptions = {}
    raw_exceptions = meta.get("exceptions") or {}
    for d_str in sorted(raw_exceptions.keys()):
        exceptions[d_str] = canonicalize_hours(raw_exceptions[d_str])

    return {
        "timezone": meta.get("timezone", ""),
        "durationMinutes": meta.get("durationMinutes"),
        "hoursEvidence": canonicalize_evidence(meta.get("hoursEvidence")),
        "durationEvidence": canonicalize_evidence(meta.get("durationEvidence")),
        "weekly": weekly,
        "exceptions": exceptions
    }


def metadata_matches(ex_meta, pilot_meta):
    """So sanh metadata canonical toan dien giua existing tren server va pilot dataset."""
    c_ex = canonicalize_metadata(ex_meta)
    c_pi = canonicalize_metadata(pilot_meta)
    return c_ex == c_pi


def import_pilot_locations(json_path, base_url, token, dry_run=True):
    with open(json_path, "r", encoding="utf-8") as f:
        pilot_data = json.load(f)

    # 1. Pre-validation toan bo dataset (F06)
    val_errors = validate_dataset(pilot_data)
    if val_errors:
        return {
            "dryRun": dry_run,
            "validationSuccess": False,
            "errors": val_errors,
            "locationsCreated": [],
            "locationsSkipped": [],
            "locationsConflict": []
        }

    report = {
        "dryRun": dry_run,
        "validationSuccess": True,
        "totalLocations": len(pilot_data.get("locations", [])),
        "categoriesCreated": [],
        "categoriesReused": [],
        "locationsCreated": [],
        "locationsSkipped": [],
        "locationsConflict": [],
        "errors": []
    }

    if not token and not dry_run:
        report["errors"].append("Khong co token Admin de thuc hien --apply.")
        return report

    # 2. Lay danh muc hien tai
    cat_url = f"{base_url}/api/v1/admin/location-categories"
    status, categories = http_request(cat_url, method="GET", token=token)
    if status != 200:
        report["errors"].append(f"Khong the doc danh muc dia diem: HTTP {status} ({categories})")
        return report
    if not isinstance(categories, list) or any(
        not isinstance(category, dict)
        or not isinstance(category.get("name"), str)
        or not isinstance(category.get("id"), str)
        for category in categories
    ):
        report["errors"].append("Danh sach danh muc dia diem tu API khong hop le.")
        return report

    category_map = {c["name"]: c["id"] for c in categories}

    # 3. Xu ly cac danh muc can thiet
    required_categories = sorted({loc["categoryName"] for loc in pilot_data["locations"]})
    for cat_name in required_categories:
        if cat_name in category_map:
            report["categoriesReused"].append(cat_name)
        else:
            if dry_run:
                report["categoriesCreated"].append(f"[DRY-RUN] {cat_name}")
                category_map[cat_name] = f"dry-run-cat-{cat_name}"
            else:
                c_status, c_res = http_request(cat_url, method="POST", token=token, payload={"name": cat_name})
                if c_status in (200, 201) and isinstance(c_res, dict) and isinstance(c_res.get("id"), str) and c_res["id"]:
                    category_map[cat_name] = c_res["id"]
                    report["categoriesCreated"].append(cat_name)
                else:
                    report["errors"].append(f"Loi tao danh muc '{cat_name}': {c_res}")
    if report["errors"]:
        return report

    # 4. Lay danh sach dia diem hien co
    loc_url = f"{base_url}/api/v1/admin/locations"
    status, existing_locations = http_request(loc_url, method="GET", token=token)
    if status != 200:
        report["errors"].append(f"Khong the doc danh sach dia diem: HTTP {status} ({existing_locations})")
        return report
    if not isinstance(existing_locations, list) or any(
        not isinstance(location, dict) or not isinstance(location.get("name"), str)
        for location in existing_locations
    ):
        report["errors"].append("Danh sach dia diem tu API khong hop le.")
        return report

    existing_by_name = {loc["name"]: loc for loc in existing_locations}

    # 5. Xu ly tung dia diem
    for loc in pilot_data["locations"]:
        name = loc["name"]
        cat_id = category_map.get(loc["categoryName"])
        lat = round(float(loc["latitude"]), 7)
        lng = round(float(loc["longitude"]), 7)
        status_val = loc["status"]
        meta = loc.get("metadata")

        payload = {
            "categoryId": cat_id,
            "name": name,
            "latitude": lat,
            "longitude": lng,
            "status": status_val,
            "metadata": meta
        }

        if name in existing_by_name:
            existing = existing_by_name[name]
            coords_match = (
                Decimal(str(existing["latitude"])) == Decimal(str(loc["latitude"]))
                and Decimal(str(existing["longitude"])) == Decimal(str(loc["longitude"]))
            )
            category_match = existing.get("categoryId") == cat_id
            status_match = existing.get("status") == status_val
            meta_match = metadata_matches(existing.get("metadata"), meta)

            if coords_match and category_match and status_match and meta_match:
                report["locationsSkipped"].append({
                    "name": name,
                    "reason": "Exact match (coordinates, category, status, metadata) - idempotent skip"
                })
            else:
                conflict_reasons = []
                if not coords_match:
                    conflict_reasons.append(
                        f"coordinates diff (server: {existing.get('latitude')},{existing.get('longitude')} "
                        f"vs pilot: {loc['latitude']},{loc['longitude']})"
                    )
                if not category_match:
                    conflict_reasons.append(f"category diff (server: {existing.get('categoryId')} vs pilot: {cat_id})")
                if not status_match:
                    conflict_reasons.append(f"status diff (server: {existing.get('status')} vs pilot: {status_val})")
                if not meta_match:
                    conflict_reasons.append("metadata/hours facts diff")

                report["locationsConflict"].append({
                    "name": name,
                    "reason": "; ".join(conflict_reasons)
                })
        else:
            if dry_run:
                report["locationsCreated"].append({"name": name, "status": "[DRY-RUN] Plan to create"})
            else:
                l_status, l_res = http_request(loc_url, method="POST", token=token, payload=payload)
                if l_status in (200, 201) and isinstance(l_res, dict) and isinstance(l_res.get("id"), str) and l_res["id"]:
                    report["locationsCreated"].append({"name": name, "id": l_res["id"]})
                else:
                    report["errors"].append(f"Loi tao dia diem '{name}': HTTP {l_status} - {l_res}")

    return report


def main():
    parser = argparse.ArgumentParser(description="Importer bo dia diem pilot KDrive")
    parser.add_argument("--json", default=str(Path(__file__).parent / "locations-pilot.json"), help="Duong dan file locations-pilot.json")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080", help="Root URL backend")
    parser.add_argument("--token", default=os.getenv("KDRIVE_ADMIN_TOKEN"), help="Admin JWT token (hoac dat qua env KDRIVE_ADMIN_TOKEN)")
    parser.add_argument("--apply", action="store_true", help="Thuc hien ghi thay vi dry-run")
    args = parser.parse_args()

    dry_run = not args.apply
    result = import_pilot_locations(args.json, args.base_url, args.token, dry_run=dry_run)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if result["errors"] or not result.get("validationSuccess", True):
        sys.exit(1)


if __name__ == "__main__":
    main()
