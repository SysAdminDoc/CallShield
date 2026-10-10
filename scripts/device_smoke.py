"""Release smoke pass on a real phone: the checks a person used to tap through.

Run it after installing the release APK, with the app set up and the phone
unlocked. It drives the English UI through adb and uiautomator, prints PASS or
FAIL for each step, and exits non-zero if any step failed.

  py -3 scripts/device_smoke.py --serial R5CT139QJ5F
  py -3 scripts/device_smoke.py --serial R5CT139QJ5F --report +13152328257

Every block it makes is undone, and the script checks that it was. Not spam
goes out five seconds after its tap unless Undo is tapped first, so that step
fails loudly if Undo isn't found. --report sends a real anonymous report for
the number given; pick one already in the database, or one this phone
reported in the last day, which the app won't send twice.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PACKAGE = "com.sysadmindoc.callshield"
ACTIVITY = f"{PACKAGE}/.ui.MainActivity"
DUMP = "/sdcard/callshield-smoke.xml"

# A Paris number has the area code +33 1; a Japanese one under +81 42 has none
# CallShield can block safely (GeographicAreaCodes drops 4, and 42 has gaps).
AREA_NUMBER, AREA_LABEL = "+33145230001", "+33 1"
NO_AREA_NUMBER = "+81427003737"


@dataclass(frozen=True)
class Node:
    label: str
    x: int
    y: int


def parse_nodes(xml: str) -> list[Node]:
    """Every node with text or a content description, at the center of its bounds."""
    nodes = []
    for match in re.finditer(r"<node [^>]*>", xml):
        attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', match.group(0)))
        label = attrs.get("text") or attrs.get("content-desc") or ""
        bounds = [int(n) for n in re.findall(r"\d+", attrs.get("bounds", ""))]
        if label and len(bounds) == 4:
            # Numbers come wrapped in Unicode isolates (⁦...⁩) for right-to-left layouts.
            label = html.unescape(label).replace("⁦", "").replace("⁩", "")
            nodes.append(Node(label, (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2))
    return nodes


def bottom_most(nodes: list[Node], label: str) -> Node | None:
    """The tab bar's entry: a tab's name also heads its screen, higher up."""
    exact = [n for n in nodes if n.label == label]
    return max(exact, key=lambda n: n.y, default=None)


def app_version(gradle: Path = REPO / "app" / "build.gradle.kts") -> str:
    match = re.search(r'versionName = "([^"]+)"', gradle.read_text(encoding="utf-8"))
    if not match:
        raise SystemExit(f"no versionName in {gradle}")
    return match.group(1)


def catalog_names(catalog: Path = REPO / "data" / "list_catalog.json") -> list[str]:
    return [entry["name"] for entry in json.loads(catalog.read_text(encoding="utf-8"))["lists"]]


class Phone:
    def __init__(self, adb: str, serial: str):
        self.adb, self.serial = adb, serial
        size = re.search(r"(\d+)x(\d+)", self.shell("wm", "size"))
        self.width, self.height = (int(size.group(1)), int(size.group(2))) if size else (1080, 2340)

    def shell(self, *args: str) -> str:
        out = subprocess.run([self.adb, "-s", self.serial, "shell", *args], capture_output=True, check=False)
        return out.stdout.decode("utf-8", "replace")

    def nodes(self) -> list[Node]:
        self.shell("uiautomator", "dump", DUMP)
        return parse_nodes(self.shell("cat", DUMP))

    def tap(self, node: Node) -> None:
        self.shell("input", "tap", str(node.x), str(node.y))
        time.sleep(0.8)

    def scroll(self, down: bool = True) -> None:
        # The left third, clear of a picture-in-picture window in the corner.
        x = str(self.width // 4)
        low, high = str(self.height * 3 // 4), str(self.height * 3 // 10)
        self.shell("input", "swipe", x, low if down else high, x, high if down else low, "300")
        time.sleep(0.6)

    def find(self, label: str, contains: bool = False, swipes: int = 0, down: bool = True) -> Node | None:
        for attempt in range(swipes + 1):
            for node in self.nodes():
                if node.label == label or (contains and label in node.label):
                    return node
            if attempt < swipes:
                self.scroll(down)
        return None

    def tap_label(self, label: str, contains: bool = False, swipes: int = 0) -> None:
        node = self.find(label, contains, swipes)
        if node is None:
            raise StepFailed(f"no {label!r} on screen")
        self.tap(node)

    def tab(self, name: str) -> None:
        node = bottom_most(self.nodes(), name)
        if node is None:
            raise StepFailed(f"no {name} tab")
        self.tap(node)

    def back(self) -> None:
        # The system Back key: a screen's own back arrow scrolls away with it.
        self.shell("input", "keyevent", "4")
        time.sleep(0.8)

    def reset(self) -> None:
        """Back out of a number's screen, or relaunch, until the tab bar shows."""
        for attempt in range(5):
            nodes = self.nodes()
            if bottom_most(nodes, "Home") and bottom_most(nodes, "More"):
                return
            if attempt < 3:
                self.back()
            else:
                self.shell("am", "start", "-n", ACTIVITY)
                time.sleep(2)
        raise StepFailed("couldn't get back to the tab bar")

    def to_top(self) -> None:
        # Until the screen stops changing, which also waits out a fling.
        before: list[str] = []
        for _ in range(15):
            self.scroll(down=False)
            labels = [n.label for n in self.nodes()]
            if labels == before:
                return
            before = labels


class StepFailed(Exception):
    pass


def lookup(phone: Phone, number: str) -> None:
    phone.tab("Lookup")
    phone.to_top()
    clear = phone.find("Close")
    if clear:
        phone.tap(clear)
    phone.tap_label("Phone number")
    phone.shell("input", "text", number)
    phone.shell("input", "keyevent", "111")  # Escape closes the keyboard.
    phone.tap_label("Check number")
    time.sleep(1.5)


def undo_snackbar(phone: Phone, what: str) -> None:
    undo = phone.find("Undo")
    if undo is None:
        raise StepFailed(f"no Undo after {what}")
    phone.tap(undo)


def wildcards_empty(phone: Phone) -> None:
    phone.tab("Rules")
    phone.tap_label("Wildcards")
    if phone.find("No wildcard rules") is None:
        raise StepFailed("a wildcard rule is still there")


def step_version(phone: Phone, version: str) -> None:
    installed = re.search(r"versionName=(\S+)", phone.shell("dumpsys", "package", PACKAGE))
    if not installed or installed.group(1) != version:
        raise StepFailed(f"installed {installed.group(1) if installed else 'nothing'}, expected {version}")


def step_sync(phone: Phone) -> None:
    phone.tab("Home")
    phone.to_top()
    # "Synced just now" shows for about a minute after a sync ends.
    for _ in range(2):
        phone.tap_label("Sync database")
        for _ in range(12):
            if phone.find("Synced just now"):
                return
            time.sleep(3)
    raise StepFailed("never showed Synced just now")


def step_whats_new(phone: Phone, version: str) -> None:
    phone.tab("More")
    phone.tap_label("What's new", swipes=8)
    labels = [n.label for n in phone.nodes()]
    phone.back()
    if f"v{version}" not in labels or "LATEST" not in labels:
        raise StepFailed(f"latest release isn't v{version}")


def step_catalog(phone: Phone, names: list[str]) -> None:
    phone.tab("More")
    phone.to_top()
    phone.tap_label("Settings")
    phone.to_top()
    phone.tap_label("Advanced")
    seen: set[str] = set()
    try:
        for _ in range(40):
            labels = [n.label for n in phone.nodes()]
            seen.update(label for label in labels if label in names)
            if len(seen) == len(names) or "FEED MIRROR" in labels:
                break
            phone.scroll()
    finally:
        phone.to_top()
        phone.tap_label("Basic")
        phone.back()
    missing = [n for n in names if n not in seen]
    if missing:
        raise StepFailed(f"Recommended lists is missing {missing}")


def step_lookup_area_code(phone: Phone) -> None:
    lookup(phone, AREA_NUMBER)
    offer = phone.find(f"Block all {AREA_LABEL} numbers", swipes=2)
    if offer is None:
        raise StepFailed(f"Lookup didn't offer {AREA_LABEL}")
    phone.tap(offer)
    undo_snackbar(phone, "the area code block")
    wildcards_empty(phone)


def step_no_area_code(phone: Phone) -> None:
    lookup(phone, NO_AREA_NUMBER)
    if phone.find("Block all", contains=True, swipes=2):
        raise StepFailed(f"Lookup offered an area code for {NO_AREA_NUMBER}")
    phone.tap_label("Open full detail", swipes=2)
    found = phone.find("Block all", contains=True, swipes=2)
    phone.back()
    if found:
        raise StepFailed(f"the number's screen offered an area code for {NO_AREA_NUMBER}")


def step_detail_area_code(phone: Phone) -> None:
    lookup(phone, AREA_NUMBER)
    phone.tap_label("Open full detail", swipes=2)
    phone.tap_label(f"Block all {AREA_LABEL} numbers", swipes=3)
    undo_snackbar(phone, "the area code block on the number's screen")
    phone.back()
    wildcards_empty(phone)


def my_reports(phone: Phone) -> list[str]:
    phone.tab("More")
    phone.to_top()
    phone.tap_label("My reports", swipes=6)
    labels = [n.label for n in phone.nodes()]
    phone.back()
    return labels


def step_not_spam_undo(phone: Phone, number: str) -> None:
    before = my_reports(phone)
    lookup(phone, number)
    phone.tap_label("Open full detail", swipes=3)
    phone.tap_label("Not spam", swipes=6)
    undo_snackbar(phone, "Not spam")
    phone.back()
    time.sleep(6)  # Past the five seconds a missed Undo would have taken.
    if my_reports(phone) != before:
        raise StepFailed("My reports changed after Not spam's Undo")


def step_report_blocks(phone: Phone, number: str) -> None:
    lookup(phone, number)
    phone.tap_label("Open full detail", swipes=3)
    phone.tap_label("Report", swipes=3)
    time.sleep(2)
    message = phone.find("Blocked on this phone.", contains=True)
    if message is None:
        raise StepFailed("Report's message doesn't say it blocked the number")
    undo_snackbar(phone, "Report's block")
    time.sleep(1)
    blocked = phone.find("Unblock", swipes=3, down=False)
    phone.back()
    if blocked:
        raise StepFailed("the number is still blocked after Undo")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True, help="adb serial of the phone")
    parser.add_argument("--adb", default="adb", help="path to adb")
    parser.add_argument("--report", metavar="NUMBER", help="also check that Report blocks NUMBER here (sends a real report)")
    parser.add_argument("--database-number", default="+13152328257", help="a number in the shared database, for Not spam")
    args = parser.parse_args()

    phone = Phone(args.adb, args.serial)
    version = app_version()
    phone.shell("input", "keyevent", "KEYCODE_WAKEUP")
    phone.shell("am", "start", "-n", ACTIVITY)
    time.sleep(2)

    steps = [
        ("installed version", lambda: step_version(phone, version)),
        ("database sync", lambda: step_sync(phone)),
        ("What's new shows this version", lambda: step_whats_new(phone, version)),
        ("Recommended lists match the catalog", lambda: step_catalog(phone, catalog_names())),
        ("Lookup offers +33 1, and Undo removes it", lambda: step_lookup_area_code(phone)),
        ("no area code offer for +81 42", lambda: step_no_area_code(phone)),
        ("number's screen offers +33 1, and Undo removes it", lambda: step_detail_area_code(phone)),
        ("Not spam then Undo sends nothing", lambda: step_not_spam_undo(phone, args.database_number)),
    ]
    if args.report:
        steps.append(("Report blocks the number, and Undo unblocks it", lambda: step_report_blocks(phone, args.report)))

    failed = 0
    for name, step in steps:
        try:
            phone.reset()
            step()
            print(f"PASS  {name}")
        except StepFailed as error:
            failed += 1
            print(f"FAIL  {name}: {error}")
    phone.shell("rm", "-f", DUMP)
    print(f"{len(steps) - failed} of {len(steps)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
