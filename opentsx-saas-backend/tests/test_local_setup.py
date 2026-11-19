"""local-setup.sh in a temporary directory, with stand-ins for psql, pg_isready, venv and pip: the .env.local it
writes holds random passwords, its output holds none of them, a rerun keeps them unless the backend refuses them,
and a failed SQL statement stops the script. No database, no network."""

import os
import stat
import subprocess
import sys
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "local-setup.sh"

STUBS = {
    "psql": """#!/bin/bash
case " $* " in
  *" -lqt "*) cat "$STUB_DIR/databases" 2>/dev/null; exit 0 ;;
esac
cat >> "$STUB_DIR/psql.sql"
if [ -e "$STUB_DIR/fail" ]; then
  echo "ERROR:  $(cat "$STUB_DIR/fail")" >&2
  case " $* " in *" ON_ERROR_STOP=1 "*) exit 3 ;; esac
fi
exit 0
""",
    "pg_isready": "#!/bin/bash\nexit 0\n",
    "pip": "#!/bin/bash\nexit 0\n",
    "python3": f"""#!/bin/bash
if [ "$1" = "-m" ] && [ "$2" = "venv" ]; then mkdir -p "$3/bin" && : > "$3/bin/activate"; exit 0; fi
exec "{sys.executable}" "$@"
""",
}


@pytest.fixture
def workdir(tmp_path):
    stubs = tmp_path / "stubs"
    stubs.mkdir()
    for name, text in STUBS.items():
        (stubs / name).write_text(text)
        (stubs / name).chmod(0o755)
    work = tmp_path / "work"
    work.mkdir()
    return work


def run(work, succeeds=True):
    stubs = work.parent / "stubs"
    env = {"PATH": f"{stubs}:/usr/bin:/bin", "HOME": str(work), "USER": "tester", "STUB_DIR": str(stubs)}
    done = subprocess.run(["bash", "-c", f'umask 022; exec bash "{SCRIPT}"'], cwd=work, env=env,
                          capture_output=True, text=True, timeout=60)
    assert (done.returncode == 0) == succeeds, done.stdout + done.stderr
    return done.stdout + done.stderr


def written(work):
    lines = (work / ".env.local").read_text().splitlines()
    return dict(line.split("=", 1) for line in lines if "=" in line and not line.startswith("#"))


def test_the_passwords_are_random_and_not_printed(workdir, tmp_path):
    output = run(workdir)
    env = written(workdir)
    db, admin = env["POSTGRES_PASSWORD"], env["FIRST_SUPERUSER_PASSWORD"]
    assert len(db) >= 12 and len(admin) >= 12 and db != admin
    for secret in (db, admin, env["SECRET_KEY"]):
        assert secret not in output
    assert "dev_password_123" not in output
    assert f"CREATE USER opentsx_dev WITH PASSWORD '{db}';" in (tmp_path / "stubs" / "psql.sql").read_text()
    assert stat.S_IMODE(os.stat(workdir / ".env.local").st_mode) == 0o600

    other = tmp_path / "other"
    (other / "work").mkdir(parents=True)
    os.symlink(tmp_path / "stubs", other / "stubs")
    run(other / "work")
    again = written(other / "work")
    assert again["POSTGRES_PASSWORD"] != db and again["FIRST_SUPERUSER_PASSWORD"] != admin


def test_a_rerun_keeps_the_passwords_and_gives_them_to_the_database_user(workdir, tmp_path):
    run(workdir)
    first = written(workdir)
    (tmp_path / "stubs" / "databases").write_text(" opentsx_local | tester | UTF8\n")
    output = run(workdir)
    second = written(workdir)
    assert second["POSTGRES_PASSWORD"] == first["POSTGRES_PASSWORD"]
    assert second["FIRST_SUPERUSER_PASSWORD"] == first["FIRST_SUPERUSER_PASSWORD"]
    assert second["POSTGRES_PASSWORD"] not in output
    sql = (tmp_path / "stubs" / "psql.sql").read_text()
    assert sql.endswith(f"ALTER USER opentsx_dev WITH PASSWORD '{first['POSTGRES_PASSWORD']}';\n")


def test_a_rerun_replaces_passwords_the_backend_refuses(workdir, tmp_path):
    (workdir / ".env.local").write_text("POSTGRES_PASSWORD=dev_password_123\nFIRST_SUPERUSER_PASSWORD=admin123\n")
    (tmp_path / "stubs" / "databases").write_text(" opentsx_local | tester | UTF8\n")
    output = run(workdir)
    env = written(workdir)
    assert len(env["POSTGRES_PASSWORD"]) >= 12 and env["POSTGRES_PASSWORD"] != "dev_password_123"
    assert len(env["FIRST_SUPERUSER_PASSWORD"]) >= 12
    sql = (tmp_path / "stubs" / "psql.sql").read_text()
    assert sql.endswith(f"ALTER USER opentsx_dev WITH PASSWORD '{env['POSTGRES_PASSWORD']}';\n")
    assert ("FIRST_SUPERUSER_PASSWORD of .env.local was shorter than 12 characters and is replaced; an existing "
            "administrator account keeps its password, change it with POST /api/v1/auth/change-password") in output
    assert "admin123" not in output


@pytest.mark.parametrize("databases, error", [
    ("", 'role "opentsx_dev" already exists'),
    (" opentsx_local | tester | UTF8\n", 'role "opentsx_dev" does not exist'),
])
def test_a_failed_statement_stops_the_script(workdir, tmp_path, databases, error):
    (tmp_path / "stubs" / "databases").write_text(databases)
    (tmp_path / "stubs" / "fail").write_text(error)
    output = run(workdir, succeeds=False)
    assert f"ERROR:  {error}" in output
    assert "✓ Password of" not in output and "✓ Database 'opentsx_local' created" not in output
    assert not (workdir / ".env.local").exists()


def test_every_key_it_writes_is_a_setting_of_the_backend(workdir):
    from app.core.config import Settings

    run(workdir)
    assert sorted(set(written(workdir)) - set(Settings.model_fields)) == []
