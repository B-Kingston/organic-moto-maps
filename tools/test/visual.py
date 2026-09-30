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
        self.wait_for_node("text", "START", timeout=60)
        self.capture("00-launched")

    def hierarchy(self) -> tuple[list[dict[str, Any]], str]:
        xml_text = ""
        last_error = ""
        for attempt in range(8):
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
    ) -> None:
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

    def press_key(self, key: str) -> None:
        self.device_command("shell", "input", "keyevent", key)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 450) -> None:
        self.device_command(
            "shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms)
        )

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
        self.tap_node("description", "Route settings")
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

    def set_black_and_white(self, mode: str) -> None:
        if mode not in {"on", "off"}:
            raise VisualError("Black-and-white mode must be 'on' or 'off'.")
        enabled = mode == "on"
        target = f"Dark ride map, {mode}"
        opposite = f"Dark ride map, {'off' if enabled else 'on'}"
        if not self.matching_nodes("description", target):
            if not self.matching_nodes("description", opposite):
                raise VisualError(
                    "Black-and-white mode is available during an active ride; start a route first."
                )
            self.tap_node("description", opposite)
            self.wait_for_node("description", target, timeout=10)

        # The accessibility state changes before MapLibre finishes loading the
        # alternate local style; allow the style and route line to repaint.
        time.sleep(2)
        self.capture(f"ride-map-black-and-white-{mode}")
        emit("ride_map_mode", black_and_white=enabled, screenshot=str(self.output_dir / "latest.png"))

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
                from_text=args.from_text,
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
        else:
            emit("existing_route_ready", from_text=args.from_text, to_text=args.to_text)

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
        if not self.serial or not self.serial.startswith("emulator-"):
            emit("gps_start_not_changed", reason="selected ADB device is not an Android emulator")
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

    def navigation_camera(self, args: argparse.Namespace) -> None:
        """Captures the immersive guidance camera states on a real ride.

        Repeatable frames: navigation start, street-close framing, one frame
        per speed band, a heading turn, and the planning-camera restore after
        END. The debug camera probe makes each state checkable, not only
        viewable. Queued debug fixes own the guidance session while they flow,
        so the emulator's live provider cannot pull the camera away.
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
        self.capture("nav-camera-00-guidance-start")

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
        self.capture("nav-camera-01-street-close")
        emit("nav_camera", stage="street-close", camera=camera)

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
            self.capture(f"nav-camera-02-speed-{speed:g}")
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
            self.capture("nav-camera-03-straight-heading")
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
            self.capture("nav-camera-03-turn-approach")
            emit("nav_camera", stage="turn-approach", camera=camera, expected_bearing=incoming)

            self.inject_route_fix(
                points, cumulative, turn_offset + args.turn_step * 0.5, speed_mps=args.turn_speed
            )
            self.settle_for_capture(args)
            self.capture("nav-camera-04-turn-mid")

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
            self.capture("nav-camera-05-turn-exited")
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


def add_route_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--from", dest="from_text", required=True, help="Place name or lat,lon.")
    parser.add_argument("--to", dest="to_text", required=True, help="Place name or lat,lon.")
    parser.add_argument("--complexity", type=int, default=0, help="Ride-complexity detent (default: 0).")
    parser.add_argument("--road-share", type=int, default=70, help="Maximum shared roads, 10–90 by 5%% (default: 70).")
    parser.add_argument("--block-unpaved", action="store_true", help="Block unpaved roads for this route.")
    parser.add_argument("--route-index", type=int, default=1, help="Choose a 1-based route card after routing.")
    parser.add_argument("--timeout", type=float, default=300, help="Maximum route wait in seconds.")
    parser.add_argument("--progress-interval", type=float, default=2.0, help="Seconds between screenshots while routing.")
    parser.add_argument("--progress", "--progress-percent", dest="progress_percent", type=float, help="Enter ride mode and jump to this route percentage.")
    parser.add_argument("--step-delay", type=float, default=0.15, help="Delay between simulated GPS fixes in seconds.")
    parser.add_argument("--black-and-white", choices=("on", "off", "both"), help="Set ride-map style; 'both' captures both styles.")
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
            "  tools/test/visual.py nav-camera --from 'Brisbane' --to 'Mount Glorious'\n"
            "  tools/test/visual.py route --from 'Brisbane' --to 'Mount Glorious' --black-and-white both\n"
            "  tools/test/visual.py map-mode --black-and-white on\n"
            "  tools/test/visual.py screenshot --label before-change\n"
            "  tools/test/visual.py tree\n"
            "  tools/test/visual.py tap --description 'Route settings'\n"
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

    route = commands.add_parser("route", help="Load endpoints, apply options, route, and optionally enter ride mode.")
    add_route_options(route)

    nav_camera = commands.add_parser(
        "nav-camera",
        help="Start a ride and capture the guidance camera states plus the exit restore.",
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
        elif args.command == "nav-camera":
            harness.navigation_camera(args)
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
