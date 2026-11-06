"""Offline, fail-closed Samsung Home Up appOrder editor (Python 3.9+).

Unofficial and version-specific. This edits a file, not the device. Native Home Up
restoration is a full-layout operation; retain a fresh original for rollback.
"""
import argparse
import collections
import copy
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

MAX_BYTES = 8 * 1024 * 1024


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def parse_exml(data):
    if not isinstance(data, bytes) or not data or len(data) > MAX_BYTES:
        raise ValueError("Expected a nonempty EXML file no larger than 8 MiB")
    if re.search(br"<!\s*(?:DOCTYPE|ENTITY)\b", data, re.I):
        raise ValueError("DTD/entity declarations are not supported")
    try:
        data.decode("utf-8", errors="strict")
        body = re.sub(br"<\?xml\s[^>]*\?>", b"", data)
        root = ET.fromstring(b"<exml>" + body + b"</exml>")
    except (ET.ParseError, UnicodeError) as error:
        raise ValueError("Invalid UTF-8 native EXML") from error
    if len(root.findall("appOrder")) != 1:
        raise ValueError("Expected exactly one top-level appOrder block")
    return root


def component(node):
    key = node.get("packageName"), node.get("className")
    if any(not isinstance(value, str) or not value for value in key):
        raise ValueError("Every favorite needs its exact packageName and className")
    return key


def source_items(root):
    order = root.find("appOrder")
    if root.findtext("viewType_appOrder") != "CUSTOM":
        raise ValueError("Only CUSTOM Apps order is supported")
    for child in order:
        if child.tag not in ("folder", "favorite"):
            raise ValueError("Unsupported item in appOrder")
        if child.tag == "folder" and any(n.tag != "favorite" or len(n) for n in child):
            raise ValueError("Unsupported nested item in folder")
        if child.tag == "favorite" and len(child):
            raise ValueError("Unsupported nested favorite content")
    items = list(order.iter("favorite"))
    counts = collections.Counter(component(item) for item in items)
    if not items or any(n != 1 for n in counts.values()):
        raise ValueError("Source must have nonempty, unique components")
    if any(item.get("hidden", "0") != "0" for item in items):
        raise ValueError("Hidden Apps entries need separate preservation review")
    return order, items, counts


def positive_number(root, tag):
    value = root.findtext(tag, "")
    if not re.fullmatch(r"[1-9][0-9]*", value):
        raise ValueError("Invalid or absent " + tag)
    return int(value)


def build(data, plan):
    root = parse_exml(data)
    original, items, counts = source_items(root)
    if not isinstance(plan, dict) or not isinstance(plan.get("folders"), list) or not plan["folders"]:
        raise ValueError("Plan needs a nonempty folders list")
    folders = plan["folders"]
    names, assignments = [], []
    for folder in folders:
        if not isinstance(folder, dict):
            raise ValueError("Invalid folder object")
        name, apps = folder.get("name"), folder.get("apps")
        if not isinstance(name, str) or not name.strip():
            raise ValueError("Folder names must be nonempty strings")
        if not isinstance(apps, list) or len(apps) < 2:
            raise ValueError("Each folder must contain at least two apps")
        names.append(name)
        for app in apps:
            if not isinstance(app, dict) or any(not isinstance(app.get(k), str) or not app[k] for k in ("package", "activity")):
                raise ValueError("Each assignment needs exact package and activity strings")
            assignments.append((app["package"], app["activity"]))
    if len(set(names)) != len(names):
        raise ValueError("Duplicate folder names")
    if collections.Counter(assignments) != counts:
        raise ValueError("Plan must cover the exact source component multiset once")
    grid = re.fullmatch(r"([1-9][0-9]*)X([1-9][0-9]*)", root.findtext("FolderGrid", ""))
    if not grid:
        raise ValueError("Unsupported native FolderGrid")
    folder_capacity = int(grid[1]) * int(grid[2])
    app_capacity = positive_number(root, "Rows_appOrder") * positive_number(root, "Columns_appOrder")
    by_component = {component(item): item for item in items}
    replacement_root = ET.Element("appOrder", original.attrib)
    for folder_index, folder in enumerate(folders):
        target = ET.SubElement(replacement_root, "folder", {
            "screen": str(folder_index // app_capacity), "title": folder["name"],
            "options": "0", "color": "0",
        })
        for index, app in enumerate(folder["apps"]):
            item = copy.deepcopy(by_component[(app["package"], app["activity"])])
            item.tail = None
            item.set("screen", str(index // folder_capacity))
            target.append(item)
    ET.indent(replacement_root, space="  ")
    replacement = ET.tostring(replacement_root, encoding="utf-8", short_empty_elements=True)
    opening, closing = b"<appOrder>", b"</appOrder>"
    if data.count(opening) != 1 or data.count(closing) != 1:
        raise ValueError("Unsupported or ambiguous native appOrder byte boundary")
    start = data.index(opening)
    end = data.index(closing, start) + len(closing)
    output = data[:start] + replacement + data[end:]
    verified = parse_exml(output)
    actual = verified.find("appOrder")
    if collections.Counter(component(item) for item in actual.iter("favorite")) != counts:
        raise ValueError("Output component preservation failed")
    if [ET.tostring(n) for n in root if n.tag != "appOrder"] != [ET.tostring(n) for n in verified if n.tag != "appOrder"]:
        raise ValueError("Protected sections changed")
    if output[:start] != data[:start] or output[start + len(replacement):] != data[end:]:
        raise ValueError("Protected bytes changed")
    for item in actual.iter("favorite"):
        before, after = by_component[component(item)].attrib.copy(), item.attrib.copy()
        before.pop("screen", None)
        after.pop("screen", None)
        if before != after:
            raise ValueError("Favorite metadata other than page changed")
    report = {
        "source_sha256": sha256(data), "modified_sha256": sha256(output),
        "apps": len(items), "folder_count": len(folders),
        "folders": [{"name": f["name"], "apps": len(f["apps"])} for f in folders],
        "app_pages": (len(folders) + app_capacity - 1) // app_capacity,
        "folder_page_capacity": folder_capacity, "only_changed_block": "appOrder",
        "all_other_bytes_identical": True,
        "all_components_and_nonpage_metadata_preserved": True,
        "protected_prefix_sha256": sha256(data[:start]),
        "protected_suffix_sha256": sha256(data[end:]),
        "device_restore_performed": False,
    }
    return output, report


def inspect(data):
    root = parse_exml(data)
    _, items, _ = source_items(root)
    return {"apps": len(items), "folders": [{"name": f.get("title"), "apps": len(list(f.iter("favorite")))} for f in root.find("appOrder") if f.tag == "folder"],
            "components": [{"package": component(n)[0], "activity": component(n)[1]} for n in items],
            "source_sha256": sha256(data)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    show = commands.add_parser("inspect", help="Inspect local backup components; no network")
    show.add_argument("source", type=Path)
    create = commands.add_parser("build", help="Create a new EXML; never restore the device")
    create.add_argument("--source", type=Path, required=True)
    create.add_argument("--plan", type=Path, required=True)
    create.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        data = args.source.read_bytes()
        if args.command == "inspect":
            print(json.dumps(inspect(data), ensure_ascii=False, indent=2))
            return 0
        target, report_path = args.output, args.output.with_suffix(".validation.json")
        if target.resolve() == args.source.resolve() or target.exists() or report_path.exists():
            raise ValueError("Refusing to overwrite source, output, or validation report")
        payload, report = build(data, json.loads(args.plan.read_text(encoding="utf-8")))
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as file:
            file.write(payload)
        with report_path.open("x", encoding="utf-8") as file:
            json.dump(report, file, ensure_ascii=False, indent=2)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError, TypeError) as error:
        print("Error: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
