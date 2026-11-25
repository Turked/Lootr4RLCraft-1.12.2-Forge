"""Run a hard-stop recovery trial using two private JVMs in a disposable, staged RLCraft server copy."""

import argparse
import os
import subprocess
import time
from pathlib import Path


def wait_for(process, log, offset, text, timeout=300):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        log.seek(offset)
        output = log.read().decode("utf-8", "replace")
        if text in output:
            return
        if "Probe failed:" in output or process.poll() is not None:
            raise RuntimeError(output[-3000:])
        time.sleep(0.25)
    raise TimeoutError(f"Timed out waiting for {text} in {log.name}")


def command(process, log, text, expected):
    offset = log.seek(0, 2)
    process.stdin.write((text + "\n").encode("ascii"))
    process.stdin.flush()
    wait_for(process, log, offset, expected)


def run(java, directory, verify):
    filename = directory / ("crash-restart.log" if verify else "crash-before.log")
    if filename.exists():
        filename = directory / f"{filename.stem}-{time.time_ns()}.log"
    with filename.open("wb") as output, filename.open("rb") as log:
        process = subprocess.Popen([str(java), "-Xms256M", "-Xmx3500M", "-jar", "forge.jar", "nogui"],
                                   cwd=directory, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            wait_for(process, log, 0, 'For help, type "help"')
            if verify:
                command(process, log, "lootrqa crash-verify", "CRASH_RESULT passed=")
                process.stdin.write(b"stop\n")
                process.stdin.flush()
                process.wait(timeout=120)
            else:
                command(process, log, "lootrqa crash-prime", "CRASH_PRIMED")
                # Locks needs populated neighbors and a delayed tick, which can take longer on
                # first pack startup. Baseline checks all three phases before writing any chest.
                deadline = time.monotonic() + 120
                while True:
                    time.sleep(3)
                    try:
                        command(process, log, "lootrqa crash-baseline", "CRASH_BASELINE")
                        break
                    except RuntimeError as failure:
                        if "Locks not ready for baseline" not in str(failure) or time.monotonic() >= deadline:
                            raise
                command(process, log, "save-off", "Turned off world auto-saving")
                command(process, log, "lootrqa crash-arm", "CRASH_ARMED")
                process.kill()
                process.wait(timeout=30)
                print(f"Terminated only the armed disposable JVM, PID {process.pid}, exit {process.returncode}", flush=True)
        finally:
            if process.poll() is None:
                # On a failed setup, stop normally whenever the console is still available.
                try:
                    process.stdin.write(b"stop\n")
                    process.stdin.flush()
                    process.wait(timeout=120)
                except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
                    process.kill()
                    process.wait()
        log.seek(0)
        for line in log.read().decode("utf-8", "replace").splitlines():
            if "[lootr-crash-probe]" in line:
                print(line, flush=True)
    return filename


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("java", type=Path)
    parser.add_argument("server", type=Path)
    parser.add_argument("--resume-setup", action="store_true", help="Resume a previously stopped, primed trial before baseline")
    args = parser.parse_args()
    directory = args.server.resolve()
    props = dict(line.split("=", 1) for line in (directory / "server.properties").read_text().splitlines()
                 if line and not line.startswith("#") and "=" in line)
    if props.get("level-name") != "CrashTrial" or props.get("server-ip") != "127.0.0.1":
        parser.error("Use a staged loopback-only test server with level-name=CrashTrial")
    if (directory / "CrashTrial").exists():
        previous = directory / "crash-before.log"
        phase = directory / "CrashTrial/data/lootr-crash-phase.txt"
        if not args.resume_setup or phase.exists() or not previous.is_file() or "CRASH_PRIMED" not in previous.read_text(errors="replace"):
            parser.error("Use a fresh disposable server directory, or resume a primed setup with no saved baseline")
    run(args.java.resolve(), directory, False)
    result = run(args.java.resolve(), directory, True)
    if "CRASH_RESULT passed=3/3" not in result.read_text(errors="replace"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
