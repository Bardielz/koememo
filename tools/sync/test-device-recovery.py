"""Exercise process death and manual pause on an unlocked debug phone with a paired watch."""
import argparse
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone", required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    pkg = "dev.watchmemory"

    def shell(*command):
        return subprocess.check_output(
            [args.adb, "-s", args.phone, "shell", shlex.join(command)], text=True, timeout=20
        ).strip()

    def prefs():
        xml = shell("run-as", pkg, "cat", "shared_prefs/sync_state.xml")
        return {v.get("name"): v.get("value", v.text) for v in ET.fromstring(xml)}

    def until(predicate, timeout=60):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            value = predicate()
            if value:
                return value
            time.sleep(0.5)
        raise AssertionError("Timed out waiting for " + predicate.__name__)

    def open_library():
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard")
        shell("am", "start", "-W", "-n", pkg + "/dev.koememo.phone.LibraryActivity")

    def tap(label):
        path = "/data/local/tmp/koememo-recovery-ui.xml"
        shell("uiautomator", "dump", path)
        nodes = ET.fromstring(shell("cat", path)).iter("node")
        button = next(n for n in nodes if n.get("package") == pkg and n.get("text") == label
                      and n.get("enabled") == "true")
        x, y, right, bottom = map(int, re.findall(r"\d+", button.get("bounds")))
        shell("input", "tap", str((x + right) // 2), str((y + bottom) // 2))

    open_library()
    tap("从手表同步")
    token = until(lambda: prefs().get("pending_session"))
    until(lambda: "正在同步" in prefs().get("status", ""))
    old_pid = shell("pidof", pkg)
    assert old_pid.isdigit()
    shell("input", "keyevent", "HOME")
    shell("run-as", pkg, "kill", "-9", old_pid)

    def restarted():
        services = shell("dumpsys", "activity", "services", pkg)
        match = re.search(r'app=ProcessRecord\{\S+ (\d+):dev\.watchmemory/', services)
        return match and match[1] != old_pid and "isForeground=true" in services

    until(restarted, 120)
    assert prefs().get("pending_session") == token
    until(lambda: "正在同步" in prefs().get("status", ""), 180)
    print("PASS: restarted and transferring under the original session", token, flush=True)
    open_library()
    tap("暂停同步")
    until(lambda: "pending_session" not in prefs() and prefs().get("active") == "false")
    until(lambda: "ServiceRecord{" not in shell("dumpsys", "activity", "services", pkg))
    shell("input", "keyevent", "HOME")
    open_library()
    time.sleep(2)
    assert "pending_session" not in prefs()
    assert "ServiceRecord{" not in shell("dumpsys", "activity", "services", pkg)
    print("PASS: explicit pause survives reopening")


if __name__ == "__main__":
    main()
