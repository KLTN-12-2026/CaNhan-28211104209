import importlib.util
from pathlib import Path
import tempfile
import subprocess
import sys
import os
import unittest

spec=importlib.util.spec_from_file_location("runner",Path(__file__).with_name("run_mysql_migrations.py"))
runner=importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

class GuardTests(unittest.TestCase):
    def env(self):
        return dict(KDRIVE_TEST_DB_URL="jdbc:mysql://127.0.0.1:33676/kdrive_test_unit?useSSL=false",
         KDRIVE_TEST_DB_USER="fixture",KDRIVE_TEST_DB_PASSWORD="test-only",
         KDRIVE_TEST_EXPECTED_HOST="127.0.0.1",KDRIVE_TEST_EXPECTED_PORT="33676",
         KDRIVE_TEST_EXPECTED_SCHEMA="kdrive_test_unit",KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX="kdrive_test_",
         KDRIVE_TEST_FIXTURE_MARKER_TOKEN="unit-token",KDRIVE_TEST_MARKER_SCHEMA="kdrive_marker_unit")
    def test_explicit_fixture(self):
        self.assertEqual(runner.validate_config(self.env()),"jdbc:mysql://127.0.0.1:33676/mysql?useSSL=false")
    def test_forbidden_ports(self):
        for port in (3306,33385):
            e=self.env();e["KDRIVE_TEST_DB_URL"]=e["KDRIVE_TEST_DB_URL"].replace("33676",str(port));e["KDRIVE_TEST_EXPECTED_PORT"]=str(port)
            with self.subTest(port=port),self.assertRaises(ValueError):runner.validate_config(e)
    def test_missing_settings(self):
        for key in runner.REQUIRED:
            e=self.env();del e[key]
            with self.subTest(key=key),self.assertRaises(ValueError):runner.validate_config(e)
    def test_target_and_url_rejections(self):
        for key,value in [("KDRIVE_TEST_EXPECTED_SCHEMA","other"),("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX","kdrive"),("KDRIVE_TEST_MARKER_SCHEMA","bad-name"),("KDRIVE_TEST_DB_URL","jdbc:mysql://localhost:33676/kdrive_test_unit?x=1"),("KDRIVE_TEST_DB_URL","jdbc:mysql://127.0.0.1:33676/kdrive?x=1"),("KDRIVE_TEST_DB_URL","jdbc:mysql://user:pass@127.0.0.1:33676/kdrive_test_unit?x=1")]:
            e=self.env();e[key]=value
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):runner.validate_config(e)
    def test_entrypoint_rejects_missing_fixture_before_writes(self):
        target = runner.REPO / ".runtime" / "must-not-create-mysql-guard-test"
        self.assertFalse(target.exists())
        env = {k:v for k,v in os.environ.items() if not k.startswith("KDRIVE_")}
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("run_mysql_migrations.py")),
                                 "--evidence", str(target)], env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing required isolated fixture settings", result.stderr)
        self.assertFalse(target.exists())
    def test_output_refusals_do_not_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);existing=root/".runtime/existing";existing.mkdir(parents=True)
            for target in (existing,root/"outside"):
                with self.assertRaises(ValueError):runner.validate_output(target,root)
            self.assertEqual(list(existing.iterdir()),[])
            self.assertFalse((root/"outside").exists())
            self.assertEqual(runner.validate_output(root/".runtime/new",root),root/".runtime/new")
            self.assertFalse((root/".runtime/new").exists())
if __name__=="__main__":unittest.main()
