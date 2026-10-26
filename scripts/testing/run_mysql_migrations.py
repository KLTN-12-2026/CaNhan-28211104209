"""Run native migration/history tests against an explicitly prepared isolated MySQL.
No container creation, marker creation, database cleanup or production credentials.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
REQUIRED = ("KDRIVE_TEST_DB_URL", "KDRIVE_TEST_DB_USER", "KDRIVE_TEST_DB_PASSWORD",
            "KDRIVE_TEST_EXPECTED_HOST", "KDRIVE_TEST_EXPECTED_PORT",
            "KDRIVE_TEST_EXPECTED_SCHEMA", "KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX",
            "KDRIVE_TEST_FIXTURE_MARKER_TOKEN", "KDRIVE_TEST_MARKER_SCHEMA")
HISTORICAL = {
 "V1__rental_schema.sql": "db2d959ab1a1c9b704b48bfa0a2b0a7986f9f8c24e57cd7dd93ce050b6ab4892",
 "V2__incident_audit_and_review_vehicle.sql": "7444cb4dadb9499d1ef9c8433e3f7e969f48d00e785578021201816553fa66ce"
}

def validate_config(env):
    if any(not env.get(k) for k in REQUIRED):
        raise ValueError("Missing required isolated fixture settings (see docs/testing/backend.md)")
    raw = env["KDRIVE_TEST_DB_URL"]
    if not raw.startswith("jdbc:mysql://"):
        raise ValueError("Explicit MySQL JDBC URL required")
    url = urlsplit(raw[5:])
    schema = url.path.lstrip("/")
    prefix = env["KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"]
    if (url.hostname != "127.0.0.1" or url.port is None or url.port in (3306,33385)
            or url.username or url.password or url.fragment or not url.query
            or env["KDRIVE_TEST_EXPECTED_HOST"] != "127.0.0.1"
            or str(url.port) != env["KDRIVE_TEST_EXPECTED_PORT"]
            or schema != env["KDRIVE_TEST_EXPECTED_SCHEMA"]
            or not prefix.startswith("kdrive_test_") and not prefix.startswith("kdrive_c14_")
            or not re.fullmatch(r"[A-Za-z0-9_]+", schema)
            or not schema.startswith(prefix)
            or not re.fullmatch(r"[A-Za-z0-9_]+", env["KDRIVE_TEST_MARKER_SCHEMA"])):
        raise ValueError("Refusing unsafe or inconsistent fixture target")
    return f"jdbc:mysql://127.0.0.1:{url.port}/mysql?{url.query}"

def validate_output(path, repo):
    path = path.resolve()
    if not path.is_relative_to((repo / ".runtime").resolve()) or path.exists():
        raise ValueError("Evidence must be a NEW directory inside this repository .runtime/")
    return path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--maven-repo", help="Optional existing Maven dependency cache")
    args = parser.parse_args()
    env = os.environ.copy()
    admin_url = validate_config(env)
    out = validate_output(Path(args.evidence), REPO)
    backend = REPO / "backend"
    historical = backend / "src/test/resources/fixtures/historical-v2"
    for name, expected in HISTORICAL.items():
        if hashlib.sha256((historical / name).read_bytes()).hexdigest() != expected:
            raise ValueError("Historical migration snapshot hash mismatch")
    java = str(Path(env["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if env.get("JAVA_HOME") else shutil.which("java")
    flags = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
    version = subprocess.run([java, "-version"], capture_output=True, text=True, check=True, **flags)
    if not re.search(r'version "21(?:\.|")', version.stderr + version.stdout):
        raise ValueError("JDK 21 required")
    for key in list(env):
        if key.startswith(("SPRING_", "KDRIVE_MIGRATION_")) or key in ("KDRIVE_TEST_BOOTSTRAP_CASE",):
            env.pop(key)
    env.update(KDRIVE_MIGRATION_MYSQL_URL=admin_url,
               KDRIVE_MIGRATION_MYSQL_USER=env["KDRIVE_TEST_DB_USER"],
               KDRIVE_MIGRATION_MYSQL_PASSWORD=env["KDRIVE_TEST_DB_PASSWORD"],
               KDRIVE_HISTORICAL_MIGRATION_DIR=str(historical),
               KDRIVE_MIGRATION_ARTIFACT_DIR=str(out))
    source = [p for top in ("src", ".mvn") for p in (backend / top).rglob("*") if p.is_file()]
    source += [backend / n for n in ("pom.xml", "mvnw", "mvnw.cmd")]
    source += [Path(__file__).resolve()]
    def hashes():
        return {str(p.relative_to(REPO)):hashlib.sha256(p.read_bytes()).hexdigest() for p in source}
    before = hashes()
    out.mkdir(parents=True)
    (out / "inputs.json").write_text(json.dumps(before, indent=2), encoding="utf-8")
    mvn = [str(backend / "mvnw.cmd")] if os.name == "nt" else ["bash", str(backend / "mvnw")]
    results = []
    for suite, expected in (("MySqlMigrationTest",8),("CancelBoundaryFlywayHistoryTest",1)):
        cmd = mvn + ["-B","-ntp",f"-Dtest={suite}"]
        if args.maven_repo:
            cmd.append(f"-Dmaven.repo.local={Path(args.maven_repo).resolve()}")
        cmd.append("test")
        xml = backend / "target/surefire-reports" / ("TEST-com.carrental." + suite + ".xml")
        if xml.exists():
            shutil.copy2(xml, out / ("previous-" + xml.name))
            xml.unlink()  # Require newly generated XML, never accept a stale report.
        with (out / (suite + ".log")).open("wb") as log:
            result = subprocess.run(cmd,cwd=backend,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=600,**flags)
        (out / (suite + ".exit")).write_text(str(result.returncode))
        if result.returncode:
            raise RuntimeError("Native test failed; inspect private evidence log: " + suite)
        xml = backend / "target/surefire-reports" / ("TEST-com.carrental." + suite + ".xml")
        node = ET.parse(xml).getroot()
        counts = [int(node.get(k,0)) for k in ("tests","failures","errors","skipped")]
        if counts != [expected,0,0,0]:
            raise RuntimeError("Unexpected test counts or skipped tests: " + suite)
        shutil.copy2(xml,out / xml.name)
        results.append({"suite":suite,"counts":counts})
        print(suite + ": PASS " + str(expected),flush=True)
    if hashes() != before:
        raise RuntimeError("Source drift during test")
    (out / "result.json").write_text(json.dumps({"status":"PASS migration/history scoped","suites":results},indent=2),encoding="utf-8")
    print("PASS migration/history; schemas retained; instance lifecycle belongs to caller")

if __name__ == "__main__":
    main()
