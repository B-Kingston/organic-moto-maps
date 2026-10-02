#!/usr/bin/env python3
"""ADB-driven visual route runner and interactive inspection helpers.

This intentionally drives the installed app through Android's accessibility
tree and input service. The app stays in the foreground after each command so
an agent can inspect a screenshot, issue another command, and inspect again.
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
import json
import math
import os
import re
import shlex
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from xml.etree import ElementTree


APP_ID = "com.organicmoto.maps"
ACTIVITY = f"{APP_ID}/.MainActivity"
DEFAULT_AVD = "Pixel_10_Pro"
WINDOW_DUMP = "/sdcard/organic-moto-visual-window.xml"
ROUTE_SNAPSHOT = "cache/visual-route.json"
FIX_ACK = "cache/visual-fix-ack.json"
CAMERA_SNAPSHOT = "cache/visual-camera.json"
LEVEL_PREFIX = "Ride complexity level "
DEFAULT_OUTPUT = Path("build/visual-inspection")

# Debug map-performance sweep (MapPerfSweepQueue / MapPerfSweepRunner).
PERF_SWEEP_ACTION = "com.organicmoto.maps.DEBUG_MAP_PERF_SWEEP"
PERF_RESULT_PREFIX = "cache/map-perf-result-"
PERF_TARGETS = {
    "cbd": (-27.4679, 153.0281),
    "houses": (-27.4616, 153.0466),
}
DEFAULT_PERF_REPORT = Path("build/brisbane-performance-report.md")

# Debug Maps-screen preview (VisualMapsPreview): deterministic download,
# import, and delete states for screenshots without a live server or SAF.
MAPS_PREVIEW_ACTION = "com.organicmoto.maps.DEBUG_VISUAL_MAPS_STATE"

# Mirrors RouteScreen's guidance camera policy. CameraFollowPolicyTest pins the
# Kotlin side, so drift shows up as a failed JVM suite before a visual run.
GUIDANCE_TILT_DEGREES = 58.0
GUIDANCE_RIDER_VERTICAL_FRACTION = 0.70
GUIDANCE_ZOOM_BANDS = ((7.0, 18.0), (14.0, 17.0), (22.0, 16.0), (31.0, 15.0), (math.inf, 14.0))
DEFAULT_SPEED_CASES = "0,8,15,23,32"

# Distance between injected fixes while riding the route. NavigationSession
# searches at most 40 vertices ahead of the matched position, so the runner
# advances in small steps (as progress_to does) instead of teleporting.
ROUTE_FOLLOW_STEP_M = 100.0


class VisualError(RuntimeError):
    pass


def emit(event: str, **values: Any) -> None:
    print(json.dumps({"event": event, **values}, ensure_ascii=False), flush=True)


def run_optional(action: Any, *arguments: Any, **kwargs: Any) -> None:
    """Best-effort restore step that never masks the original failure.

    Screenshot runs must put the device back the way they found it (dark-map
    preference, notification access, ride state); a restore step failing is
    reported but must not replace the error that triggered the cleanup.
    """
    try:
        action(*arguments, **kwargs)
    except Exception as error:  # noqa: BLE001 - cleanup must not mask failures
        emit(
            "restore_warning",
            action=getattr(action, "__name__", str(action)),
            error=str(error),
        )


def sdk_directory(root: Path) -> Path:
    configured = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if configured:
        return Path(configured).expanduser()
    properties = root / "local.properties"
    if properties.is_file():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                value = line.partition("=")[2].replace("\\:", ":")
                return Path(value).expanduser()
    return Path.home() / "Library/Android/sdk"


class VisualHarness:
    def __init__(self, args: argparse.Namespace) -> None:
        self.root = Path(__file__).resolve().parents[2]
        self.sdk = sdk_directory(self.root)
        self.adb = Path(os.environ.get("ADB", str(self.sdk / "platform-tools/adb")))
        self.emulator = Path(os.environ.get("EMULATOR", str(self.sdk / "emulator/emulator")))
        self.avd = args.avd or os.environ.get("ANDROID_AVD", DEFAULT_AVD)
        self.requested_serial = args.serial or os.environ.get("ANDROID_SERIAL")
        output_dir = Path(args.output_dir).expanduser() if args.output_dir else DEFAULT_OUTPUT
        if not output_dir.is_absolute():
            output_dir = self.root / output_dir
        self.output_dir = output_dir
        self.run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + f"-{os.getpid()}"
        self.run_dir = self.output_dir / self.run_id
        self.serial: str | None = None
        self.sequence = 0
        self.last_replay_fix: tuple[float, float, float | None] | None = None

    def command(
        self,
        *arguments: str,
        check: bool = True,
        timeout: float = 60,
        input_file: Any = None,
    ) -> subprocess.CompletedProcess[str]:
        completed = subprocess.run(
            [str(self.adb), *(str(arg) for arg in arguments)],
            cwd=self.root,
            stdin=input_file,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        if check and completed.returncode != 0:
            details = completed.stderr.strip() or completed.stdout.strip()
            raise VisualError(
                f"ADB command failed ({completed.returncode}): "
                f"{' '.join(shlex.quote(str(arg)) for arg in arguments)}"
                + (f"\n{details}" if details else "")
            )
        return completed

    def device_command(
        self,
        *arguments: str,
        check: bool = True,
        timeout: float = 60,
        input_file: Any = None,
    ) -> subprocess.CompletedProcess[str]:
        if not self.serial:
            raise VisualError("No ADB device selected. Run the launch command first.")
        return self.command(
            "-s", self.serial, *arguments, check=check, timeout=timeout, input_file=input_file
        )

    def raw_device_bytes(self, *arguments: str, timeout: float = 60) -> bytes:
        if not self.serial:
            raise VisualError("No ADB device selected. Run the launch command first.")
        completed = subprocess.run(
            [str(self.adb), "-s", self.serial, *arguments],
            cwd=self.root,
            capture_output=True,
            timeout=timeout,
        )
        if completed.returncode != 0:
            details = completed.stderr.decode(errors="replace").strip()
            raise VisualError(f"ADB command failed: {' '.join(arguments)}\n{details}")
        return completed.stdout

    def device_rows(self) -> list[tuple[str, str]]:
        completed = self.command("devices", check=True)
        rows: list[tuple[str, str]] = []
        for line in completed.stdout.splitlines()[1:]:
            fields = line.split()
            if len(fields) >= 2:
                rows.append((fields[0], fields[1]))
        return rows

    def ensure_device(self, *, start_if_missing: bool) -> None:
        if not self.adb.is_file():
            raise VisualError(
                f"adb was not found at {self.adb}. Set ANDROID_SDK_ROOT, ANDROID_HOME, or ADB."
            )
        self.command("start-server", check=True, timeout=20)
        rows = self.device_rows()
        online = [serial for serial, state in rows if state == "device"]

        if self.requested_serial:
            if self.requested_serial not in online:
                if not start_if_missing:
                    raise VisualError(
                        f"Android device {self.requested_serial!r} is not online. "
                        "Check `adb devices`."
                    )
                if any(serial == self.requested_serial for serial, _ in rows):
                    self._wait_for_serial(self.requested_serial)
                    online = [serial for serial, state in self.device_rows() if state == "device"]
                else:
                    raise VisualError(
                        f"Android device {self.requested_serial!r} is not online. "
                        "Connect it or choose a running emulator."
                    )
            self.serial = self.requested_serial
        elif len(online) == 1:
            self.serial = online[0]
        elif len(online) > 1:
            raise VisualError(
                "More than one Android device is online. Set ANDROID_SERIAL or pass --serial."
            )
        elif start_if_missing:
            pending_emulators = [
                serial for serial, state in rows
                if serial.startswith("emulator-") and state in {"offline", "device"}
            ]
            if pending_emulators:
                self._wait_for_serial(pending_emulators[0])
                self.serial = pending_emulators[0]
            else:
                self.start_emulator()
                self._wait_for_any_device()
        else:
            raise VisualError("No Android device is online. Run `visual.py launch` to start the AVD.")

        self.wait_for_boot()

    def start_emulator(self) -> None:
        if not self.emulator.is_file():
            raise VisualError(
                f"Android emulator was not found at {self.emulator}. "
                "Set ANDROID_SDK_ROOT, ANDROID_HOME, or EMULATOR."
            )
        avds = subprocess.run(
            [str(self.emulator), "-list-avds"],
            cwd=self.root,
            capture_output=True,
            text=True,
            timeout=20,
        )
        if avds.returncode != 0:
            raise VisualError(avds.stderr.strip() or "Could not list Android virtual devices.")
        if self.avd not in avds.stdout.splitlines():
            raise VisualError(
                f"AVD {self.avd!r} was not found. Set ANDROID_AVD or create the AVD once."
            )
        self.output_dir.mkdir(parents=True, exist_ok=True)
        emulator_log = (self.output_dir / "emulator.log").open("ab")
        subprocess.Popen(
            [str(self.emulator), "-avd", self.avd, "-no-snapshot-load"],
            cwd=self.root,
            stdin=subprocess.DEVNULL,
            stdout=emulator_log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        emit("emulator_started", avd=self.avd, log=str(self.output_dir / "emulator.log"))

    def _wait_for_serial(self, wanted: str, timeout: float = 180) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            states = dict(self.device_rows())
            if states.get(wanted) == "device":
                return
            time.sleep(1)
        raise VisualError(f"Android device {wanted!r} did not come online within {timeout:g}s.")

    def _wait_for_any_device(self, timeout: float = 180) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            online = [serial for serial, state in self.device_rows() if state == "device"]
            if len(online) == 1:
                self.serial = online[0]
                return
            if len(online) > 1:
                raise VisualError(
                    "More than one Android device came online. Set ANDROID_SERIAL or pass --serial."
                )
            time.sleep(1)
        raise VisualError(f"The {self.avd} emulator did not appear within {timeout:g}s.")

    def wait_for_boot(self, timeout: float = 180) -> None:
        assert self.serial
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = self.device_command("shell", "getprop", "sys.boot_completed", check=False)
            if result.stdout.strip() == "1":
                return
            time.sleep(1)
        raise VisualError(f"Android device {self.serial} did not finish booting within {timeout:g}s.")

    def build_and_install(self, *, build: bool, skip_map: bool, refresh_map: bool) -> None:
        apk = self.root / "app/build/outputs/apk/debug/app-debug.apk"
        if build:
            env = os.environ.copy()
            env.setdefault("JAVA_HOME", "/opt/homebrew/opt/openjdk@17")
            gradle = subprocess.run(
                ["./gradlew", ":app:assembleDebug"],
                cwd=self.root,
                env=env,
                text=True,
            )
            if gradle.returncode != 0:
                raise VisualError(f"Gradle build failed with exit code {gradle.returncode}.")
        if not apk.is_file():
            raise VisualError(f"Debug APK is missing at {apk}. Build it or remove --no-build.")
        self.device_command("install", "-r", str(apk), timeout=240)
        for permission in (
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        ):
            self.device_command("shell", "pm", "grant", APP_ID, permission, check=False)

        if not skip_map:
            archive = self.root / "data/tiles/queensland.pmtiles"
            if not archive.is_file() or archive.stat().st_size == 0:
                raise VisualError(
                    f"Basemap archive is missing at {archive}. Build it or pass --skip-map."
                )
            installed = self.device_command(
                "shell", f"run-as {APP_ID} sh -c 'test -s files/tiles/basemap.pmtiles'",
                check=False,
            )
            if refresh_map or installed.returncode != 0:
                self.device_command("shell", "run-as", APP_ID, "mkdir", "-p", "files/tiles")
                remote_command = f"run-as {APP_ID} sh -c 'cat > files/tiles/basemap.pmtiles'"
                with archive.open("rb") as map_file:
                    copied = self.command(
                        "-s", self.serial or "", "shell", remote_command,
                        check=False, timeout=300, input_file=map_file,
                    )
                if copied.returncode != 0:
                    raise VisualError(
                        "Could not install the PMTiles basemap into the emulator. "
                        + copied.stderr.strip()
                    )
                emit("basemap_installed", bytes=archive.stat().st_size)
            else:
                emit("basemap_reused", path="files/tiles/basemap.pmtiles")

    def launch_app(self, *, build: bool, skip_map: bool, refresh_map: bool) -> None:
        self.ensure_device(start_if_missing=True)
        self.build_and_install(build=build, skip_map=skip_map, refresh_map=refresh_map)
        self.device_command("shell", "am", "force-stop", APP_ID)
        self.device_command("shell", "am", "start", "-n", ACTIVITY)
        # The startup dialog gets its own window a frame after the planner.
        # Check both together instead of missing a late dialog while waiting
        # only for START in the obscured activity window.
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            nodes, _ = self.hierarchy()
            if any(node["text"] == "Not now" for node in nodes):
                self.capture("00-media-access-startup-prompt")
                self.tap_node("text", "Not now")
                continue
            if any(node["text"] == "START" for node in nodes):
                windows = self.device_command("shell", "dumpsys", "window", "windows").stdout
                blocking_dialog = any(
                    f"package={APP_ID}" in window and "DIM_BEHIND" in window
                    and "isVisible=true" in window
                    for window in re.split(r"(?=  Window #\d+ )", windows)
                )
                if not blocking_dialog:
                    break
            time.sleep(0.2)
        else:
            raise VisualError("The planner did not become available after startup setup.")
        self.capture("00-launched")

    def hierarchy(self) -> tuple[list[dict[str, Any]], str]:
        xml_text = ""
        last_error = ""
        for attempt in range(8):
            # A launch/IME transition can make uiautomator fail before writing
            # XML. Never accept the previous window's dump as the current UI.
            self.device_command("shell", "rm", "-f", WINDOW_DUMP)
            self.device_command(
                "shell", "uiautomator", "dump", "--compressed", WINDOW_DUMP,
                timeout=30, check=False,
            )
            dump = self.device_command("shell", "cat", WINDOW_DUMP, timeout=30, check=False)
            xml_text = dump.stdout
            if xml_text.lstrip().startswith("<?xml") or xml_text.lstrip().startswith("<hierarchy"):
                break
            last_error = dump.stderr.strip() or dump.stdout.strip() or "empty accessibility dump"
            time.sleep(0.25 if attempt < 3 else 0.5)
        else:
            raise VisualError(f"Android did not provide an accessibility tree: {last_error}")
        try:
            root = ElementTree.fromstring(xml_text)
        except ElementTree.ParseError as error:
            raise VisualError(f"Could not parse the Android accessibility tree: {error}") from error
        nodes: list[dict[str, Any]] = []
        for element in root.iter("node"):
            item = {
                "text": element.attrib.get("text", ""),
                "description": element.attrib.get("content-desc", ""),
                "bounds": element.attrib.get("bounds", ""),
                "class": element.attrib.get("class", ""),
                "clickable": element.attrib.get("clickable") == "true",
                "enabled": element.attrib.get("enabled") != "false",
                "focused": element.attrib.get("focused") == "true",
                "checked": element.attrib.get("checked") == "true",
                "checkable": element.attrib.get("checkable") == "true",
                "selected": element.attrib.get("selected") == "true",
                "visible": element.attrib.get("visible-to-user") != "false",
            }
            if any(item[key] for key in ("text", "description")):
                nodes.append(item)
        return nodes, xml_text

    def matching_nodes(
        self,
        kind: str,
        value: str,
        *,
        contains: bool = False,
        visible_only: bool = True,
    ) -> list[dict[str, Any]]:
        nodes, _ = self.hierarchy()
        key = "text" if kind == "text" else "description"
        matches = [
            node for node in nodes
            if (not visible_only or node["visible"])
            and (
                value in node[key] if contains else node[key] == value
            )
        ]
        return matches

    def wait_for_node(
        self,
        kind: str,
        value: str,
        *,
        contains: bool = False,
        timeout: float = 30,
    ) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            matches = self.matching_nodes(kind, value, contains=contains)
            if matches:
                return matches[0]
            time.sleep(0.5)
        nodes, _ = self.hierarchy()
        visible = [
            f"{node['text'] or node['description']} [{node['bounds']}]"
            for node in nodes if node["visible"] and (node["text"] or node["description"])
        ]
        raise VisualError(
            f"Timed out waiting for {kind} {value!r}. Visible nodes: "
            + "; ".join(visible[:35])
        )

    def wait_for_any_text(self, values: tuple[str, ...], *, timeout: float = 30) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            nodes, _ = self.hierarchy()
            for node in nodes:
                if node["visible"] and node["text"] in values:
                    return node
            time.sleep(0.4)
        raise VisualError(f"Timed out waiting for any of these controls: {', '.join(values)}.")

    @staticmethod
    def center(bounds: str) -> tuple[int, int, int, int]:
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not match:
            raise VisualError(f"Invalid Android screen bounds: {bounds!r}")
        return tuple(int(part) for part in match.groups())  # type: ignore[return-value]

    def select_node(
        self,
        kind: str,
        value: str,
        *,
        contains: bool = False,
        index: int = 0,
    ) -> dict[str, Any]:
        matches = self.matching_nodes(kind, value, contains=contains)
        if not matches:
            raise VisualError(f"Could not find visible {kind} {value!r}.")
        if index < 0 or index >= len(matches):
            raise VisualError(
                f"{kind} {value!r} matched {len(matches)} node(s); index {index} is out of range."
            )
        return matches[index]

    def tap_point(self, x: int, y: int) -> None:
        self.device_command("shell", "input", "tap", str(x), str(y))

    def tap_node(
        self,
        kind: str,
        value: str,
        *,
        contains: bool = False,
        index: int = 0,
    ) -> dict[str, Any]:
        node = self.select_node(kind, value, contains=contains, index=index)
        x1, y1, x2, y2 = self.center(node["bounds"])
        self.tap_point((x1 + x2) // 2, (y1 + y2) // 2)
        return node

    def type_into(
        self,
        kind: str,
        selector: str,
        value: str,
        *,
        contains: bool = False,
        replace: bool = True,
        verify: bool = False,
    ) -> None:
        """Types into a field. With ``verify``, the field is read back and
        corrected: select-all can race the field's focus right after the tap,
        leaving prefilled text in place or eating the first typed characters.
        """
        if not value.isascii() or any(ord(char) < 32 for char in value):
            raise VisualError("ADB text entry accepts printable ASCII only.")
        node = self.tap_node(kind, selector, contains=contains)
        if replace:
            selected = self.device_command(
                "shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A",
                check=False,
            )
            if selected.returncode != 0:
                # Older Android images may not provide input keycombination.
                self.device_command("shell", "input", "keyevent", "KEYCODE_MOVE_END")
                visible_text = node["text"]
                if visible_text:
                    self.device_command(
                        "shell", "input", "keyevent",
                        *("KEYCODE_DEL" for _ in visible_text),
                    )
        adb_text = value.replace("%", "%25").replace(" ", "%s")
        remote = "input text " + shlex.quote(adb_text)
        self.device_command("shell", remote)
        if not verify:
            return
        for _ in range(3):
            time.sleep(0.4)
            current = self.field_text(kind, selector, contains=contains)
            if current == value:
                return
            self.device_command("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            if current:
                self.device_command(
                    "shell", "input", "keyevent",
                    *("KEYCODE_DEL" for _ in range(len(current) + 2)),
                )
            self.device_command("shell", remote)
        raise VisualError(f"The field {selector!r} did not accept {value!r}.")

    def field_text(self, kind: str, selector: str, *, contains: bool = False) -> str | None:
        """The editable text behind a field. Compose exposes a field's content
        description and its EditText as separate nodes with the same bounds."""
        matches = self.matching_nodes(kind, selector, contains=contains)
        if not matches:
            return None
        nodes, _ = self.hierarchy()
        bounds = matches[0]["bounds"]
        for node in nodes:
            if node["bounds"] == bounds and "EditText" in node["class"]:
                return node["text"]
        return matches[0]["text"]

    def press_key(self, key: str) -> None:
        self.device_command("shell", "input", "keyevent", key)

    def wait_for_keyboard(self, shown: bool, timeout: float = 10.0) -> None:
        """Wait for the real IME state before judging result placement."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            state = self.device_command("shell", "dumpsys", "input_method").stdout
            visible = bool(re.search(r"mInputShown=true", state) and
                           re.search(r"mIsInputViewShown=true", state))
            if visible and shown:
                windows = self.device_command("shell", "dumpsys", "window", "windows").stdout
                ime = next((window for window in re.split(r"(?=  Window #\d+ )", windows)
                            if "u0 InputMethod}" in window), "")
                inset = re.search(r"mGivenContentInsets=\[0,(\d+)\]", ime)
                frame = re.search(r"\bframe=\[\d+,(\d+)\]\[\d+,(\d+)\]", ime)
                # Gboard can report shown while displaying only a floating
                # hardware-keyboard toolbar. Require a real resized viewport.
                visible = bool(inset and frame and
                               int(frame[2]) - int(frame[1]) - int(inset[1]) > 200)
            if visible == shown:
                # Let the resize animation's final layout land as well.
                time.sleep(0.3)
                return
            time.sleep(0.1)
        raise VisualError(f"Keyboard did not become {'visible' if shown else 'hidden'}.")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 450) -> None:
        self.device_command(
            "shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms)
        )

    def scroll_down(self, *, fraction: float = 0.45) -> None:
        """Swipes up inside the screen to reveal content below the fold."""
        nodes, _ = self.hierarchy()
        # Swipe across the extent of everything visible. Picking one "big"
        # node is fragile: a banner or card can be the first large node, and
        # a swipe inside it never travels far enough to scroll the screen.
        bounds: tuple[int, int, int, int] | None = None
        for node in nodes:
            if not node["visible"]:
                continue
            match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node["bounds"])
            if not match:
                continue
            x1, y1, x2, y2 = (int(part) for part in match.groups())
            if bounds is None:
                bounds = (x1, y1, x2, y2)
            else:
                bounds = (min(bounds[0], x1), min(bounds[1], y1), max(bounds[2], x2), max(bounds[3], y2))
        if bounds is not None and (bounds[2] - bounds[0] <= 100 or bounds[3] - bounds[1] <= 100):
            bounds = None
        if bounds is None:
            raise VisualError("Could not determine the screen bounds for scrolling.")
        x1, y1, x2, y2 = bounds
        start_y = y1 + int((y2 - y1) * 0.72)
        end_y = y1 + int((y2 - y1) * (0.72 - fraction))
        self.swipe((x1 + x2) // 2, start_y, (x1 + x2) // 2, end_y)

    def scroll_to_node(
        self,
        kind: str,
        value: str,
        *,
        contains: bool = False,
        max_swipes: int = 6,
    ) -> dict[str, Any]:
        """Scrolls the current screen until a visible node matches, then returns it."""
        for _ in range(max_swipes):
            matches = self.matching_nodes(kind, value, contains=contains)
            if matches:
                return matches[0]
            self.scroll_down()
            time.sleep(0.5)
        return self.wait_for_node(kind, value, contains=contains, timeout=5)

    def broadcast_maps_preview(self, scenario: str) -> None:
        """Shows one deterministic Maps-screen state (debug builds only)."""
        self.device_command(
            "shell", "am", "broadcast",
            "-a", MAPS_PREVIEW_ACTION,
            "-p", APP_ID,
            "--es", "scenario", scenario,
            check=False,
        )
        time.sleep(0.3)

    def capture(self, label: str, *, include_tree: bool = True) -> Path:
        self.ensure_capture_dirs()
        self.sequence += 1
        safe_label = re.sub(r"[^a-zA-Z0-9_-]+", "-", label).strip("-") or "screen"
        png = self.raw_device_bytes("exec-out", "screencap", "-p", timeout=60)
        if not png.startswith(b"\x89PNG\r\n\x1a\n"):
            raise VisualError("The emulator did not return a PNG screenshot.")
        file_path = self.run_dir / f"{self.sequence:02d}-{safe_label}.png"
        file_path.write_bytes(png)
        latest_tmp = self.output_dir / "latest.png.tmp"
        latest_tmp.write_bytes(png)
        latest_tmp.replace(self.output_dir / "latest.png")

        visible_nodes: list[dict[str, Any]] = []
        if include_tree:
            try:
                visible_nodes, _ = self.hierarchy()
            except VisualError as error:
                visible_nodes = [{"text": f"accessibility dump failed: {error}"}]
        event = {
            "event": "screenshot",
            "label": label,
            "path": str(file_path),
            "latest": str(self.output_dir / "latest.png"),
            "sha256": hashlib.sha256(png).hexdigest(),
            "visible": [
                {"text": node["text"], "description": node["description"]}
                for node in visible_nodes if node["visible"]
            ],
        }
        self.run_dir.mkdir(parents=True, exist_ok=True)
        with (self.run_dir / "events.jsonl").open("a", encoding="utf-8") as events:
            events.write(json.dumps({"at": utc_now(), **event}, ensure_ascii=False) + "\n")
        emit("screenshot", **{key: value for key, value in event.items() if key != "event"})
        return file_path

    def ensure_capture_dirs(self) -> None:
        self.run_dir.mkdir(parents=True, exist_ok=True)
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def set_complexity(self, target: int) -> None:
        if target < 0:
            raise VisualError("Ride complexity must be zero or higher.")
        node = self.wait_for_node("description", LEVEL_PREFIX, contains=True, timeout=10)
        match = re.search(r"(\d+)\s*$", node["description"])
        if not match:
            raise VisualError(f"Could not read the current ride complexity: {node['description']!r}.")
        current = int(match.group(1))
        x1, y1, x2, y2 = self.center(node["bounds"])
        cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
        radius = min(x2 - x1, y2 - y1) * 0.44
        direction = 1 if target > current else -1
        for level in range(current, target, direction):
            start_angle = math.pi / 2
            end_angle = start_angle + direction * math.pi / 4
            sx = round(cx + math.cos(start_angle) * radius)
            sy = round(cy + math.sin(start_angle) * radius)
            ex = round(cx + math.cos(end_angle) * radius)
            ey = round(cy + math.sin(end_angle) * radius)
            self.swipe(sx, sy, ex, ey, 350)
            desired = level + direction
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                descriptions = [
                    candidate["description"]
                    for candidate in self.matching_nodes(
                        "description", LEVEL_PREFIX, contains=True
                    )
                ]
                if f"{LEVEL_PREFIX}{desired}" in descriptions:
                    break
                time.sleep(0.25)
            else:
                raise VisualError(
                    f"Complexity dial did not reach level {desired}; current labels: {descriptions}."
                )
        time.sleep(0.35)

    def set_route_settings(self, *, road_share: int | None, block_unpaved: bool | None) -> None:
        if road_share is None and block_unpaved is None:
            return
        if road_share is not None and (road_share < 10 or road_share > 90 or road_share % 5 != 0):
            raise VisualError("Road share must be 10–90 in steps of 5 percent.")
        self.open_settings_menu()
        self.capture("settings-menu-open")
        self.tap_node("text", "Route settings")
        self.wait_for_node("text", "Target maximum shared roads", timeout=10)
        self.capture("route-settings-open")

        if road_share is not None:
            share_node = self.wait_for_node(
                "description", "Maximum shared roads", contains=True, timeout=10
            )
            match = re.search(r"(\d+)\s+percent", share_node["description"])
            if not match:
                raise VisualError(f"Could not read the road-share dial: {share_node['description']!r}.")
            current = int(match.group(1))
            if current != road_share:
                x1, y1, x2, y2 = self.center(share_node["bounds"])
                cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
                radius = min(x2 - x1, y2 - y1) * 0.35
                start = math.radians(135 + ((current - 10) / 80) * 270)
                end = math.radians(135 + ((road_share - 10) / 80) * 270)
                self.swipe(
                    round(cx + math.cos(start) * radius),
                    round(cy + math.sin(start) * radius),
                    round(cx + math.cos(end) * radius),
                    round(cy + math.sin(end) * radius),
                    700,
                )
                self.wait_for_node(
                    "description", f"Maximum shared roads {road_share} percent", timeout=8
                )

        if block_unpaved is not None:
            switch = self.wait_for_node("description", "Block unpaved roads", timeout=8)
            if switch["checked"] != block_unpaved:
                self.tap_node("description", "Block unpaved roads")
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline:
                    updated = self.select_node("description", "Block unpaved roads")
                    if updated["checked"] == block_unpaved:
                        break
                    time.sleep(0.2)
                else:
                    raise VisualError("Block unpaved roads switch did not change to the requested state.")
        self.capture("route-settings-edited")
        self.tap_node("text", "APPLY")
        self.wait_for_any_text(("START", "RIDE"), timeout=60)
        self.capture("route-settings-applied")

    def open_settings_menu(self) -> None:
        if not self.matching_nodes("text", "Sound settings"):
            self.tap_node("description", "Planning settings")
        self.wait_for_node("text", "Sound settings", timeout=10)

    def capture_settings_menu(self, *, build: bool, skip_map: bool, refresh_map: bool) -> None:
        """Capture the planner menu card, a screen opened from it, and dismissal."""
        self.launch_app(build=build, skip_map=skip_map, refresh_map=refresh_map)
        self.open_settings_menu()
        self.capture("settings-menu-open")
        # Every screen opened from the card exits through the shared back
        # button, returning to the closed planner.
        self.tap_node("text", "Route settings")
        self.wait_for_node("description", "Back", timeout=10)
        self.capture("settings-screen-open")
        self.tap_node("description", "Back")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if not self.matching_nodes("text", "Target maximum shared roads"):
                break
            time.sleep(0.2)
        else:
            raise VisualError("The route-settings screen did not close after Back.")
        self.capture("settings-screen-back-to-planner")
        # Re-open the card and verify the system back button dismisses it.
        self.open_settings_menu()
        self.press_key("KEYCODE_BACK")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if not self.matching_nodes("text", "Sound settings"):
                break
            time.sleep(0.2)
        else:
            raise VisualError("Settings menu did not dismiss after Back.")
        self.capture("settings-menu-dismissed")

    def capture_search_results(self, args: argparse.Namespace) -> None:
        """Capture From's POI action and search while the IME changes available space."""
        self.ensure_device(start_if_missing=True)
        setting = "show_ime_with_hard_keyboard"
        previous = self.device_command("shell", "settings", "get", "secure", setting).stdout.strip()
        self.device_command("shell", "settings", "put", "secure", setting, "1")
        try:
            if previous != "1":
                ime = self.device_command("shell", "settings", "get", "secure", "default_input_method").stdout.strip()
                package = ime.partition("/")[0]
                if re.fullmatch(r"[A-Za-z0-9_.]+", package):
                    # The emulator's IME caches hardware-keyboard mode. Let
                    # Android rebind it with the temporary fixture setting.
                    self.device_command("shell", "am", "force-stop", package)
            self.launch_app(build=not args.no_build, skip_map=args.skip_map, refresh_map=args.refresh_map)
            self.capture("search-planner-poi-icon")
            self.tap_node("description", args.field)
            self.wait_for_keyboard(shown=True)
            self.capture("search-focused-keyboard")
            self.type_into("description", args.field, args.query)
            self.capture("search-query-entered", include_tree=False)
            self.wait_for_search_completion(args.field, args.query)
            self.wait_for_search_window()
            self.capture("search-results-keyboard-visible")
            self.press_key("KEYCODE_BACK")
            self.wait_for_keyboard(shown=False)
            self.capture("search-results-keyboard-dismissed")
        finally:
            if previous == "null":
                run_optional(self.device_command, "shell", "settings", "delete", "secure", setting)
            else:
                run_optional(self.device_command, "shell", "settings", "put", "secure", setting, previous)

    def wait_for_search_completion(self, field: str, query: str, timeout: float = 30.0) -> None:
        pid = self.device_command("shell", "pidof", APP_ID).stdout.strip().split()[0]
        expected = f'[{field}] "{query}" -> '
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            logs = self.device_command("logcat", "--pid=" + pid, "-d", "-v", "brief",
                                       "OrganicMoto.SearchField:D", "*:S").stdout
            matching = [line for line in logs.splitlines() if expected in line]
            if matching:
                if "no results" in matching[-1]:
                    raise VisualError(f"The fixture search {query!r} returned no results.")
                (self.run_dir / "search-completion.txt").write_text(matching[-1] + "\n")
                time.sleep(0.3)
                return
            time.sleep(0.1)
        raise VisualError(f"The fixture search {query!r} did not complete in app PID {pid}.")

    def wait_for_search_window(self, timeout: float = 15.0) -> None:
        # Legacy uiautomator dump omits nonfocusable Compose popup windows.
        # Observe WindowManager's actual drawn surface; all controls still use
        # their accessible labels, and Compose tests assert popup content.
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            windows = self.device_command("shell", "dumpsys", "window", "windows").stdout
            for window in re.split(r"(?=  Window #\d+ )", windows):
                if ("Pop-Up Window" in window and f"package={APP_ID}" in window
                        and "Surface: shown=true" in window and "isVisible=true" in window):
                    (self.run_dir / "search-results-window.txt").write_text(window)
                    return
            time.sleep(0.1)
        raise VisualError("The search results popup did not draw a visible window.")

    def capture_maps_settings(self, args: argparse.Namespace) -> None:
        """Capture the Maps screen: server, catalog, download/import/delete states, Back.

        The live server is never contacted: typing an address only fills the
        field, Check server is deliberately not tapped, and the intermediate
        download/import/delete states come from the debug Maps-preview bridge
        (VisualMapsPreview), so this scenario needs no network, no map server,
        and no SAF picker.
        """
        self.launch_app(build=not args.no_build, skip_map=args.skip_map, refresh_map=args.refresh_map)
        self.open_settings_menu()
        self.tap_node("text", "Maps")
        self.wait_for_node("description", "Map server address", timeout=15)
        self.wait_for_node("description", "Import package file", timeout=15)
        self.capture("maps-settings-empty")
        self.type_into("description", "Map server address", args.server, verify=True)
        self.press_key("KEYCODE_BACK")  # dismiss the IME
        self.capture("maps-settings-address")
        # A failed check shows a dismissible, actionable error banner.
        self.broadcast_maps_preview("server-error")
        self.wait_for_node("description", "Dismiss error", timeout=10)
        self.capture("maps-preview-server-error")

        # Deterministic intermediate states: a terminal job keeps Download
        # visible, a failed job keeps Retry visible, and every download/import
        # step offers its next explicit action.
        previews = (
            ("catalog-ready", "Download package queensland", "maps-preview-catalog-ready"),
            ("build-failed", "Retry package queensland", "maps-preview-build-failed"),
            ("download-progress", "Cancel download", "maps-preview-download-progress"),
            ("download-retrying", "Cancel download", "maps-preview-download-retrying"),
            ("download-verifying", "Cancel download", "maps-preview-download-verifying"),
            ("download-interrupted", "Resume download", "maps-preview-download-interrupted"),
            ("package-saved", "Import saved file", "maps-preview-package-saved"),
            ("recovered-package", "Save downloaded package to device", "maps-preview-recovered-package"),
        )
        for scenario, description, label in previews:
            self.broadcast_maps_preview(scenario)
            self.scroll_to_node("description", description)
            self.capture(label)

        # Installed maps list: real installed size, a rubbish bin per entry, and
        # a confirmation that names the dataset and the reclaimed space.
        self.broadcast_maps_preview("installed")
        self.scroll_to_node("description", "Delete Queensland map")
        self.capture("maps-preview-installed")
        self.tap_node("description", "Delete Queensland map")
        self.wait_for_node("text", "Delete installed map?", timeout=10)
        self.capture("maps-delete-confirmation")
        self.tap_node("description", "Cancel delete Queensland map")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if not self.matching_nodes("text", "Delete installed map?"):
                break
            time.sleep(0.2)
        else:
            raise VisualError("The delete confirmation did not dismiss after Cancel.")
        self.capture("maps-delete-cancelled")

        # Active-map deletion shows the pending drain state truthfully.
        self.broadcast_maps_preview("removal-pending")
        self.scroll_to_node("text", "releasing this map", contains=True)
        self.capture("maps-preview-removal-pending")
        self.broadcast_maps_preview("clear")

        # Back closes the Maps screen and returns to the planner rail.
        self.press_key("KEYCODE_BACK")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if not self.matching_nodes("description", "Map server address"):
                break
            time.sleep(0.2)
        else:
            raise VisualError("The Maps screen did not close after Back.")
        self.capture("maps-settings-back-to-planner")

    def apply_black_and_white(self, mode: str) -> None:
        """Toggles the ride-map style through its accessible control, no capture.

        The accessibility state changes before MapLibre finishes loading the
        alternate local style, so a real toggle waits for the style and route
        line to repaint; an already-correct state returns immediately.
        """
        if mode not in {"on", "off"}:
            raise VisualError("Black-and-white mode must be 'on' or 'off'.")
        target = f"Dark ride map, {mode}"
        enabled = mode == "on"
        opposite = f"Dark ride map, {'off' if enabled else 'on'}"
        if not self.matching_nodes("description", target):
            if not self.matching_nodes("description", opposite):
                raise VisualError(
                    "Black-and-white mode is available during an active ride; start a route first."
                )
            self.tap_node("description", opposite)
            self.wait_for_node("description", target, timeout=10)

        # Accessibility changes before style/tile/overlay loading completes.
        # Request-driven native queries are only a readiness gate; the captured
        # pixels still need inspection to prove readability and visibility.
        self.wait_for_ride_map(dark=enabled)
        time.sleep(0.5)

    def set_black_and_white(self, mode: str) -> None:
        self.apply_black_and_white(mode)
        self.capture(f"ride-map-black-and-white-{mode}")
        emit("ride_map_mode", black_and_white=mode == "on", screenshot=str(self.output_dir / "latest.png"))

    def capture_icon_states(self) -> None:
        """Capture appearance and speaker states through accessible controls."""
        self.capture("guidance-icons-initial")
        original_dark = bool(self.matching_nodes("description", "Dark ride map, on"))
        self.set_black_and_white("off")
        self.set_black_and_white("on")
        self.set_black_and_white("on" if original_dark else "off")
        self.tap_node("description", "Voice guidance settings", contains=True)
        self.capture("guidance-icons-voice-settings")
        switch = self.wait_for_node("description", "Spoken turn guidance", timeout=8)
        original_voice = switch["checked"]
        for index, enabled in enumerate((False, True, original_voice)):
            switch = self.wait_for_node("description", "Spoken turn guidance", timeout=8)
            if switch["checked"] != enabled:
                self.tap_node("description", "Spoken turn guidance")
            self.tap_node("text", "APPLY")
            time.sleep(1)
            self.capture(f"guidance-icons-voice-{'on' if enabled else 'off'}")
            if index < 2:
                self.tap_node("description", "Voice guidance settings", contains=True)

    def grant_media_listener(self, granted: bool) -> None:
        """Grant/revoke this app's notification-listener (media session) access."""
        component = (
            f"{APP_ID}/com.organicmoto.maps.media.MediaNotificationListenerService"
        )
        command = "allow_listener" if granted else "disallow_listener"
        self.device_command("shell", "cmd", "notification", command, component, check=False)
        time.sleep(1.5)

    def media_listener_granted(self) -> bool:
        """Whether this app's notification listener is currently enabled.

        Reads the platform's own enabled-listener setting (a colon-separated
        list), so the runner can restore the exact starting state instead of
        assuming access was off.
        """
        component = (
            f"{APP_ID}/com.organicmoto.maps.media.MediaNotificationListenerService"
        )
        completed = self.device_command(
            "shell",
            "settings",
            "get",
            "secure",
            "enabled_notification_listeners",
            check=False,
        )
        value = (completed.stdout or "").strip()
        return any(entry.strip() == component for entry in value.split(":"))

    def set_media_session(self, state: str, title: str, artist: str) -> None:
        """Drive the debug-only synthetic media session used for screenshots."""
        self.device_command(
            "shell", "am", "broadcast",
            "-a", "com.organicmoto.maps.DEBUG_VISUAL_MEDIA_SESSION",
            "-p", APP_ID,
            "--es", "state", state,
            "--es", "title", title,
            "--es", "artist", artist,
            check=False,
        )
        time.sleep(0.6)

    def capture_media_controls(self, args: argparse.Namespace) -> None:
        """Capture media states or the focused inactivity interaction."""
        if not args.reuse_app:
            self.launch_app(
                build=not args.no_build,
                skip_map=args.skip_map,
                refresh_map=args.refresh_map,
            )
        else:
            self.ensure_device(start_if_missing=False)
            self.wait_for_any_text(("START", "RIDE"), timeout=15)

        if not self.matching_nodes("text", "RIDE"):
            self.type_into("description", "From", args.from_text)
            self.type_into("description", "To", args.to_text)
            self.press_key("KEYCODE_ENTER")
            planner_action = self.wait_for_any_text(("START", "RIDE"), timeout=15)
            if planner_action["text"] == "START":
                self.tap_node("text", "START")
                self.wait_for_node("text", "RIDE", timeout=args.route_timeout)

        self.tap_node("text", "RIDE")
        self.wait_for_node("text", "END", timeout=30)
        self.capture("media-ride-active")
        if args.inactivity_only:
            self.capture_media_inactivity()
            return

        # Record the exact starting state so the finally block restores it: the
        # runner must never leave notification access force-granted for the
        # next command or another test.
        initial_access = self.media_listener_granted()
        original_dark = bool(self.matching_nodes("description", "Dark ride map, on"))
        panel_open = False
        try:
            self.grant_media_listener(True)

            # Playing, in colour and in B&W ride mode. Force colour first so a
            # persisted dark-map preference from an earlier run cannot make the
            # "colour" capture indistinguishable from the monochrome one.
            self.set_media_session("playing", args.title, args.artist)
            if original_dark:
                self.set_black_and_white("off")
            self.tap_node("description", "Media controls")
            panel_open = True
            self.wait_for_node("description", "Media control center", timeout=10)
            self.capture("media-panel-color-playing")
            self.tap_node("description", "Close media controls")
            panel_open = False
            # Style settling is independent of the inactivity timer; switch
            # with the panel closed and give each capture a fresh open window.
            self.set_black_and_white("on")
            self.tap_node("description", "Media controls")
            panel_open = True
            self.capture("media-panel-bw-playing")

            # Paused.
            self.tap_node("description", "Close media controls")
            panel_open = False
            self.set_media_session("paused", args.title, args.artist)
            time.sleep(1.0)
            self.tap_node("description", "Media controls")
            panel_open = True
            self.capture("media-panel-bw-paused")

            # No player (access still granted, session stopped).
            self.tap_node("description", "Close media controls")
            panel_open = False
            self.set_media_session("stop", args.title, args.artist)
            time.sleep(1.0)
            self.tap_node("description", "Media controls")
            panel_open = True
            self.wait_for_node("description", "Media control center", timeout=10)
            self.capture("media-panel-bw-no-player")

            # Permission needed (notification access revoked).
            self.tap_node("description", "Close media controls")
            panel_open = False
            self.grant_media_listener(False)
            self.tap_node("description", "Media controls")
            panel_open = True
            self.capture("media-panel-bw-permission-needed")
            # System Back closes the panel instead of leaving the ride; capture
            # the dismissed state as evidence.
            self.press_key("KEYCODE_BACK")
            panel_open = False
            time.sleep(0.5)
            self.capture("media-panel-dismissed-with-back")
            panel_open = True
            self.capture_media_inactivity()
            panel_open = False
        finally:
            # Restore exactly what the runner found, never force-grant.
            if panel_open:
                run_optional(self.tap_node, "description", "Close media controls")
            run_optional(self.set_media_session, "stop", args.title, args.artist)
            run_optional(self.grant_media_listener, initial_access)
            run_optional(self.set_black_and_white, "on" if original_dark else "off")
            run_optional(self.tap_node, "text", "END")
        emit("media_controls_captured", restored_notification_access=initial_access)

    def capture_media_inactivity(self) -> None:
        """Capture the real panel's open timer, reset, and inactivity close."""
        self.tap_node("description", "Media controls")
        panel = self.wait_for_node("description", "Media control center", timeout=10)
        opened_at = time.monotonic()
        x1, y1, x2, _ = self.center(panel["bounds"])
        self.capture("media-panel-inactivity-full", include_tree=False)
        time.sleep(max(0.0, 7.5 - (time.monotonic() - opened_at)))
        # Tap unused top padding: it must restart the timer without issuing a
        # transport or volume command.
        self.tap_point((x1 + x2) // 2, y1 + 2)
        interacted_at = time.monotonic()
        time.sleep(max(0.0, 7.5 - (time.monotonic() - interacted_at)))
        if not self.matching_nodes("description", "Media control center"):
            raise VisualError("A panel touch did not restart the media inactivity timer")
        self.capture("media-panel-inactivity-half", include_tree=False)
        deadline = interacted_at + 17.0
        while time.monotonic() < deadline:
            if not self.matching_nodes("description", "Media control center"):
                break
            time.sleep(0.25)
        else:
            raise VisualError("Media panel did not close 15 seconds after its last interaction")
        self.capture("media-panel-inactivity-closed", include_tree=False)
        emit("media_inactivity_captured", timer_reset_by="blank-panel-touch", close="15s inactivity")



    def route(
        self,
        args: argparse.Namespace,
        *,
        before_ride: Any = None,
    ) -> None:
        if args.plan_only and args.black_and_white is not None:
            raise VisualError("--black-and-white requires ride mode; omit --plan-only.")
        if not args.reuse_app:
            self.launch_app(
                build=not args.no_build,
                skip_map=args.skip_map,
                refresh_map=args.refresh_map,
            )
        else:
            self.ensure_device(start_if_missing=False)
            self.wait_for_any_text(("START", "RIDE"), timeout=15)

        self.set_complexity(args.complexity)
        self.set_route_settings(road_share=args.road_share, block_unpaved=args.block_unpaved)
        self.capture("01-options-ready")

        from_current_location = getattr(args, "from_current_location", False)
        from_endpoint_text = "Current location" if from_current_location else args.from_text
        current_location: tuple[float, float] | None = None
        if from_current_location:
            if not self.serial or not self.serial.startswith("emulator-"):
                raise VisualError("The current-location visual scenario requires an Android emulator.")
            try:
                latitude_text, longitude_text = args.location.split(",", 1)
                latitude, longitude = float(latitude_text), float(longitude_text)
            except (AttributeError, ValueError) as error:
                raise VisualError("--location must be a latitude,longitude pair.") from error
            if (
                not all(map(math.isfinite, (latitude, longitude)))
                or not -90.0 <= latitude <= 90.0
                or not -180.0 <= longitude <= 180.0
            ):
                raise VisualError("--location must contain valid geographic coordinates.")
            current_location = (latitude, longitude)
            self.device_command("emu", "geo", "fix", str(longitude), str(latitude))
            self.tap_node("description", "Use current location")
            self.wait_for_node("text", "Current location", timeout=20)
            self.wait_for_node("text", "START", timeout=10)
            self.capture("02-current-location-selected")
        else:
            self.type_into("description", "From", args.from_text)
            time.sleep(0.4)
            self.capture("02-from-entered")
        self.type_into("description", "To", args.to_text)
        self.press_key("KEYCODE_ENTER")
        planner_action = self.wait_for_any_text(("START", "RIDE"), timeout=15)
        self.capture("03-endpoints-ready")

        if planner_action["text"] == "START":
            self.tap_node("text", "START")
            emit(
                "routing_started",
                from_text=from_endpoint_text,
                to_text=args.to_text,
                complexity=args.complexity,
                road_share=args.road_share,
                block_unpaved=args.block_unpaved,
            )
            self.capture("04-routing")
            deadline = time.monotonic() + args.timeout
            next_progress = time.monotonic() + args.progress_interval
            progress_index = 1
            while time.monotonic() < deadline:
                nodes = self.matching_nodes("text", "RIDE")
                if nodes:
                    break
                errors = self.matching_nodes("text", "No match", contains=True)
                if errors:
                    self.capture("route-error")
                    raise VisualError("Route failed: " + errors[0]["text"])
                if time.monotonic() >= next_progress:
                    self.capture(f"routing-{progress_index:02d}")
                    progress_index += 1
                    next_progress = time.monotonic() + args.progress_interval
                time.sleep(0.8)
            else:
                self.capture("route-timeout")
                logs = self.app_logs(lines=50)
                raise VisualError(
                    f"Routing did not reach the RIDE state within {args.timeout:g}s.\n"
                    f"Recent app logs:\n{logs}"
                )
            emit("existing_route_ready", from_text=from_endpoint_text, to_text=args.to_text)

        self.wait_for_node("text", "RIDE", timeout=10)
        if args.route_index > 1:
            self.tap_node("description", f"Route {args.route_index}:", contains=True)
            time.sleep(1)
        self.capture("05-route-ready")

        if args.plan_only:
            return
        snapshot = self.wait_for_route_snapshot(args.route_index - 1)
        selected_route = snapshot["routes"][args.route_index - 1]
        start = point_at_percent(selected_route["points"], 0.0)
        if current_location is not None and distance_metres(
            start[0],
            start[1],
            current_location[0],
            current_location[1],
        ) > 1_500.0:
            raise VisualError(
                "The route did not start near the selected emulator location; "
                "the From coordinate may be stale."
            )
        if not self.serial or not self.serial.startswith("emulator-"):
            emit("gps_start_not_changed", reason="selected ADB device is not an Android emulator")
        else:
            self.device_command("emu", "geo", "fix", str(start[1]), str(start[0]))
        if before_ride is not None:
            before_ride()
        self.tap_node("text", "RIDE")
        self.wait_for_node("text", "END", timeout=30)
        if self.serial and self.serial.startswith("emulator-"):
            self.inject_location(start[0], start[1])
            time.sleep(args.step_delay)
            self.capture("ride-start-location")
        # Let the ride HUD and map camera settle before saving the first ride frame.
        time.sleep(5)
        self.capture("06-ride-active")
        if args.black_and_white is not None:
            modes = ("on", "off") if args.black_and_white == "both" else (args.black_and_white,)
            for mode in modes:
                self.set_black_and_white(mode)
        if args.progress_percent is not None:
            self.progress_to(args.progress_percent, args.step_delay)

    def read_route_snapshot(self) -> dict[str, Any]:
        raw = self.raw_device_bytes(
            "exec-out", "run-as", APP_ID, "cat", ROUTE_SNAPSHOT, timeout=30
        )
        try:
            snapshot = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise VisualError(f"Could not read the debug route geometry export: {error}") from error
        if snapshot.get("version") != 1 or not snapshot.get("routes"):
            raise VisualError("The debug route geometry export is empty or has an unsupported version.")
        return snapshot

    def wait_for_route_snapshot(self, selected_index: int, timeout: float = 20) -> dict[str, Any]:
        if selected_index < 0:
            raise VisualError("Route index must be one or greater.")
        deadline = time.monotonic() + timeout
        last_error = "route geometry has not been written yet"
        while time.monotonic() < deadline:
            try:
                snapshot = self.read_route_snapshot()
                if selected_index < len(snapshot["routes"]) and snapshot.get("selectedIndex") == selected_index:
                    return snapshot
                last_error = (
                    f"selected route {snapshot.get('selectedIndex')} is not route {selected_index + 1}"
                )
            except VisualError as error:
                last_error = str(error)
            time.sleep(0.25)
        raise VisualError(f"Timed out waiting for route geometry: {last_error}")

    def inject_location(
        self,
        lat: float,
        lon: float,
        *,
        speed_mps: float | None = None,
        timeout: float = 20,
    ) -> None:
        if not self.serial or not self.serial.startswith("emulator-"):
            raise VisualError(
                "Percentage route progress needs an Android emulator; ADB location injection "
                "is not available on a physical device."
            )
        request_id = time.time_ns()
        arguments = [
            "shell", "am", "broadcast",
            "-a", "com.organicmoto.maps.DEBUG_VISUAL_ROUTE_FIX",
            "-p", APP_ID,
            "--es", "request_id", str(request_id),
            "--es", "lat", f"{lat:.7f}",
            "--es", "lon", f"{lon:.7f}",
        ]
        if speed_mps is not None:
            if not math.isfinite(speed_mps) or speed_mps < 0.0:
                raise VisualError("Injected speed must be finite and non-negative metres/second.")
            arguments += ["--ef", "speed", f"{speed_mps:.4f}"]
        self.device_command(*arguments, timeout=timeout)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                raw_ack = self.raw_device_bytes(
                    "exec-out", "run-as", APP_ID, "cat", FIX_ACK, timeout=15
                )
                ack = json.loads(raw_ack.decode("utf-8"))
                if ack.get("requestId") == request_id:
                    # Keep this owned emulator's parked provider on the same
                    # point. UI inspection can exceed the 20 s debug authority
                    # window; a stale California fix must not pull the camera
                    # out of the offline Queensland map between screenshots.
                    self.device_command("emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}")
                    self.last_replay_fix = (lat, lon, speed_mps)
                    return
            except (VisualError, UnicodeDecodeError, json.JSONDecodeError):
                pass
            time.sleep(0.05)
        raise VisualError(
            "The app did not acknowledge the debug route fix. Start ride mode first and "
            "make sure the installed app is a debug build."
        )

    def read_camera(self) -> dict[str, Any] | None:
        """Reads the debug camera probe; None until the map has settled once."""
        try:
            raw = self.raw_device_bytes(
                "exec-out", "run-as", APP_ID, "cat", CAMERA_SNAPSHOT, timeout=15
            )
            camera = json.loads(raw.decode("utf-8"))
        except (VisualError, UnicodeDecodeError, json.JSONDecodeError):
            return None
        return camera if isinstance(camera, dict) else None

    def inspect_camera(self, lat: float, lon: float, zoom: float, tilt: float, bearing: float = 0.0) -> None:
        """Debug-only deterministic view, without synthetic map gestures."""
        self.device_command(
            "shell", "am", "broadcast", "-a", "com.organicmoto.maps.DEBUG_VISUAL_CAMERA", "-p", APP_ID,
            "--es", "request_id", str(time.time_ns()), "--es", "lat", str(lat), "--es", "lon", str(lon),
            "--es", "zoom", str(zoom), "--es", "tilt", str(tilt), "--es", "bearing", str(bearing),
        )
        camera = self.wait_for_camera("inspection view", lambda probe: camera_near(probe, lat, lon)
            and abs(probe_number(probe, "zoom") - zoom) < 0.1
            and abs(probe_number(probe, "tilt") - tilt) < 0.5)
        time.sleep(2)
        emit("inspection_camera", camera=camera)

    def wait_for_ride_map(self, *, dark: bool, timeout: float = 30.0) -> dict[str, Any]:
        # A style switch is asynchronous. Keep the replay's real session/camera
        # authoritative and exercise its first post-reload location update.
        if self.last_replay_fix is not None:
            lat, lon, speed = self.last_replay_fix
            self.inject_location(lat, lon, speed_mps=speed)
        deadline = time.monotonic() + timeout
        probe: dict[str, Any] | None = None
        while time.monotonic() < deadline:
            request_id = time.time_ns()
            self.device_command("shell", "am", "broadcast", "-a", "com.organicmoto.maps.DEBUG_VISUAL_MAP_PROBE",
                                "-p", APP_ID, "--es", "request_id", str(request_id))
            time.sleep(0.25)
            try:
                probe = json.loads(self.raw_device_bytes("exec-out", "run-as", APP_ID, "cat", "cache/visual-map-probe.json"))
                if (probe.get("requestId") == request_id and probe.get("dark") == dark
                        and probe.get("basemapFeatures", 0) > 0 and probe.get("routeReady") is True
                        and probe.get("riderFeatures", 0) > 0):
                    emit("ride_map_ready", probe=probe)
                    return probe
            except (VisualError, json.JSONDecodeError):
                pass
        raise VisualError(f"Ride map/route/rider never finished rendering after style switch: {probe}")

    def buildings(self, args: argparse.Namespace) -> None:
        """Offline house/city/occlusion/style/lifecycle evidence on an explicitly owned AVD."""
        if not self.requested_serial:
            raise VisualError("buildings requires --serial or ANDROID_SERIAL for an exclusively owned device.")
        self.ensure_device(start_if_missing=False)
        original = {key: self.device_command("shell", "settings", "get", "system", key).stdout.strip()
                    for key in ("accelerometer_rotation", "user_rotation")}
        airplane = self.device_command("shell", "settings", "get", "global", "airplane_mode_on").stdout.strip()
        permissions = ("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
        package_state = self.device_command("shell", "dumpsys", "package", APP_ID, check=False).stdout
        originally_granted = {permission: bool(re.search(re.escape(permission) + r": granted=true", package_state))
                              for permission in permissions}
        original_dark: bool | None = None
        locations = self.device_command("shell", "dumpsys", "location", check=False).stdout
        parked_gps = re.search(r"last location=Location\[gps (-?[\d.]+),(-?[\d.]+)", locations)
        try:
            self.device_command("shell", "settings", "put", "system", "accelerometer_rotation", "0")
            self.device_command("shell", "settings", "put", "system", "user_rotation", "0")
            self.launch_app(build=not args.no_build, skip_map=False, refresh_map=False)
            time.sleep(5)  # Allow the first live-location centring to finish before inspection.
            self.device_command("shell", "cmd", "connectivity", "airplane-mode", "enable")
            for label, lat, lon, zoom, tilt in (
                ("buildings-flat-overview", -27.4679, 153.0281, 13.0, 0.0),
                ("buildings-below-threshold", -27.4679, 153.0281, 13.9, 58.0),
                ("buildings-threshold", -27.4679, 153.0281, 14.0, 58.0),
                ("buildings-city-intermediate", -27.4679, 153.0281, 16.0, 58.0),
                ("buildings-city-towers", -27.4679, 153.0281, 18.0, 58.0),
                ("buildings-residential-houses", -27.4616, 153.0466, 18.5, 58.0),
            ):
                self.inspect_camera(lat, lon, zoom, tilt)
                self.capture(label)
            # A short real GraphHopper route through the dense CBD. All controls
            # are selected through semantics; fixes drive the real navigation session.
            route_args = argparse.Namespace(from_text="-27.4698,153.0251", to_text="-27.4570,153.0350",
                complexity=0, road_share=None, block_unpaved=None, route_index=1, timeout=300,
                progress_interval=5, progress_percent=None, step_delay=0.2, black_and_white=None,
                plan_only=False, reuse_app=True, no_build=True, skip_map=False, refresh_map=False)
            self.route(route_args)
            original_dark = bool(self.matching_nodes("description", "Dark ride map, on"))
            self.set_black_and_white("off")
            snapshot = self.read_route_snapshot()
            points = snapshot["routes"][snapshot["selectedIndex"]]["points"]
            cumulative, distance = cumulative_distance(points)
            for index, speed in enumerate((0.0, 8.0, 15.0, 23.0, 32.0)):
                offset = min(100.0 + index * 120.0, distance * 0.65)
                lat, lon = self.inject_route_fix(points, cumulative, offset, speed_mps=speed)
                self.wait_for_camera("navigation speed band", lambda p: p.get("guidance") is True
                    and camera_near(p, lat, lon) and abs(probe_number(p, "zoom") - guidance_zoom_band(speed)) < 0.25)
                time.sleep(2)
                self.wait_for_node("text", "END", timeout=10)
                self.wait_for_node("description", "Ride data: ", contains=True, timeout=10)
                self.capture(f"buildings-city-navigation-speed-{speed:g}")
            # Deliberately put towers between camera and street; pitched viewpoints
            # expose depth occlusion that layer-order checks cannot prove away.
            offset = min(580.0, distance * 0.65)
            lat, lon = self.inject_route_fix(points, cumulative, offset, speed_mps=0.0)
            time.sleep(1)
            for bearing in (45.0, 225.0):
                self.inject_route_fix(points, cumulative, offset, speed_mps=0.0)
                time.sleep(1)
                self.inspect_camera(lat, lon, 17.0, 58.0, bearing)
                self.capture(f"buildings-route-silhouette-{bearing:g}")
            self.progress_to(65.0, 0.2)
            self.set_black_and_white("on")
            self.set_black_and_white("off")
            self.device_command("shell", "settings", "put", "system", "user_rotation", "1")
            time.sleep(3)
            # This main-base app recreates the planner on orientation change
            # (navigation is not persisted). Start a fresh landscape ride rather
            # than pretending a stale session survived, or changing that policy.
            self.route(route_args)
            self.set_black_and_white("off")
            self.wait_for_node("text", "END", timeout=15)
            self.capture("buildings-landscape-navigation")
            self.tap_node("text", "END")
            self.wait_for_node("text", "START", timeout=15)
            self.capture("buildings-landscape-end-reachable")
            self.device_command("shell", "settings", "put", "system", "user_rotation", "0")
            time.sleep(3)
            self.route(route_args)
            self.set_black_and_white("on" if original_dark else "off")
            self.tap_node("text", "END")
            self.wait_for_node("text", "START", timeout=15)
            self.capture("buildings-end-planning-restore")
            self.device_command("shell", "am", "force-stop", APP_ID)
            self.device_command("shell", "am", "start", "-n", ACTIVITY)
            self.wait_for_node("text", "START", timeout=30)
            self.inspect_camera(-27.4616, 153.0466, 18.5, 58.0)
            self.capture("buildings-relaunch-installed-map-houses")
        finally:
            if original_dark is not None:
                try:
                    if self.matching_nodes("description", "Dark ride map, ", contains=True):
                        self.set_black_and_white("on" if original_dark else "off")
                except (VisualError, subprocess.TimeoutExpired, OSError) as error:
                    emit("appearance_restore_warning", message=str(error))
            self.device_command("shell", "cmd", "connectivity", "airplane-mode", "enable" if airplane == "1" else "disable", check=False)
            for key, value in original.items():
                if value == "null":
                    self.device_command("shell", "settings", "delete", "system", key, check=False)
                else:
                    self.device_command("shell", "settings", "put", "system", key, value, check=False)
            for permission, granted in originally_granted.items():
                if not granted:
                    self.device_command("shell", "pm", "revoke", APP_ID, permission, check=False)
            if parked_gps and self.serial and self.serial.startswith("emulator-"):
                self.device_command("emu", "geo", "fix", parked_gps.group(2), parked_gps.group(1), check=False)

    def installed_map_bytes(self) -> int | None:
        result = self.device_command(
            "shell", "run-as", APP_ID, "sh", "-c", "ls -l files/tiles/basemap.pmtiles",
            check=False, timeout=20,
        )
        match = re.search(r"\s(\d+)\s+\d{4}-\d{2}-\d{2}", result.stdout)
        return int(match.group(1)) if match else None

    def gpu_identity(self) -> dict[str, str]:
        """Best-effort GPU identity so a perf report can be attributed.

        Vulkan map rendering goes through ``cmd gpu vkjson``'s device; the
        emulator's OpenGL ES path is what ``dumpsys SurfaceFlinger`` reports.
        Both are optional evidence: a missing probe never fails the run.
        """
        identity: dict[str, str] = {}
        vkjson = self.device_command("shell", "cmd", "gpu", "vkjson", check=False, timeout=30).stdout
        match = re.search(r'"deviceName"\s*:\s*"([^"]*)"', vkjson)
        if match and match.group(1):
            identity["vulkanDeviceName"] = match.group(1)
        surface_flinger = self.device_command("shell", "dumpsys", "SurfaceFlinger", check=False, timeout=30).stdout
        gles = re.search(r"GLES:\s*(\S.*)", surface_flinger)
        if gles:
            identity["glesRenderer"] = gles.group(1).strip()
        return identity

    def set_emulator_location(self, lat: float, lon: float) -> None:
        if not self.serial or not self.serial.startswith("emulator-"):
            raise VisualError("Cold sweep positioning is available only on an Android emulator.")
        self.device_command("emu", "geo", "fix", str(lon), str(lat))

    def restart_app(self) -> None:
        self.device_command("shell", "am", "force-stop", APP_ID)
        self.device_command("shell", "am", "start", "-n", ACTIVITY)
        self.wait_for_node("text", "START", timeout=60)

    def queue_map_perf_sweep(self, plan: dict[str, Any]) -> int:
        request_id = int(plan["requestId"])
        payload = json.dumps(plan, separators=(",", ":"))
        # The device shell re-tokenizes adb's argument list, so the JSON must
        # reach `am` as one single-quoted argument (labels are already
        # restricted to [a-z0-9-], so no quote can appear inside the plan).
        if "'" in payload:
            raise VisualError("Perf sweep plans must not contain single quotes.")
        self.device_command(
            "shell",
            f"am broadcast -a {PERF_SWEEP_ACTION} -p {APP_ID} --es plan '{payload}'",
        )
        return request_id

    def read_map_perf_result(self, request_id: int, *, timeout: float) -> dict[str, Any]:
        path = f"{PERF_RESULT_PREFIX}{request_id}.json"
        deadline = time.monotonic() + timeout
        last_error = ""
        while time.monotonic() < deadline:
            try:
                raw = self.raw_device_bytes("exec-out", "run-as", APP_ID, "cat", path, timeout=20)
                result = json.loads(raw.decode("utf-8"))
                if isinstance(result, dict) and result.get("requestId") == request_id:
                    return result
            except (VisualError, UnicodeDecodeError, json.JSONDecodeError) as error:
                last_error = str(error)
            time.sleep(0.4)
        raise VisualError(
            f"Sweep {request_id} never exported a report within {timeout:.0f}s ({last_error})."
        )

    def run_perf_sweep(
        self,
        case: str,
        mode: str,
        lat: float,
        lon: float,
        zoom: float,
        phase: str,
        *,
        settle: bool,
        args: argparse.Namespace,
    ) -> dict[str, Any]:
        """Queues one sweep, waits for its exported report, and keeps evidence."""
        request_id = time.time_ns()
        label = f"{case}-{mode}-{phase}"
        plan = perf_sweep_plan(
            request_id=request_id,
            label=label,
            motion=args.motion,
            lat=lat,
            lon=lon,
            zoom=zoom,
            tilt=args.tilt,
            duration_ms=int(args.duration * 1000),
            building_mode=mode,
            settle=settle,
        )
        self.queue_map_perf_sweep(plan)
        if args.screenshot_motion:
            time.sleep(max(0.7, args.duration * 0.45))
            self.capture(f"{label}-motion", include_tree=False)
        result = self.read_map_perf_result(request_id, timeout=args.duration + 45)
        result["case"] = case
        result["phase"] = phase
        result["zoom"] = zoom
        result["requestedTilt"] = args.tilt
        self.ensure_capture_dirs()
        report_path = self.run_dir / f"{label}.json"
        report_path.write_text(json.dumps(result, indent=2), encoding="utf-8")
        all_stats = perf_segment(result)
        emit(
            "perf_sweep",
            case=case,
            mode=mode,
            phase=phase,
            label=label,
            report=str(report_path),
            completed=result.get("completed"),
            interrupted=result.get("interrupted"),
            frames=int(perf_number(all_stats, "frames")),
            fps=round(perf_primary_fps(result), 2),
            p50Ms=round(perf_number(all_stats, "p50Ms"), 2),
            p95Ms=round(perf_number(all_stats, "p95Ms"), 2),
            p99Ms=round(perf_number(all_stats, "p99Ms"), 2),
            stalls50=int(perf_number(all_stats, "stalls50")),
            stalls100=int(perf_number(all_stats, "stalls100")),
        )
        return result

    def perf(self, args: argparse.Namespace) -> None:
        """Repeatable native-frame benchmark of the real offline map.

        Cold runs measure tile/geometry loading on a fresh process (the app is
        restarted and parked far away first so the case's tiles are genuinely
        cold); warm runs repeat the same motion on the settled map. Building
        modes A/B the extrusion rendering with the identical camera plan.
        """
        if not self.requested_serial:
            raise VisualError("perf requires --serial or ANDROID_SERIAL for an exclusively owned device.")
        targets = [item.strip() for item in args.targets.split(",") if item.strip()]
        if not targets:
            raise VisualError("perf needs at least one target.")
        for target in targets:
            if target not in PERF_TARGETS:
                raise VisualError(
                    f"Unknown perf target '{target}'. Known targets: {', '.join(sorted(PERF_TARGETS))}."
                )
        zoom_plan = perf_zoom_plan(targets, args.zooms)
        modes = [item.strip().lower() for item in args.building_modes.split(",") if item.strip()]
        for mode in modes:
            if mode not in ("current", "opaque", "hidden"):
                raise VisualError(f"Unknown building mode '{mode}'.")
        if args.repeat < 1:
            raise VisualError("perf needs --repeat of at least 1.")
        if not 1.0 <= args.duration <= 60.0:
            raise VisualError("perf --duration must be between 1 and 60 seconds.")

        self.launch_app(build=not args.no_build, skip_map=args.skip_map, refresh_map=args.refresh_map)
        archive_bytes = self.installed_map_bytes()
        gpu = self.gpu_identity()
        original_location = None
        if self.serial and self.serial.startswith("emulator-"):
            locations = self.device_command("shell", "dumpsys", "location", check=False).stdout
            parked = re.search(r"last location=Location\[gps (-?[\d.]+),(-?[\d.]+)", locations)
            if parked:
                original_location = (float(parked.group(1)), float(parked.group(2)))
        results: list[dict[str, Any]] = []
        try:
            for mode in modes:
                for target in targets:
                    lat, lon = PERF_TARGETS[target]
                    for zoom in zoom_plan[target]:
                        case = f"brisbane-{target}-z{zoom:g}"
                        if not args.no_cold and mode == modes[0]:
                            # Park the emulator far away so the app's one-shot
                            # initial centring cannot warm the case tiles and
                            # cannot yank the camera mid-sweep.
                            if original_location is not None:
                                self.set_emulator_location(-16.9203, 145.7710)
                            self.restart_app()
                            time.sleep(args.settle)
                            results.append(
                                self.run_perf_sweep(case, mode, lat, lon, zoom, "cold", settle=False, args=args)
                            )
                        if not args.no_warm:
                            self.inspect_camera(lat, lon, zoom, args.tilt)
                            for repeat in range(1, args.repeat + 1):
                                results.append(
                                    self.run_perf_sweep(case, mode, lat, lon, zoom, "warm", settle=True, args=args)
                                )
                                if repeat == 1 and args.screenshots:
                                    # A settled view of the case camera is the
                                    # building-visibility evidence; it runs
                                    # after the sweep so it never perturbs the
                                    # measured frames.
                                    self.inspect_camera(lat, lon, zoom, args.tilt)
                                    self.capture(f"{case}-{mode}-view", include_tree=False)
        finally:
            if original_location is not None:
                self.set_emulator_location(*original_location)

        generated = utc_now()
        summary = {
            "generated": generated,
            "serial": self.serial,
            "avd": self.avd,
            "archiveBytes": archive_bytes,
            "deviceGpu": gpu,
            "zooms": {target: list(zoom_plan[target]) for target in targets},
            "durationSeconds": args.duration,
            "motion": args.motion,
            "tilt": args.tilt,
            "repeat": args.repeat,
            "sweeps": results,
        }
        self.ensure_capture_dirs()
        summary_path = self.run_dir / "perf-summary.json"
        summary_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
        report = perf_report_markdown(
            results,
            device=f"{self.serial} ({self.avd})",
            generated=generated,
            app_build="debug (assembleDebug; MapLibre backend per sweep 'backend' object)",
            archive_bytes=archive_bytes,
            device_gpu=gpu,
        )
        report_path = Path(args.report).expanduser()
        if not report_path.is_absolute():
            report_path = self.root / report_path
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(report, encoding="utf-8")
        emit(
            "perf_report",
            report=str(report_path),
            summary=str(summary_path),
            sweeps=len(results),
            warm=[result for result in results if result.get("phase") == "warm"],
        )

    def wait_for_camera(
        self,
        description: str,
        predicate: Any,
        *,
        timeout: float = 10.0,
        not_before_ms: int | None = None,
    ) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        camera: dict[str, Any] | None = None
        while time.monotonic() < deadline:
            candidate = self.read_camera()
            if candidate is not None:
                camera = candidate
                if not_before_ms is not None and candidate.get("createdAtMillis", 0) < not_before_ms:
                    time.sleep(0.25)
                    continue
                if predicate(candidate):
                    return candidate
            time.sleep(0.25)
        raise VisualError(
            f"The guidance camera never matched {description}. Last probe: {camera}"
        )

    def ride_progress_percent(self, total_distance_m: float) -> float:
        nodes, _ = self.hierarchy()
        hud = next(
            (node["description"] for node in nodes if node["description"].startswith("Ride data: ")),
            None,
        )
        if hud is None:
            raise VisualError("The ride HUD is not visible; start a route with RIDE before setting progress.")
        remaining = re.search(r",\s*([0-9,.]+)\s*(km|m) left,", hud)
        if not remaining:
            raise VisualError(f"Could not read remaining route distance from the HUD: {hud!r}.")
        remaining_m = float(remaining.group(1).replace(",", ""))
        if remaining.group(2) == "km":
            remaining_m *= 1000
        covered_m = max(0.0, min(total_distance_m, total_distance_m - remaining_m))
        return 100.0 * covered_m / total_distance_m

    def progress_to(
        self,
        target_percent: float,
        step_delay: float = 0.15,
    ) -> None:
        if not 0.0 <= target_percent <= 100.0:
            raise VisualError("Route progress must be between 0 and 100 percent.")
        if step_delay < 0.0 or step_delay > 5.0:
            raise VisualError("Step delay must be between 0 and 5 seconds.")
        if not self.serial or not self.serial.startswith("emulator-"):
            raise VisualError("Percentage route progress is available only on an Android emulator.")

        self.wait_for_node("text", "END", timeout=10)
        initial_snapshot = self.read_route_snapshot()
        snapshot_index = int(initial_snapshot.get("selectedIndex", 0))
        snapshot = self.wait_for_route_snapshot(snapshot_index)
        route = snapshot["routes"][snapshot_index]
        points = route["points"]
        cumulative, total_distance = cumulative_distance(points)
        if total_distance <= 0.0:
            raise VisualError("The selected route has no measurable length.")

        current_percent = self.ride_progress_percent(total_distance)
        restarted = target_percent + 0.5 < current_percent
        if restarted:
            self.tap_node("text", "END")
            self.wait_for_node("text", "START", timeout=15)
            self.capture("progress-restart-planner")
            self.tap_node("text", "START")
            route_deadline = time.monotonic() + 300
            next_progress_capture = time.monotonic() + 2
            while time.monotonic() < route_deadline:
                if self.matching_nodes("text", "RIDE"):
                    break
                errors = self.matching_nodes("text", "No match", contains=True)
                if errors:
                    self.capture("progress-restart-route-error")
                    raise VisualError("Could not restore the original route: " + errors[0]["text"])
                if time.monotonic() >= next_progress_capture:
                    self.capture("progress-restarting-route")
                    next_progress_capture = time.monotonic() + 2
                time.sleep(0.8)
            else:
                self.capture("progress-restart-timeout")
                raise VisualError("Timed out recalculating the route after a backward progress jump.")

            refreshed = self.wait_for_route_snapshot(0)
            refreshed_index = min(snapshot_index, len(refreshed["routes"]) - 1)
            if refreshed_index > 0:
                self.tap_node("description", f"Route {refreshed_index + 1}:", contains=True)
                time.sleep(0.5)
                refreshed = self.wait_for_route_snapshot(refreshed_index)
            route = refreshed["routes"][refreshed_index]
            points = route["points"]
            cumulative, total_distance = cumulative_distance(points)
            if total_distance <= 0.0:
                raise VisualError("The recalculated route has no measurable length.")
            self.capture("progress-restarted-route-ready")
            self.tap_node("text", "RIDE")
            self.wait_for_node("text", "END", timeout=20)
            start_lat, start_lon = point_at_offset(points, cumulative, 0.0)
            self.inject_location(start_lat, start_lon)
            time.sleep(max(0.1, step_delay))
            current_percent = 0.0
            self.capture("progress-restarted-at-start")

        start_offset = total_distance * current_percent / 100.0
        target_offset = total_distance * target_percent / 100.0
        if target_offset < start_offset:
            start_offset = 0.0
        start_segment = max(0, bisect.bisect_right(cumulative, start_offset) - 1)
        target_segment = max(0, bisect.bisect_right(cumulative, target_offset) - 1)
        # NavigationSession searches at most 40 vertices ahead. Keeping these
        # injected fixes within 25 vertices makes large percentage jumps follow
        # the real matcher path instead of teleporting past its search window.
        fix_offsets: list[float] = []
        segment = start_segment + 25
        while segment < len(points) - 1 and segment < target_segment:
            fix_offsets.append(cumulative[segment])
            segment += 25
        if target_offset > start_offset or not fix_offsets:
            fix_offsets.append(target_offset)

        capture_indices = {
            max(0, round((len(fix_offsets) - 1) * fraction / 6))
            for fraction in range(1, 7)
        }
        capture_indices.discard(0)
        for index, offset in enumerate(fix_offsets):
            lat, lon = point_at_offset(points, cumulative, offset)
            self.inject_location(lat, lon)
            if step_delay:
                time.sleep(step_delay)
            if index in capture_indices:
                self.capture(f"progress-{target_percent:g}-step-{index + 1}")

        # The emulator accepts the final GPS fix asynchronously. Wait until
        # the HUD reflects the requested point (rounded to whole distance units).
        tolerance_percent = max(0.5, 1200.0 / total_distance * 100.0)
        deadline = time.monotonic() + 20
        observed_percent = current_percent
        while time.monotonic() < deadline:
            observed_percent = self.ride_progress_percent(total_distance)
            if abs(observed_percent - target_percent) <= tolerance_percent:
                break
            time.sleep(0.5)
        else:
            raise VisualError(
                f"Navigation HUD reached {observed_percent:.1f}% instead of "
                f"{target_percent:.1f}% after {len(fix_offsets)} GPS fixes."
            )
        self.capture(f"progress-{target_percent:g}-percent")
        emit(
            "route_progress",
            requested_percent=target_percent,
            observed_percent=round(observed_percent, 1),
            fixes_sent=len(fix_offsets),
            restarted_from_start=restarted,
            coordinate=point_at_offset(points, cumulative, target_offset),
        )

    def inject_route_fix(
        self,
        points: list[list[float]],
        cumulative: list[float],
        offset_m: float,
        *,
        speed_mps: float | None = None,
    ) -> tuple[float, float]:
        lat, lon = point_at_offset(points, cumulative, offset_m)
        self.inject_location(lat, lon, speed_mps=speed_mps)
        return lat, lon

    def inject_route_span(
        self,
        points: list[list[float]],
        cumulative: list[float],
        start_m: float,
        end_m: float,
        *,
        step_m: float,
        speed_mps: float | None,
    ) -> tuple[float, float]:
        if step_m <= 0.0:
            raise VisualError("The turn step must be a positive distance in metres.")
        steps = max(1, int(round(abs(end_m - start_m) / step_m)))
        position = (0.0, 0.0)
        for index in range(steps + 1):
            offset = start_m + (end_m - start_m) * index / steps
            position = self.inject_route_fix(points, cumulative, offset, speed_mps=speed_mps)
            time.sleep(0.2)
        return position

    @staticmethod
    def settle_for_capture(args: argparse.Namespace) -> None:
        """Lets the 600 ms camera animation and the repaint frame land before capturing."""
        time.sleep(max(0.0, args.settle))

    @staticmethod
    def ride_map_modes(black_and_white: str | None) -> list[str | None]:
        """Requested ride-map styles per frame; `None` leaves the style alone."""
        if black_and_white is None:
            return [None]
        if black_and_white == "both":
            return ["off", "on"]
        return [black_and_white]

    def capture_ride_frame(self, args: argparse.Namespace, label: str) -> None:
        """Captures one guidance frame once per requested ride-map style.

        With `--black-and-white both`, every guidance frame (street-close,
        each speed band's z14-z18 zoom, and the turn) is saved in colour and in
        B&W, including the compact sound/moon rail and its toggle states.
        The street-close frames show the raised arrow and its dark grayscale
        outline over the routed road at the same camera state.
        A single requested mode keeps the historical label with
        no suffix, so existing runs keep their artifact names.
        """
        for mode in self.ride_map_modes(args.black_and_white):
            suffix = ""
            if args.black_and_white == "both":
                suffix = "-bw" if mode == "on" else "-color"
            if mode is not None:
                self.apply_black_and_white(mode)
            self.capture(label + suffix)
            if mode is not None:
                emit(
                    "ride_map_mode_frame",
                    label=label + suffix,
                    black_and_white=mode == "on",
                    screenshot=str(self.output_dir / "latest.png"),
                )

    def capture_follow_pause(self, args: argparse.Namespace, lat: float, lon: float) -> None:
        """Pan inside accessible map bounds, capture the pause, then prove automatic return."""
        for mode in self.ride_map_modes(args.black_and_white):
            if mode is not None:
                self.apply_black_and_white(mode)
            suffix = "-bw" if mode == "on" else "-color" if mode == "off" else ""
            self.wait_for_camera("locked follow before pan", lambda probe: camera_near(probe, lat, lon))
            if self.matching_nodes("description", "Map"):
                node = self.select_node("description", "Map")
            else:
                node = self.select_node(
                    "description", "Showing a Map created with MapLibre", contains=True,
                )
            left, top, right, bottom = self.center(node["bounds"])
            width, height = right - left, bottom - top
            self.swipe(
                int(left + width * 0.30), int(top + height * 0.45),
                int(left + width * 0.65), int(top + height * 0.50), 500,
            )
            paused = self.read_camera()
            if paused is None or not paused.get("followSuspended"):
                raise VisualError(f"A locked map pan did not report its follow pause: {paused}")
            self.capture("nav-camera-gesture-paused" + suffix, include_tree=False)
            camera = self.wait_for_camera(
                "automatic return after the gesture pause",
                lambda probe: not probe.get("followSuspended") and camera_near(probe, lat, lon)
                and abs(probe_number(probe, "tilt") - GUIDANCE_TILT_DEGREES) <= 1.0,
            )
            self.wait_for_node("description", "Rider lock on; tap to release", timeout=10)
            self.capture("nav-camera-gesture-returned" + suffix)
            emit("nav_camera", stage="gesture-pause-return", paused=paused, camera=camera)

    def navigation_camera(self, args: argparse.Namespace) -> None:
        """Captures the guidance camera and rider-lock states during a ride.

        Repeatable frames cover navigation start, street-close framing, rider
        lock release while fixes advance, relock at the latest fix, one frame
        per speed band, an off-rider inspection with a lock cycle, a heading
        turn, and camera restore after END. The debug camera probe makes each
        state checkable, not only viewable. Queued debug fixes own the guidance
        session while they flow, so the emulator's live provider cannot pull
        the camera away. With `--black-and-white both`, every frame is captured
        in colour and B&W. The street-close and speed-band frames also show the
        raised arrow and its outline against the full-width white routed road.
        """
        self.navigation_camera_steps(args)

    def navigation_camera_steps(self, args: argparse.Namespace) -> None:
        if args.plan_only:
            raise VisualError("nav-camera starts guidance; omit --plan-only.")
        speeds = parse_speed_cases(args.speed_cases)
        run_started_ms = time.time_ns() // 1_000_000
        planning_holder: list[dict[str, Any] | None] = []
        self.route(args, before_ride=lambda: planning_holder.append(self.read_camera()))
        planning_camera = planning_holder[0] if planning_holder else None
        self.capture_ride_frame(args, "nav-camera-00-guidance-start")

        snapshot = self.read_route_snapshot()
        index = int(snapshot.get("selectedIndex", 0))
        route = snapshot["routes"][index]
        points = route["points"]
        cumulative, total_distance = cumulative_distance(points)
        if total_distance <= 0.0:
            raise VisualError("The selected route has no measurable length.")

        # Street-close framing: stationary at the closest band, rider low in
        # the viewport, forward tilt. This is the frame the reference view
        # describes. Ride the first metres rather than teleporting, so the
        # session's forward vertex window always contains the next fix.
        street_offset = min(max(40.0, total_distance * 0.01), total_distance * 0.5)
        street_lat, street_lon = self.inject_route_span(
            points,
            cumulative,
            min(40.0, street_offset),
            street_offset,
            step_m=ROUTE_FOLLOW_STEP_M,
            speed_mps=0.0,
        )
        camera = self.wait_for_camera(
            "street-close framing",
            lambda probe: probe.get("guidance") is True
            and camera_near(probe, street_lat, street_lon)
            and abs(probe_number(probe, "zoom") - guidance_zoom_band(0.0)) <= 0.25
            and abs(probe_number(probe, "tilt") - GUIDANCE_TILT_DEGREES) <= 1.0,
            not_before_ms=run_started_ms - 2_000,
        )
        self.settle_for_capture(args)
        self.capture_ride_frame(args, "nav-camera-01-street-close")
        emit("nav_camera", stage="street-close", camera=camera)

        expected_zoom = guidance_zoom_band(0.0)
        self.tap_node("description", "Zoom out")
        initial_zoomed_out = self.wait_for_camera(
            "ride zoom-out before releasing the rider lock",
            lambda probe: probe.get("guidance") is True
            and camera_near(probe, street_lat, street_lon)
            and probe_number(probe, "zoom") < expected_zoom - 0.5
        )
        self.capture_ride_frame(args, "nav-camera-lock-on-zoomed-out")
        # Switching the ride style restarts the guidance marker collector, which
        # can restore its speed-band zoom after the earlier zoom-out snapshot.
        # Reapply the inspection zoom after the final style capture so the lock
        # release comparison uses a fresh settled native camera snapshot.
        self.tap_node("description", "Zoom out")
        zoomed_out = self.wait_for_camera(
            "ride zoom-out after the final style capture",
            lambda probe: probe.get("guidance") is True
            and probe.get("cameraLocked") is True
            and camera_near(probe, street_lat, street_lon)
            and probe_number(probe, "zoom") < expected_zoom - 0.5
            and probe.get("createdAtMillis", 0) > initial_zoomed_out.get("createdAtMillis", 0),
        )
        self.tap_node("description", "Rider lock on; tap to release")
        self.wait_for_node("description", "Rider lock off; tap to follow", timeout=10)
        unlocked_offset = min(street_offset + 200.0, total_distance * 0.5)
        unlocked_lat, unlocked_lon = self.inject_route_span(
            points,
            cumulative,
            street_offset,
            unlocked_offset,
            step_m=ROUTE_FOLLOW_STEP_M,
            speed_mps=0.0,
        )
        expected_relock_bearing = bearing_degrees(
            point_at_offset(points, cumulative, max(0.0, unlocked_offset - 40.0)),
            point_at_offset(points, cumulative, min(total_distance, unlocked_offset + 40.0)),
        )
        self.settle_for_capture(args)
        camera = self.wait_for_camera(
            "released lock to preserve the inspection camera while fixes advance",
            lambda probe: probe.get("guidance") is True
            and camera_near(probe, street_lat, street_lon)
            and abs(probe_number(probe, "zoom") - probe_number(zoomed_out, "zoom")) <= 0.25
        )
        self.capture_ride_frame(args, "nav-camera-rider-lock-off")
        emit(
            "nav_camera",
            stage="rider-lock-off",
            rider=(unlocked_lat, unlocked_lon),
            camera=camera,
        )

        self.tap_node("description", "Rider lock off; tap to follow")
        camera = self.wait_for_camera(
            "rider lock to restore guidance framing at the latest fix",
            lambda probe: probe.get("guidance") is True
            and camera_near(probe, unlocked_lat, unlocked_lon)
            and abs(probe_number(probe, "zoom") - expected_zoom) <= 0.25
            and abs(probe_number(probe, "tilt") - GUIDANCE_TILT_DEGREES) <= 1.0
            and bearing_distance_degrees(
                probe_number(probe, "bearing"),
                expected_relock_bearing,
            ) <= 8.0,
        )
        self.capture_ride_frame(args, "nav-camera-rider-lock-relocked")
        emit("nav_camera", stage="rider-lock-reenabled", camera=camera)

        self.capture_follow_pause(args, unlocked_lat, unlocked_lon)

        # Use the debug inspection camera to create a repeatable off-rider
        # view, then exercise the same unlock/relock control without relying
        # on emulator-specific synthetic drag dispatch.
        off_rider_lat = unlocked_lat + 0.005
        off_rider_lon = unlocked_lon + 0.005
        self.inspect_camera(
            off_rider_lat,
            off_rider_lon,
            expected_zoom,
            25.0,
            expected_relock_bearing,
        )
        self.capture_ride_frame(args, "nav-camera-lock-on-inspection")
        self.tap_node("description", "Rider lock on; tap to release")
        self.wait_for_node("description", "Rider lock off; tap to follow", timeout=10)
        self.capture_ride_frame(args, "nav-camera-lock-off-inspection")
        self.tap_node("description", "Rider lock off; tap to follow")
        camera = self.wait_for_camera(
            "explicit relock from the off-rider inspection view",
            lambda probe: probe.get("guidance") is True
            and camera_near(probe, unlocked_lat, unlocked_lon)
            and abs(probe_number(probe, "zoom") - expected_zoom) <= 0.25
            and abs(probe_number(probe, "tilt") - GUIDANCE_TILT_DEGREES) <= 1.0,
        )
        self.capture_ride_frame(args, "nav-camera-recenter-restored")
        emit("nav_camera", stage="explicit-relock", camera=camera)

        # One frame per speed band; the rider keeps moving along the route so
        # the camera also has to re-centre, not just re-zoom.
        speed_offset = min(max(street_offset + 150.0, total_distance * 0.03), total_distance * 0.5)
        zoom_step = max(120.0, total_distance * 0.01)
        for speed in speeds:
            expected_zoom = guidance_zoom_band(speed)
            next_offset = min(speed_offset + zoom_step, total_distance * 0.8)
            fix_lat, fix_lon = self.inject_route_span(
                points,
                cumulative,
                speed_offset,
                next_offset,
                step_m=ROUTE_FOLLOW_STEP_M,
                speed_mps=speed,
            )
            camera = self.wait_for_camera(
                f"zoom band {expected_zoom:g} at {speed:g} m/s",
                lambda probe, expected_zoom=expected_zoom, lat=fix_lat, lon=fix_lon: probe.get("guidance") is True
                and camera_near(probe, lat, lon)
                and abs(probe_number(probe, "zoom") - expected_zoom) <= 0.25,
            )
            self.settle_for_capture(args)
            self.capture_ride_frame(args, f"nav-camera-02-speed-{speed:g}")
            emit(
                "nav_camera",
                stage="speed-band",
                speed_mps=speed,
                expected_zoom=expected_zoom,
                camera=camera,
            )
            speed_offset = next_offset

        # Heading turn: ride a straight approach, then through a real corner,
        # so the heading-up camera and the rider chevron both rotate.
        turn = find_route_turn(
            points,
            cumulative,
            threshold_deg=args.turn_threshold,
            min_offset_m=max(speed_offset + args.turn_step, total_distance * 0.05),
            max_offset_m=total_distance * 0.95,
        )
        if turn is None:
            emit("nav_camera", stage="heading-turn", status="no-turn-found")
            self.capture_ride_frame(args, "nav-camera-03-straight-heading")
        else:
            turn_offset, incoming, outgoing = turn
            approach_end = max(0.0, turn_offset - 25.0)
            approach_lat, approach_lon = self.inject_route_span(
                points,
                cumulative,
                max(speed_offset, max(0.0, turn_offset - 200.0)),
                approach_end,
                step_m=args.turn_step,
                speed_mps=args.turn_speed,
            )
            camera = self.wait_for_camera(
                "turn approach heading",
                lambda probe, lat=approach_lat, lon=approach_lon: probe.get("guidance") is True
                and camera_near(probe, lat, lon)
                and bearing_distance_degrees(probe_number(probe, "bearing"), incoming) <= 30.0,
            )
            self.settle_for_capture(args)
            self.capture_ride_frame(args, "nav-camera-03-turn-approach")
            emit("nav_camera", stage="turn-approach", camera=camera, expected_bearing=incoming)

            self.inject_route_fix(
                points, cumulative, turn_offset + args.turn_step * 0.5, speed_mps=args.turn_speed
            )
            self.settle_for_capture(args)
            self.capture_ride_frame(args, "nav-camera-04-turn-mid")

            exit_end = turn_offset + args.turn_step * 5.0
            exit_lat, exit_lon = self.inject_route_span(
                points,
                cumulative,
                turn_offset + args.turn_step,
                exit_end,
                step_m=args.turn_step,
                speed_mps=args.turn_speed,
            )
            camera = self.wait_for_camera(
                "post-turn heading",
                lambda probe, lat=exit_lat, lon=exit_lon: probe.get("guidance") is True
                and camera_near(probe, lat, lon)
                and bearing_distance_degrees(probe_number(probe, "bearing"), outgoing) <= 35.0,
            )
            self.settle_for_capture(args)
            self.capture_ride_frame(args, "nav-camera-05-turn-exited")
            observed_delta = abs(
                bearing_delta_degrees(incoming, probe_number(camera, "bearing"))
            )
            expected_delta = abs(bearing_delta_degrees(incoming, outgoing))
            emit(
                "nav_camera",
                stage="turn-exited",
                camera=camera,
                observed_delta_deg=round(observed_delta, 1),
                expected_delta_deg=round(expected_delta, 1),
            )
            if observed_delta < max(10.0, expected_delta / 3.0):
                raise VisualError(
                    "The guidance camera did not follow the route heading through the turn "
                    f"(observed {observed_delta:.1f} deg of an expected {expected_delta:.1f} deg)."
                )

        # Exit restores the planning camera: no tilt, no heading lock.
        self.tap_node("text", "END")
        self.wait_for_node("text", "START", timeout=20)
        camera = self.wait_for_camera(
            "planning camera restore",
            lambda probe: probe.get("guidance") is not True
            and abs(probe_number(probe, "tilt")) <= 1.0,
            timeout=15.0,
        )
        self.settle_for_capture(args)
        self.capture("nav-camera-06-exit-restored-planning")
        emit("nav_camera", stage="exit-restore", camera=camera, planning_camera=planning_camera)
        if planning_camera is not None:
            zoom_drift = abs(probe_number(camera, "zoom") - probe_number(planning_camera, "zoom"))
            bearing_drift = bearing_distance_degrees(
                probe_number(camera, "bearing"), probe_number(planning_camera, "bearing")
            )
            if zoom_drift > 1.5 or (math.isfinite(bearing_drift) and bearing_drift > 15.0):
                raise VisualError(
                    "Exit did not restore the planning camera: "
                    f"zoom drift {zoom_drift:.2f}, bearing drift {bearing_drift:.2f}."
                )

    def lane_guidance(self, args: argparse.Namespace) -> None:
        """Rides the route and captures each maneuver that shows lane guidance.

        The guidance card exposes the lane strip as a "Lane guidance: ..."
        content description. Fixes advance in --step metres; each time a new
        lane recommendation appears, an approach frame is captured, then one
        more after riding past that maneuver so the strip's hand-over to the
        next turn is visible. Fails when the ride ends without any lane
        guidance, which catches a graph imported without lane data.
        """
        if args.plan_only:
            raise VisualError("lanes starts guidance; omit --plan-only.")
        self.route(args)
        self.capture_ride_frame(args, "lanes-00-guidance-start")
        snapshot = self.read_route_snapshot()
        route = snapshot["routes"][int(snapshot.get("selectedIndex", 0))]
        points = route["points"]
        cumulative, total_distance = cumulative_distance(points)
        seen: list[str] = []
        pending_exit: str | None = None
        offset = 0.0
        while offset < total_distance and len(seen) < args.max_captures:
            offset = min(total_distance, offset + args.step)
            self.inject_route_fix(points, cumulative, offset, speed_mps=args.speed)
            nodes = self.matching_nodes("description", "Lane guidance:", contains=True)
            guidance = self.matching_nodes("description", "Navigation guidance:", contains=True)
            card = guidance[0]["description"] if guidance else ""
            lane = nodes[0]["description"] if nodes else ""
            if pending_exit is not None and card != pending_exit:
                self.settle_for_capture(args)
                self.capture_ride_frame(args, f"lanes-{len(seen):02d}-after")
                emit("lanes", stage="after", offset_m=round(offset), card=card, lanes=lane or None)
                pending_exit = None
            if lane and f"{card}|{lane}" not in seen:
                seen.append(f"{card}|{lane}")
                self.settle_for_capture(args)
                self.capture_ride_frame(args, f"lanes-{len(seen):02d}-approach")
                emit("lanes", stage="approach", offset_m=round(offset), card=card, lanes=lane)
                pending_exit = card
        emit("lanes", stage="done", captured=len(seen), ridden_m=round(offset), total_m=round(total_distance))
        if not seen:
            raise VisualError(
                "No lane guidance appeared on this ride. Check that the graph was imported with "
                "tools/gh/MotoGraphImport.java (preflight checks for moto_lanes)."
            )

    def app_logs(self, *, lines: int) -> str:
        result = self.device_command(
            "logcat", "-d", "-t", str(lines),
            "-s", "OrganicMoto.RouteScreen:I", "OrganicMoto.Router:I",
            "OrganicMoto.SearchField:D", "OrganicMoto.GeocodeCtrl:I", "*:S",
            check=False,
            timeout=30,
        )
        return result.stdout.strip()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def perf_pan_metres(zoom: float) -> float:
    """Roughly one screen width of motion at [zoom], so every case sweeps the
    same amount of visible map instead of a few pixels at z14."""
    return 300.0 * 2.0 ** (18.0 - zoom)


def perf_zoom_plan(targets: list[str], zooms_argument: str) -> dict[str, list[float]]:
    """Parses the perf --zooms grammar into a zoom list per target.

    Comma-separated items are either a default zoom that applies to every
    target (`18`) or a target-specific zoom (`houses=18.5`). A target with at
    least one target-specific zoom uses only those; every other target uses the
    default zooms. This is what lets one run cover the acceptance matrix
    (`cbd=14,cbd=16,cbd=18,houses=18.5`) without re-running the command and
    hand-merging reports.
    """
    defaults: list[float] = []
    overrides: dict[str, list[float]] = {}
    for raw in zooms_argument.split(","):
        item = raw.strip()
        if not item:
            continue
        target: str | None = None
        value = item
        if "=" in item:
            head, _, tail = item.partition("=")
            target = head.strip()
            value = tail.strip()
            if target not in targets:
                raise VisualError(
                    f"Zoom override '{item}' names target '{target}', which --targets did not select."
                )
        try:
            zoom = float(value)
        except ValueError as error:
            raise VisualError(f"Invalid perf zoom '{item}'.") from error
        if not 0.0 <= zoom <= 22.0:
            raise VisualError(f"Perf zoom '{item}' is outside the supported 0..22 range.")
        if target is None:
            defaults.append(zoom)
        else:
            overrides.setdefault(target, []).append(zoom)
    plan = {target: list(overrides.get(target, defaults)) for target in targets}
    missing = [target for target, values in plan.items() if not values]
    if missing:
        raise VisualError(
            "No zoom selected for perf target(s): "
            + ", ".join(missing)
            + ". Pass default zooms or `target=zoom` overrides."
        )
    return plan


def perf_sweep_plan(
    *,
    request_id: int,
    label: str,
    motion: str,
    lat: float,
    lon: float,
    zoom: float,
    tilt: float,
    duration_ms: int,
    building_mode: str,
    settle: bool,
) -> dict[str, Any]:
    """Builds the JSON plan the debug receiver queues; mirrors MapPerfSweepRequest."""
    pan_m = perf_pan_metres(zoom)
    d_lat = (pan_m * math.cos(math.radians(45.0))) / 111_320.0
    d_lon = (pan_m * math.sin(math.radians(45.0))) / (
        111_320.0 * math.cos(math.radians(lat))
    )
    return {
        "requestId": request_id,
        "label": label,
        "motion": motion,
        "lat": lat,
        "lon": lon,
        "latEnd": lat + d_lat,
        "lonEnd": lon + d_lon,
        "zoomStart": zoom,
        "zoomEnd": min(zoom + 1.0, 18.5),
        "tilt": tilt,
        "bearingStart": 0.0,
        "bearingEnd": 90.0,
        "durationMs": duration_ms,
        "settle": settle,
        "buildingMode": building_mode,
    }


def perf_segment(result: dict[str, Any], name: str = "all") -> dict[str, Any]:
    segments = result.get("segments") or {}
    segment = segments.get(name)
    return segment if isinstance(segment, dict) else {}


def perf_number(source: dict[str, Any], key: str) -> float:
    value = source.get(key)
    return float(value) if isinstance(value, (int, float)) else 0.0


def perf_primary_fps(result: dict[str, Any]) -> float:
    """The primary cadence evidence for one sweep.

    `nativeFps.harmonic` comes from MapLibre's per-render-thread frame listener
    (`1e9/delta` per frame, elapsed-average recovered by the app, first
    pre-sweep idle interval discarded). The finish-callback timestamp series is
    the secondary series: it is taken inside the app's callback, so anything
    that coalesces or delays that callback would bias it.
    """
    native = result.get("nativeFps") or {}
    harmonic = perf_number(native, "harmonic")
    if harmonic > 0.0:
        return harmonic
    return perf_number(perf_segment(result), "fps")


def perf_merge(args: argparse.Namespace) -> None:
    """Merges `perf` run summaries into one labelled before/after report."""
    labels = [item.strip() for item in args.labels.split(",") if item.strip()]
    summaries: list[dict[str, Any]] = []
    for path in args.summaries:
        summary_path = Path(path).expanduser()
        if not summary_path.is_absolute():
            summary_path = Path.cwd() / summary_path
        summaries.append(json.loads(summary_path.read_text(encoding="utf-8")))
    if not summaries:
        raise VisualError("perf-merge needs at least one perf-summary.json.")
    if labels and len(labels) != len(summaries):
        raise VisualError(
            f"perf-merge got {len(labels)} labels for {len(summaries)} summaries."
        )
    results: list[dict[str, Any]] = []
    device_gpu: dict[str, str] = {}
    for index, summary in enumerate(summaries):
        label = labels[index] if labels else f"run{index + 1}"
        for result in summary.get("sweeps") or []:
            labelled = dict(result)
            labelled["case"] = f"{label}/{result.get('case', '?')}"
            results.append(labelled)
        gpu = summary.get("deviceGpu")
        if isinstance(gpu, dict):
            device_gpu.update(gpu)
    first = summaries[0]
    report = perf_report_markdown(
        results,
        device=f"{first.get('serial', '?')} ({first.get('avd', '?')})",
        generated=utc_now(),
        app_build="debug sweeps (per-row backend identity below)",
        archive_bytes=first.get("archiveBytes"),
        device_gpu=device_gpu or None,
    )
    report_path = Path(args.report).expanduser()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report, encoding="utf-8")
    emit("perf_report", report=str(report_path), summaries=len(summaries), sweeps=len(results))


def perf_backend_label(result: dict[str, Any]) -> str:
    """One sweep's measured backend, from `RenderingEngine.getCurrentType()`.

    The renderer value is read at runtime by the app and is the authoritative
    bit: the single-backend MapLibre artifacts reject the other backend, so
    `vulkan` can only come from the Vulkan artifact and `opengl` only from the
    OpenGL one. The artifact's `BuildConfig` flavor is reported in the per-sweep
    JSON as extra context but is deliberately not used in this header: those
    constants are compile-time inlined, so an incrementally built APK can carry
    a stale value even when the runtime backend is correct.
    """
    backend = result.get("backend")
    if not isinstance(backend, dict):
        return "not reported"
    renderer = str(backend.get("renderer", "unknown"))
    build_type = backend.get("appBuildType")
    return f"{renderer} ({build_type})" if build_type else renderer


def perf_report_markdown(
    results: list[dict[str, Any]],
    *,
    device: str,
    generated: str,
    app_build: str,
    archive_bytes: int | None,
    device_gpu: dict[str, str] | None = None,
) -> str:
    """Human-readable evidence for the numeric benchmark, one table per view."""
    backends = sorted({perf_backend_label(result) for result in results})
    lines: list[str] = [
        "# Brisbane map performance report",
        "",
        f"- Generated: {generated}",
        f"- Device: {device}",
        f"- App build: {app_build}",
        (
            f"- Installed basemap: {archive_bytes} bytes"
            if archive_bytes
            else "- Installed basemap: not reported"
        ),
        (
            "- Rendering backend (measured in-process): " + "; ".join(backends)
            if backends
            else "- Rendering backend: not reported"
        ),
    ]
    if device_gpu:
        vulkan = device_gpu.get("vulkanDeviceName")
        gles = device_gpu.get("glesRenderer")
        if vulkan:
            lines.append(f"- Vulkan device (`cmd gpu vkjson`): {vulkan}")
        if gles:
            lines.append(f"- GLES renderer (`dumpsys SurfaceFlinger`): {gles}")
    lines += [
        f"- Sweeps: {len(results)}",
        "",
        "Frame intervals are wall-clock deltas between native MapLibre",
        "`OnDidFinishRenderingFrame` callbacks, i.e. the renderer's own frame",
        "cadence. `fps` is the primary cadence: `nativeFps.harmonic`, the",
        "elapsed average recovered from MapLibre's per-render-thread frame",
        "listener (first pre-sweep idle interval discarded). `elapsed fps` is",
        "the secondary finish-callback series `(frames-1)*1000/durationMs`;",
        "the in-app callback can be coalesced, so it never overrides the native",
        "series. `enc`/`render` are",
        "MapLibre `RenderingStats` times converted from seconds to ms; on",
        "Vulkan `enc` includes `Context::beginFrame` fence waits, so it is",
        "encode+GPU-wait, not pure CPU encode time. `draws` is the mean",
        "per-frame `numDrawCalls`; `upload KB/f` is the mean per-frame delta of",
        "the cumulative `bufferUpdateBytes` counter (never its running total).",
        "`gpu`/`total` are per-frame durations from the activity window's",
        "`Window.FrameMetrics` (`GPU_DURATION`/`TOTAL_DURATION`); a",
        "SurfaceView-backed MapView renders on its own GL surface, so those",
        "columns describe the window, not map presentation. Each sweep's",
        "`backend.renderer` is read at runtime via",
        "`RenderingEngine.getCurrentType()`, which the single-backend MapLibre",
        "artifacts make authoritative; the per-sweep JSON `maplibreFlavor` is",
        "informational (compile-time-inlined constants can go stale after an",
        "ABI-identical artifact swap).",
        "",
        "## Sweeps",
        "",
        "| case | mode | phase | fps | elapsed fps | p50 ms | p95 ms | p99 ms | >50 ms | >100 ms | enc mean | render mean | draws | upload KB/f | gpu p95 | total p95 | frames |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for result in results:
        all_stats = perf_segment(result)
        presentation = result.get("windowFrameMetrics") or {}
        gpu = presentation.get("gpu") if isinstance(presentation.get("gpu"), dict) else {}
        total = presentation.get("total") if isinstance(presentation.get("total"), dict) else {}
        counters = result.get("nativeRenderStats") or {}
        lines.append(
            "| {case} | {mode} | {phase} | {fps:.1f} | {elapsed:.1f} | {p50:.1f} | {p95:.1f} | {p99:.1f} | {s50} | {s100} | "
            "{enc:.1f} | {render:.1f} | {draws:.0f} | {upload:.0f} | {gpu:.1f} | {total:.1f} | {frames} |".format(
                case=result.get("case", "?"),
                mode=result.get("buildingMode", "?"),
                phase=result.get("phase", "?"),
                fps=perf_primary_fps(result),
                elapsed=perf_number(all_stats, "fps"),
                p50=perf_number(all_stats, "p50Ms"),
                p95=perf_number(all_stats, "p95Ms"),
                p99=perf_number(all_stats, "p99Ms"),
                s50=int(perf_number(all_stats, "stalls50")),
                s100=int(perf_number(all_stats, "stalls100")),
                enc=perf_number(all_stats, "encodingMeanMs"),
                render=perf_number(all_stats, "renderingMeanMs"),
                draws=perf_number(counters, "drawCallsMean"),
                upload=perf_number(counters, "bufferUpdateBytesPerFrameMean") / 1024.0,
                gpu=perf_number(gpu, "p95Ms"),
                total=perf_number(total, "p95Ms"),
                frames=int(perf_number(all_stats, "frames")),
            )
        )
    warm = [result for result in results if result.get("phase") == "warm"]
    if warm:
        lines += [
            "",
            "## Warmed motion (grouped by case and mode)",
            "",
            "| case | mode | mean fps | mean p95 ms | mean p99 ms | total >100 ms |",
            "| --- | --- | ---: | ---: | ---: | ---: |",
        ]
        groups: dict[tuple[str, str], list[dict[str, Any]]] = {}
        for result in warm:
            groups.setdefault((result.get("case", "?"), result.get("buildingMode", "?")), []).append(result)
        for (case, mode), group in sorted(groups.items()):
            count = len(group)
            fps = sum(perf_primary_fps(item) for item in group) / count
            p95 = sum(perf_number(perf_segment(item), "p95Ms") for item in group) / count
            p99 = sum(perf_number(perf_segment(item), "p99Ms") for item in group) / count
            stalls = sum(int(perf_number(perf_segment(item), "stalls100")) for item in group)
            lines.append(
                f"| {case} | {mode} | {fps:.1f} | {p95:.1f} | {p99:.1f} | {stalls} |"
            )
    # Explicit before/after comparison for merged runs labelled
    # `<before|after>-<variant>` (for example `before-opengl`/`after-opengl`).
    # Only valid warm sweeps count: a settle timeout or an interrupted leg is
    # never a measured improvement, and variants are never averaged together.
    grouped: dict[tuple[str, str, str], list[dict[str, Any]]] = {}
    for result in warm:
        if result.get("interrupted") or result.get("cancelled") or result.get("settleTimedOut"):
            continue
        case = str(result.get("case", "?"))
        label, _, suffix = case.partition("/")
        side, _, variant = label.partition("-")
        if side not in ("before", "after") or not variant:
            continue
        grouped.setdefault((variant, suffix, side), []).append(result)
    pairs = sorted(
        {
            (variant, suffix)
            for (variant, suffix, side) in grouped
            if (variant, suffix, "before") in grouped and (variant, suffix, "after") in grouped
        }
    )
    if pairs:
        lines += [
            "",
            "## Valid warm before/after (primary cadence)",
            "",
            "Mean `nativeFps.harmonic` over the valid warmed sweeps of each merged",
            "run, paired per backend variant; sweeps with a settle timeout,",
            "interruption or cancellation are excluded from both sides.",
            "",
            "| variant | case | before fps | after fps | factor | before p95 ms | after p95 ms |",
            "| --- | --- | ---: | ---: | ---: | ---: | ---: |",
        ]
        for variant, suffix in pairs:
            before = grouped[(variant, suffix, "before")]
            after = grouped[(variant, suffix, "after")]
            before_fps = sum(perf_primary_fps(item) for item in before) / len(before)
            after_fps = sum(perf_primary_fps(item) for item in after) / len(after)
            before_p95 = sum(perf_number(perf_segment(item), "p95Ms") for item in before) / len(before)
            after_p95 = sum(perf_number(perf_segment(item), "p95Ms") for item in after) / len(after)
            factor = after_fps / before_fps if before_fps > 0.0 else 0.0
            lines.append(
                f"| {variant} | {suffix} | {before_fps:.1f} | {after_fps:.1f} | {factor:.2f}x | "
                f"{before_p95:.1f} | {after_p95:.1f} |"
            )
    cold = [result for result in results if result.get("phase") == "cold"]
    if cold:
        lines += [
            "",
            "## Cold loading (fresh process, tiles still streaming)",
            "",
            "| case | mode | fps | p50 ms | p95 ms | >100 ms | cold frames | warm frames |",
            "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
        for result in cold:
            all_stats = perf_segment(result)
            segments = result.get("segments") or {}
            lines.append(
                "| {case} | {mode} | {fps:.1f} | {p50:.1f} | {p95:.1f} | {s100} | {cold} | {warm} |".format(
                    case=result.get("case", "?"),
                    mode=result.get("buildingMode", "?"),
                    fps=perf_number(all_stats, "fps"),
                    p50=perf_number(all_stats, "p50Ms"),
                    p95=perf_number(all_stats, "p95Ms"),
                    s100=int(perf_number(all_stats, "stalls100")),
                    cold=int(segments.get("coldFrames", 0) or 0),
                    warm=int(segments.get("warmFrames", 0) or 0),
                )
            )
    lines += [
        "",
        "## Acceptance",
        "",
        "Targets: ~60 fps, warm p95 <= 33 ms, no warm >100 ms stalls. A warm",
        "sweep whose legs were interrupted/cancelled or whose settle window",
        "timed out is INVALID, never folded into a pass. Emulator numbers below",
        "the target are reported as measured; the benchmark is never loosened to",
        "make a case pass.",
        "",
    ]
    for result in results:
        if result.get("phase") != "warm":
            continue
        all_stats = perf_segment(result)
        fps = perf_primary_fps(result)
        p95 = perf_number(all_stats, "p95Ms")
        stalls = int(perf_number(all_stats, "stalls100"))
        interrupted = bool(result.get("interrupted")) or bool(result.get("cancelled"))
        settle_timed_out = bool(result.get("settleTimedOut"))
        if interrupted or settle_timed_out:
            verdict = "INVALID"
        else:
            verdict = "PASS" if fps >= 55.0 and p95 <= 33.0 and stalls == 0 else "MISS"
        detail = f"{fps:.1f} fps, p95 {p95:.1f} ms, {stalls} stalls >100 ms"
        if interrupted:
            detail += "; interrupted leg"
        if settle_timed_out:
            detail += "; settle window timed out (not a verified warmed run)"
        lines.append(
            f"- {result.get('case', '?')} / {result.get('buildingMode', '?')}: "
            f"{verdict} ({detail})"
        )
    lines.append("")
    return "\n".join(lines)


def cumulative_distance(points: list[list[float]]) -> tuple[list[float], float]:
    if len(points) < 2:
        raise VisualError("The selected route has fewer than two geometry points.")
    cumulative = [0.0]
    for first, second in zip(points, points[1:]):
        lat1, lon1 = map(math.radians, first)
        lat2, lon2 = map(math.radians, second)
        dlat = lat2 - lat1
        dlon = lon2 - lon1
        hav = (
            math.sin(dlat / 2) ** 2
            + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
        )
        segment_m = 2 * 6_371_008.8 * math.asin(min(1.0, math.sqrt(hav)))
        cumulative.append(cumulative[-1] + segment_m)
    return cumulative, cumulative[-1]


def point_at_offset(
    points: list[list[float]],
    cumulative: list[float],
    offset_m: float,
) -> tuple[float, float]:
    target = max(0.0, min(cumulative[-1], offset_m))
    index = min(len(points) - 2, max(0, bisect.bisect_right(cumulative, target) - 1))
    segment_m = cumulative[index + 1] - cumulative[index]
    fraction = 0.0 if segment_m <= 0 else (target - cumulative[index]) / segment_m
    lat1, lon1 = points[index]
    lat2, lon2 = points[index + 1]
    longitude_delta = (lon2 - lon1 + 180.0) % 360.0 - 180.0
    return lat1 + (lat2 - lat1) * fraction, lon1 + longitude_delta * fraction


def point_at_percent(points: list[list[float]], percent: float) -> tuple[float, float]:
    cumulative, total = cumulative_distance(points)
    return point_at_offset(points, cumulative, total * percent / 100.0)


def guidance_zoom_band(speed_mps: float) -> float:
    """Mirrors RouteScreen.guidanceZoomFor / CameraFollowPolicyTest bands."""
    if not math.isfinite(speed_mps) or speed_mps < 0.0:
        return GUIDANCE_ZOOM_BANDS[0][1]
    for limit, zoom in GUIDANCE_ZOOM_BANDS:
        if speed_mps < limit:
            return zoom
    return GUIDANCE_ZOOM_BANDS[-1][1]


def parse_speed_cases(value: str) -> list[float]:
    speeds: list[float] = []
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        try:
            speed = float(part)
        except ValueError as error:
            raise VisualError(f"Invalid speed case {part!r}: {error}") from error
        if not math.isfinite(speed) or speed < 0.0:
            raise VisualError(f"Speed cases must be finite and non-negative: {part!r}.")
        speeds.append(speed)
    if not speeds:
        raise VisualError("At least one speed case is required.")
    return speeds


def probe_number(probe: dict[str, Any], key: str) -> float:
    value = probe.get(key)
    return float(value) if isinstance(value, (int, float)) else float("nan")


def distance_metres(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    if not all(map(math.isfinite, (lat1, lon1, lat2, lon2))):
        return math.inf
    mean_lat = math.radians((lat1 + lat2) / 2.0)
    north = math.radians(lat2 - lat1) * 6_371_008.8
    east = math.radians(lon2 - lon1) * 6_371_008.8 * math.cos(mean_lat)
    return math.hypot(north, east)


def camera_near(probe: dict[str, Any], lat: float, lon: float, *, tolerance_m: float = 30.0) -> bool:
    """True when the settled camera target sits on the injected fix."""
    return distance_metres(
        probe_number(probe, "lat"), probe_number(probe, "lon"), lat, lon
    ) <= tolerance_m


def bearing_degrees(
    first: tuple[float, float],
    second: tuple[float, float],
) -> float:
    lat1, lon1 = map(math.radians, first)
    lat2, lon2 = map(math.radians, second)
    delta_lon = lon2 - lon1
    y = math.sin(delta_lon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(
        delta_lon
    )
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def bearing_delta_degrees(first: float, second: float) -> float:
    return (second - first + 540.0) % 360.0 - 180.0


def bearing_distance_degrees(first: float, second: float) -> float:
    if not (math.isfinite(first) and math.isfinite(second)):
        return math.inf
    return abs(bearing_delta_degrees(first, second))


def find_route_turn(
    points: list[list[float]],
    cumulative: list[float],
    *,
    threshold_deg: float,
    min_offset_m: float,
    max_offset_m: float,
) -> tuple[float, float, float] | None:
    """First pronounced corner: (offset_m, incoming bearing, outgoing bearing)."""
    if not math.isfinite(threshold_deg) or threshold_deg <= 0.0:
        raise VisualError("The turn threshold must be a positive number of degrees.")
    sample = max(0.0, min_offset_m)
    while sample <= max_offset_m:
        before = point_at_offset(points, cumulative, max(0.0, sample - 40.0))
        at = point_at_offset(points, cumulative, sample)
        after = point_at_offset(points, cumulative, min(cumulative[-1], sample + 40.0))
        incoming = bearing_degrees(before, at)
        outgoing = bearing_degrees(at, after)
        if abs(bearing_delta_degrees(incoming, outgoing)) >= threshold_deg:
            return sample, incoming, outgoing
        sample += 20.0
    return None


def add_selector(parser: argparse.ArgumentParser) -> None:
    selector = parser.add_mutually_exclusive_group(required=True)
    selector.add_argument("--text", help="Match visible UI text.")
    selector.add_argument("--description", help="Match a content description.")
    parser.add_argument("--contains", action="store_true", help="Match a substring.")
    parser.add_argument("--index", type=int, default=0, help="Choose one match when there are duplicates.")


def add_route_options(parser: argparse.ArgumentParser, *, require_from: bool = True) -> None:
    if require_from:
        parser.add_argument("--from", dest="from_text", required=True, help="Place name or lat,lon.")
    else:
        parser.set_defaults(from_text="")
    parser.add_argument("--to", dest="to_text", required=True, help="Place name or lat,lon.")
    parser.add_argument("--complexity", type=int, default=0, help="Ride-complexity detent (default: 0).")
    parser.add_argument("--road-share", type=int, default=70, help="Maximum shared roads, 10–90 by 5%% (default: 70).")
    parser.add_argument("--block-unpaved", action="store_true", help="Block unpaved roads for this route.")
    parser.add_argument("--route-index", type=int, default=1, help="Choose a 1-based route card after routing.")
    parser.add_argument("--timeout", type=float, default=300, help="Maximum route wait in seconds.")
    parser.add_argument("--progress-interval", type=float, default=2.0, help="Seconds between screenshots while routing.")
    parser.add_argument("--progress", "--progress-percent", dest="progress_percent", type=float, help="Enter ride mode and jump to this route percentage.")
    parser.add_argument("--step-delay", type=float, default=0.15, help="Delay between simulated GPS fixes in seconds.")
    parser.add_argument("--black-and-white", choices=("on", "off", "both"), help="Set ride-map style; 'both' captures every frame in both styles.")
    parser.add_argument("--plan-only", action="store_true", help="Stop after the route is ready; leave the planner visible.")
    parser.add_argument("--reuse-app", action="store_true", help="Use the running app without rebuilding or relaunching it.")
    parser.add_argument("--no-build", action="store_true", help="Use the existing debug APK when launching.")
    parser.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    parser.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Launch, route, inspect, and control Organic Moto Maps on an ADB device.",
        epilog=(
            "Examples:\n"
            "  tools/test/visual.py launch\n"
            "  tools/test/visual.py route --from '-27.4698,153.0251' --to '-27.3353,152.7720'\n"
            "  tools/test/visual.py route --from 'Brisbane' --to 'Mount Glorious' --complexity 2 --road-share 45 --block-unpaved\n"
            "  tools/test/visual.py current-location --to '-27.3353,152.7720' --plan-only\n"
            "  tools/test/visual.py lanes --from '-27.4698,153.0251' --to '-27.5598,153.0811'\n"
            "  tools/test/visual.py nav-camera --from 'Brisbane' --to 'Mount Glorious'\n"
            "  tools/test/visual.py nav-camera --from 'Brisbane' --to 'Mount Glorious' --black-and-white both\n"
            "  tools/test/visual.py nav-camera --from '-27.4700,153.0250' --to 'Mount Glorious' --black-and-white on\n"
            "  tools/test/visual.py route --from 'Brisbane' --to 'Mount Glorious' --black-and-white both\n"
            "  tools/test/visual.py map-mode --black-and-white on\n"
            "  tools/test/visual.py settings-menu\n"
            "  tools/test/visual.py search-results --query Brisbane\n"
            "  tools/test/visual.py screenshot --label before-change\n"
            "  tools/test/visual.py tree\n"
            "  tools/test/visual.py tap --description 'Planning settings'\n"
            "  tools/test/visual.py type --description From --value 'Samford'\n"
            "  tools/test/visual.py swipe 500 700 800 900 --duration 500\n"
            "  tools/test/visual.py logs --lines 100"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--serial", help="ADB serial (defaults to ANDROID_SERIAL or the only online device).")
    parser.add_argument("--avd", help=f"AVD to boot when none is online (default: {DEFAULT_AVD}).")
    parser.add_argument("--output-dir", help="Artifact directory (default: build/visual-inspection).")
    commands = parser.add_subparsers(dest="command", required=True)

    launch = commands.add_parser("launch", help="Build/install, boot or reuse an emulator, and launch the app.")
    launch.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    launch.add_argument("--skip-map", action="store_true", help="Do not seed the PMTiles archive.")
    launch.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")

    settings_menu = commands.add_parser(
        "settings-menu",
        help="Capture the planner settings menu and its dismissed state.",
    )
    settings_menu.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    settings_menu.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    settings_menu.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")

    search_results = commands.add_parser(
        "search-results", help="Capture the From POI action and search results with and without the keyboard.",
    )
    search_results.add_argument("--field", choices=("From", "To"), default="From")
    search_results.add_argument("--query", default="Brisbane")
    search_results.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    search_results.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    search_results.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when installed.")

    maps_settings = commands.add_parser(
        "maps-settings",
        help=(
            "Capture the Maps screen: server address, catalog, download/import "
            "intermediate states, installed-map delete confirmation, and Back."
        ),
    )
    maps_settings.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    maps_settings.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    maps_settings.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")
    maps_settings.add_argument(
        "--server",
        default="https://maps.example.net",
        help="Address typed into the server field (no request is sent).",
    )

    media_controls = commands.add_parser(
        "media-controls",
        help="Capture the ride media control center across B&W player states.",
    )
    media_controls.add_argument("--from", dest="from_text", default="-27.4698,153.0251", help="Place name or lat,lon.")
    media_controls.add_argument("--to", dest="to_text", default="-27.3353,152.7720", help="Place name or lat,lon.")
    media_controls.add_argument("--title", default="Debug Track", help="Synthetic track title.")
    media_controls.add_argument("--artist", default="Debug Artist", help="Synthetic artist.")
    media_controls.add_argument("--route-timeout", type=float, default=300, help="Maximum route wait in seconds.")
    media_controls.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    media_controls.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    media_controls.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")
    media_controls.add_argument("--reuse-app", action="store_true", help="Reuse the running app and enter ride mode from the current screen.")
    media_controls.add_argument(
        "--inactivity-only",
        action="store_true",
        help="Capture only the real ride panel's inactivity timer and blank-panel touch reset.",
    )

    route = commands.add_parser("route", help="Load endpoints, apply options, route, and optionally enter ride mode.")
    add_route_options(route)
    current_location = commands.add_parser(
        "current-location",
        help="Select a real emulator GPS fix as From, then continue the route flow.",
    )
    add_route_options(current_location, require_from=False)
    current_location.add_argument(
        "--location",
        default="-27.4698,153.0251",
        help="Emulator GPS coordinate to supply to the app (default: Brisbane).",
    )
    current_location.set_defaults(from_current_location=True)

    buildings = commands.add_parser("buildings", help="Offline 3D houses, city navigation, silhouettes, style switches and landscape (explicit owned serial required).")
    buildings.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")

    perf = commands.add_parser(
        "perf",
        help="Native-frame FPS benchmark over deterministic camera sweeps on the real offline map (explicit owned serial required).",
        description=(
            "Queues camera sweeps through the debug receiver and reports native\n"
            "MapLibre frame intervals (p50/p95/p99, >50/>100 ms stalls) for cold\n"
            "loading and warmed motion, A/B-ing the building extrusion paint. Each\n"
            "sweep records the rendering backend MapLibre actually initialized and\n"
            "the per-frame renderer counters; the report names the device GPU.\n\n"
            "  tools/test/visual.py --serial emulator-5554 perf\n"
            "  tools/test/visual.py --serial emulator-5554 perf --targets cbd --zooms 18 \\\n"
            "      --building-modes current,opaque,hidden --repeat 2\n"
            "  tools/test/visual.py --serial emulator-5554 perf --targets cbd,houses \\\n"
            "      --zooms cbd=14,cbd=16,cbd=18,houses=18.5 --screenshots"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    perf.add_argument("--targets", default="cbd,houses", help="Comma-separated targets: cbd,houses (default both).")
    perf.add_argument(
        "--zooms",
        default="14,16,18",
        help=(
            "Comma-separated zooms. A bare zoom applies to every target; "
            "`target=zoom` applies only to that target and, when present for a "
            "target, replaces the bare zooms there (default 14,16,18). Example: "
            "--zooms cbd=14,cbd=16,cbd=18,houses=18.5"
        ),
    )
    perf.add_argument("--motion", default="combo", choices=("orbit", "pan", "zoom", "combo"), help="Camera motion kind (default combo).")
    perf.add_argument("--tilt", type=float, default=58.0, help="Camera pitch in degrees (default 58).")
    perf.add_argument("--duration", type=float, default=6.0, help="Sweep motion duration in seconds (default 6).")
    perf.add_argument("--repeat", type=int, default=2, help="Warmed repeats per case (default 2).")
    perf.add_argument("--building-modes", default="current", help="Comma-separated extrusion diagnostics: current,opaque,hidden (default current).")
    perf.add_argument("--no-cold", action="store_true", help="Skip the fresh-process cold-loading runs.")
    perf.add_argument("--no-warm", action="store_true", help="Skip the warmed motion runs.")
    perf.add_argument("--settle", type=float, default=1.0, help="Seconds to wait after a cold restart (default 1).")
    perf.add_argument("--screenshots", action="store_true", help="Capture a settled case view for building visibility evidence.")
    perf.add_argument("--screenshot-motion", action="store_true", help="Also capture one mid-motion frame per sweep (adds a known hitch to that sweep's frames).")
    perf.add_argument("--report", default=str(DEFAULT_PERF_REPORT), help=f"Markdown report path (default {DEFAULT_PERF_REPORT}).")
    perf.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    perf.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    perf.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")

    perf_merge_parser = commands.add_parser(
        "perf-merge",
        help="Merge several perf summaries into one before/after report.",
        description=(
            "Reads the perf-summary.json of one or more `perf` runs (each run\n"
            "covers one style/backend combination) and writes one report with\n"
            "every sweep labelled by its run, using the same markdown renderer\n"
            "as a single run.\n\n"
            "  tools/test/visual.py perf-merge --labels before-opengl,after-opengl \\\n"
            "      build/visual-inspection/*/perf-summary.json"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    perf_merge_parser.add_argument("summaries", nargs="+", help="perf-summary.json files, in report order.")
    perf_merge_parser.add_argument("--labels", default="", help="Comma-separated run labels, one per summary.")
    perf_merge_parser.add_argument("--report", default=str(DEFAULT_PERF_REPORT), help=f"Markdown report path (default {DEFAULT_PERF_REPORT}).")

    camera = commands.add_parser("camera", help="Set a deterministic debug inspection camera, not a production camera policy.")
    camera.add_argument("--lat", type=float, required=True)
    camera.add_argument("--lon", type=float, required=True)
    camera.add_argument("--zoom", type=float, required=True)
    camera.add_argument("--tilt", type=float, default=58.0)
    camera.add_argument("--bearing", type=float, default=0.0)

    lanes = commands.add_parser(
        "lanes",
        help="Ride a route and capture every maneuver whose guidance card shows recommended lanes.",
    )
    add_route_options(lanes)
    lanes.add_argument("--step", type=float, default=30.0, help="Metres between injected GPS fixes (default: 30).")
    lanes.add_argument("--speed", type=float, default=12.0, help="Speed in m/s for the injected fixes (default: 12).")
    lanes.add_argument("--max-captures", type=int, default=6, help="Stop after this many lane maneuvers (default: 6).")
    lanes.add_argument("--settle", type=float, default=0.7, help="Seconds to let the HUD repaint before a capture (default: 0.7).")

    nav_camera = commands.add_parser(
        "nav-camera",
        help="Capture guidance camera lock/relock, speed, turn, and exit restore (both ride-map styles with --black-and-white both).",
    )
    add_route_options(nav_camera)
    nav_camera.add_argument(
        "--speed-cases",
        default=DEFAULT_SPEED_CASES,
        help=f"Comma-separated m/s cases to capture (default: {DEFAULT_SPEED_CASES}).",
    )
    nav_camera.add_argument(
        "--turn-threshold",
        type=float,
        default=40.0,
        help="Minimum turn angle in degrees to use for the heading-turn frames (default: 40).",
    )
    nav_camera.add_argument(
        "--turn-step",
        type=float,
        default=25.0,
        help="Metres between heading-turn GPS fixes (default: 25).",
    )
    nav_camera.add_argument(
        "--turn-speed",
        type=float,
        default=12.0,
        help="Speed in m/s used through the heading-turn frames (default: 12).",
    )
    nav_camera.add_argument(
        "--settle",
        type=float,
        default=0.7,
        help="Seconds to let the camera animation and repaint settle before a capture (default: 0.7).",
    )

    options = commands.add_parser("options", help="Set route options on the current screen.")
    options.add_argument("--complexity", type=int, help="Set the ride-complexity detent.")
    options.add_argument("--road-share", type=int, help="Set shared-road target (10–90 by 5 percent).")
    options.add_argument("--block-unpaved", choices=("on", "off"), help="Set whether unpaved roads are blocked.")

    progress = commands.add_parser("progress", help="Move the active emulator ride to a route percentage.")
    progress.add_argument("--percent", type=float, required=True, help="Target progress from 0 to 100 percent.")
    progress.add_argument("--step-delay", type=float, default=0.15, help="Delay between simulated GPS fixes in seconds.")

    map_mode = commands.add_parser("map-mode", help="Set black-and-white ride-map style on an active ride.")
    map_mode.add_argument("--black-and-white", choices=("on", "off", "both"), required=True, help="Capture black-and-white mode on, off, or both.")

    commands.add_parser("icon-states", help="Capture moon and speaker states on an active ride, restoring preferences.")

    screenshot = commands.add_parser("screenshot", help="Save the current screen and update latest.png.")
    screenshot.add_argument("--label", default="manual", help="Artifact filename label.")
    screenshot.add_argument("--no-tree", action="store_true", help="Skip the accessibility dump.")

    commands.add_parser("tree", help="Print visible accessibility nodes as JSON.")
    commands.add_parser("status", help="Print app process, foreground activity, and visible nodes.")

    tap = commands.add_parser("tap", help="Tap a visible node by text or content description.")
    add_selector(tap)

    type_cmd = commands.add_parser("type", help="Focus a field and enter printable ASCII text.")
    add_selector(type_cmd)
    type_cmd.add_argument("--value", required=True, help="Text to type.")
    type_cmd.add_argument("--append", action="store_true", help="Append instead of selecting existing text first.")

    key = commands.add_parser("key", help="Send an Android key event, for example KEYCODE_BACK or KEYCODE_ENTER.")
    key.add_argument("code")

    swipe = commands.add_parser("swipe", help="Swipe between screen coordinates in physical pixels.")
    swipe.add_argument("x1", type=int)
    swipe.add_argument("y1", type=int)
    swipe.add_argument("x2", type=int)
    swipe.add_argument("y2", type=int)
    swipe.add_argument("--duration", type=int, default=450, help="Swipe duration in milliseconds.")

    watch = commands.add_parser("watch", help="Capture changing screens while another command or gesture runs.")
    watch.add_argument("--seconds", type=float, default=45, help="Capture duration.")
    watch.add_argument("--interval", type=float, default=1.5, help="Seconds between captures.")
    watch.add_argument("--max-captures", type=int, default=30, help="Maximum timeline screenshots.")

    logs = commands.add_parser("logs", help="Print recent app routing and geocoder logs.")
    logs.add_argument("--lines", type=int, default=120)

    commands.add_parser("stop", help="Force-stop the app without shutting down the emulator.")
    return parser


def main() -> int:
    parser = create_parser()
    args = parser.parse_args()
    harness = VisualHarness(args)
    try:
        if args.command == "launch":
            harness.launch_app(
                build=not args.no_build,
                skip_map=args.skip_map,
                refresh_map=args.refresh_map,
            )
        elif args.command == "route":
            harness.route(args)
        elif args.command == "search-results":
            harness.capture_search_results(args)
        elif args.command == "current-location":
            harness.route(args)
        elif args.command == "nav-camera":
            harness.navigation_camera(args)
        elif args.command == "lanes":
            harness.lane_guidance(args)
        elif args.command == "buildings":
            harness.buildings(args)
        elif args.command == "perf":
            harness.perf(args)
        elif args.command == "perf-merge":
            perf_merge(args)
        elif args.command == "camera":
            harness.ensure_device(start_if_missing=False)
            harness.inspect_camera(args.lat, args.lon, args.zoom, args.tilt, args.bearing)
            harness.capture("inspection-camera")
        elif args.command == "settings-menu":
            harness.capture_settings_menu(
                build=not args.no_build,
                skip_map=args.skip_map,
                refresh_map=args.refresh_map,
            )
        elif args.command == "maps-settings":
            harness.capture_maps_settings(args)
        elif args.command == "media-controls":
            harness.capture_media_controls(args)
        elif args.command in {"options", "progress", "map-mode", "icon-states", "screenshot", "tree", "status", "tap", "type", "key", "swipe", "watch", "logs", "stop"}:
            harness.ensure_device(start_if_missing=False)
            if args.command == "options":
                if args.complexity is not None:
                    harness.set_complexity(args.complexity)
                block = None if args.block_unpaved is None else args.block_unpaved == "on"
                harness.set_route_settings(road_share=args.road_share, block_unpaved=block)
                harness.capture("options-updated")
            elif args.command == "progress":
                harness.progress_to(args.percent, args.step_delay)
            elif args.command == "map-mode":
                modes = ("on", "off") if args.black_and_white == "both" else (args.black_and_white,)
                for mode in modes:
                    harness.set_black_and_white(mode)
            elif args.command == "icon-states":
                harness.capture_icon_states()
            elif args.command == "screenshot":
                harness.capture(args.label, include_tree=not args.no_tree)
            elif args.command == "tree":
                nodes, _ = harness.hierarchy()
                emit("tree", serial=harness.serial, nodes=[n for n in nodes if n["visible"]])
            elif args.command == "status":
                nodes, _ = harness.hierarchy()
                pid = harness.device_command("shell", "pidof", "-s", APP_ID, check=False).stdout.strip()
                activity = harness.device_command(
                    "shell", "dumpsys", "activity", "activities", check=False
                ).stdout
                resumed = re.search(r"mResumedActivity:.*?\s([^\s}]+/[^\s}]+)", activity)
                emit(
                    "status",
                    serial=harness.serial,
                    package_running=bool(pid),
                    pid=pid,
                    foreground_activity=resumed.group(1) if resumed else None,
                    nodes=[n for n in nodes if n["visible"]],
                    latest=str(harness.output_dir / "latest.png"),
                )
            elif args.command == "tap":
                node = harness.tap_node(
                    "text" if args.text is not None else "description",
                    args.text if args.text is not None else args.description,
                    contains=args.contains,
                    index=args.index,
                )
                emit("tap", node=node)
            elif args.command == "type":
                harness.type_into(
                    "text" if args.text is not None else "description",
                    args.text if args.text is not None else args.description,
                    args.value,
                    contains=args.contains,
                    replace=not args.append,
                )
                emit("typed", value=args.value)
            elif args.command == "key":
                harness.press_key(args.code)
                emit("key", code=args.code)
            elif args.command == "swipe":
                harness.swipe(args.x1, args.y1, args.x2, args.y2, args.duration)
                emit("swipe", coordinates=[args.x1, args.y1, args.x2, args.y2])
            elif args.command == "watch":
                deadline = time.monotonic() + args.seconds
                index = 0
                last_digest = ""
                while time.monotonic() < deadline and index < args.max_captures:
                    png = harness.raw_device_bytes("exec-out", "screencap", "-p", timeout=60)
                    digest = hashlib.sha256(png).hexdigest()
                    if digest != last_digest:
                        index += 1
                        harness.sequence += 1
                        label = f"watch-{index:02d}"
                        path = harness.run_dir / f"{harness.sequence:02d}-{label}.png"
                        harness.ensure_capture_dirs()
                        path.write_bytes(png)
                        latest_tmp = harness.output_dir / "latest.png.tmp"
                        latest_tmp.write_bytes(png)
                        latest_tmp.replace(harness.output_dir / "latest.png")
                        emit("screenshot", label=label, path=str(path), latest=str(harness.output_dir / "latest.png"))
                        last_digest = digest
                    time.sleep(max(0.1, args.interval))
            elif args.command == "logs":
                emit("logs", serial=harness.serial, text=harness.app_logs(lines=args.lines))
            elif args.command == "stop":
                harness.device_command("shell", "am", "force-stop", APP_ID)
                emit("stopped", package=APP_ID)
        emit("complete", run_dir=str(harness.run_dir), latest=str(harness.output_dir / "latest.png"))
        return 0
    except (VisualError, subprocess.TimeoutExpired, OSError) as error:
        print(json.dumps({"event": "error", "message": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
