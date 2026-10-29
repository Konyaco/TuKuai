#!/usr/bin/env python3
"""Uploads the release jars tukuai-<loader>-<minecraft>-<version>.jar to Modrinth and CurseForge.

Usage: publish.py <version> <folder with the jars> [--sites modrinth,curseforge] [--dry-run]

Reads MODRINTH_TOKEN, MODRINTH_PROJECT_ID (default tukuai), CURSEFORGE_TOKEN and
CURSEFORGE_PROJECT_ID from the environment. A site without its token or project id is skipped
with a notice. The Minecraft versions each jar goes up for are the release versions its own
fabric.mod.json or neoforge.mods.toml accepts. --dry-run prints what would be sent and only reads.
"""
import argparse
import json
import os
import pathlib
import re
import sys
import tomllib
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODRINTH = "https://api.modrinth.com/v2"
CURSEFORGE = "https://minecraft.curseforge.com"
JAR = re.compile(r"tukuai-(fabric|neoforge)-(.+)-(.+)\.jar")
LOADER_NAMES = {"fabric": "Fabric", "neoforge": "NeoForge"}
DEPENDENCIES = {"fabric": ["fabric-api", "fabric-language-kotlin"], "neoforge": ["kotlin-for-forge"]}


class ApiError(Exception):
    pass


def notice(text):
    print(f"::notice::{text}")


def request(method, url, headers, body=None, content_type=None):
    """Sends one request and returns the parsed JSON answer, or None for a 404 on a GET."""
    headers = dict(headers)
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            text = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8", "replace")
        if error.code == 404 and method == "GET":
            return None
        raise ApiError(f"{method} {url} failed with HTTP {error.code}:\n{text}") from None
    return json.loads(text) if text.strip() else None


def multipart(fields, file_field, path):
    """Writes a multipart/form-data body: the JSON fields first, then the jar."""
    boundary = uuid.uuid4().hex
    parts = []
    for name, value in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n'
                     f"Content-Type: application/json\r\n\r\n".encode() + json.dumps(value).encode() + b"\r\n")
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; filename="{path.name}"\r\n'
                 f"Content-Type: application/java-archive\r\n\r\n".encode() + path.read_bytes() + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def changelog(version):
    """The release notes without the download table, since each store file is one loader and version."""
    text = (ROOT / f".github/release-notes/v{version}.md").read_text(encoding="utf-8")
    text = re.sub(r"^## 下载哪个\n.*?(?=^## )", "", text, flags=re.S | re.M)
    return text.strip() + "\n"


def numbers(version):
    return tuple(int(part) for part in version.split("."))


def fabric_accepts(predicate, version):
    """One fabric.mod.json version predicate, such as 1.21.1, ~26.1.2, >=1.21 or 1.21.x."""
    if predicate in ("*", ""):
        return True
    match = re.fullmatch(r"(>=|<=|>|<|=|~|\^)?(.+)", predicate)
    op, target = match.group(1) or "=", match.group(2)
    if target.endswith(".x") or target.endswith(".*"):
        return version == target[:-2] or version.startswith(target[:-1])
    have, want = numbers(version), numbers(target)
    if op == "~":
        return have >= want and have[:2] == want[:2]
    if op == "^":
        return have >= want and have[:1] == want[:1]
    return {"=": have == want, ">=": have >= want, "<=": have <= want, ">": have > want, "<": have < want}[op]


def maven_accepts(spec, version):
    """A NeoForge version range such as [26.1.2], [1.21,1.22) or [1.21.1,)."""
    have = numbers(version)
    for opening, inside, closing in re.findall(r"([\[(])([^\[\]()]*)([\])])", spec):
        if "," not in inside:
            if have == numbers(inside):
                return True
            continue
        low, high = (part.strip() for part in inside.split(",", 1))
        above = not low or (have >= numbers(low) if opening == "[" else have > numbers(low))
        below = not high or (have <= numbers(high) if closing == "]" else have < numbers(high))
        if above and below:
            return True
    return False


def minecraft_range(path, loader):
    with zipfile.ZipFile(path) as jar:
        if loader == "fabric":
            depends = json.loads(jar.read("fabric.mod.json"))["depends"]["minecraft"]
            alternatives = depends if isinstance(depends, list) else [depends]
            return " || ".join(alternatives), lambda v: any(all(fabric_accepts(p, v) for p in a.split()) for a in alternatives)
        toml = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode("utf-8"))
        spec = next(d["versionRange"] for deps in toml["dependencies"].values() for d in deps if d["modId"] == "minecraft")
        return spec, lambda v: maven_accepts(spec, v)


def java_version(minecraft):
    properties = ROOT / f"versions/{minecraft}/gradle.properties"
    if properties.exists():
        match = re.search(r"^java_version=(\d+)", properties.read_text(encoding="utf-8"), re.M)
        if match:
            return match.group(1)
    return None


def find_jars(version, folder):
    jars = []
    for path in sorted(pathlib.Path(folder).glob("tukuai-*.jar")):
        match = JAR.fullmatch(path.name)
        if match and match.group(3) == version:
            jars.append({"path": path, "loader": match.group(1), "minecraft": match.group(2)})
    if not jars:
        raise ApiError(f"No tukuai-<loader>-<minecraft>-{version}.jar in {folder}")
    return jars


def display_name(version, jar):
    return f"TuKuai {version} ({LOADER_NAMES[jar['loader']]} {jar['minecraft']})"


def show(method, url, fields, path, notes):
    """Prints a request as it would be sent, with the long changelog shortened."""
    print(f"  would {method} {url}")
    for name, value in fields.items():
        value = dict(value, changelog=f"<the changelog above, {len(notes)} characters>")
        print(f"  part {name}: " + json.dumps(value, ensure_ascii=False, indent=2).replace("\n", "\n  "))
    print(f"  part file: {path.name} ({path.stat().st_size} bytes)")


def publish_modrinth(version, jars, notes, dry_run):
    token = os.environ.get("MODRINTH_TOKEN", "")
    if not token and not dry_run:
        notice("Modrinth skipped: the MODRINTH_TOKEN secret is not set")
        return
    headers = {"User-Agent": f"Konyaco/TuKuai/{version} (github.com/Konyaco/TuKuai)"}
    if token:
        headers["Authorization"] = token
    slug = os.environ.get("MODRINTH_PROJECT_ID") or "tukuai"
    print(f"Modrinth: project {slug}")
    project = request("GET", f"{MODRINTH}/project/{slug}", headers)
    if project is None:
        if not dry_run:
            raise ApiError(f"Modrinth has no project {slug} that MODRINTH_TOKEN can see")
        print(f"  Modrinth has no public project {slug} yet; the requests below use it as the project id")
        project_id, existing = slug, set()
    else:
        project_id = project["id"]
        existing = {v["version_number"] for v in request("GET", f"{MODRINTH}/project/{project_id}/version", headers)}
        print(f"  id {project_id}, {len(existing)} versions already up")
    dependency_ids = {}
    for dependency in sorted({d for deps in DEPENDENCIES.values() for d in deps}):
        found = request("GET", f"{MODRINTH}/project/{dependency}", headers)
        if found is None:
            raise ApiError(f"Modrinth has no project {dependency}")
        dependency_ids[dependency] = found["id"]
        print(f"  dependency {dependency} is {found['id']}")
    for jar in jars:
        number = f"{version}+{jar['loader']}-{jar['minecraft']}"
        print(f"- {jar['path'].name} as {number}")
        if number in existing:
            print("  already up, skipped")
            continue
        data = {
            "project_id": project_id,
            "name": display_name(version, jar),
            "version_number": number,
            "changelog": notes,
            "dependencies": [{"project_id": dependency_ids[d], "dependency_type": "required"}
                             for d in DEPENDENCIES[jar["loader"]]],
            "game_versions": jar["game_versions"],
            "loaders": [jar["loader"]],
            "version_type": "release",
            "status": "listed",
            "featured": False,
            "file_parts": ["file"],
            "primary_file": "file",
        }
        if dry_run:
            show("POST", f"{MODRINTH}/version", {"data": data}, jar["path"], notes)
            continue
        body, content_type = multipart({"data": data}, "file", jar["path"])
        made = request("POST", f"{MODRINTH}/version", headers, body, content_type)
        print(f"  uploaded as https://modrinth.com/mod/{project.get('slug', slug)}/version/{made['id']}")


def curseforge_versions(types, versions, jar):
    """Looks up the CurseForge game version ids for a jar's Minecraft versions, loader, sides and Java."""
    def find(name, accept_type):
        type_ids = {t["id"] for t in types if accept_type(t["slug"])}
        ids = [v["id"] for v in versions if v["name"] == name and v["gameVersionTypeID"] in type_ids]
        return ids[0] if ids else None

    wanted = [(m, lambda s: s.startswith("minecraft-"), True) for m in jar["game_versions"]]
    wanted += [(LOADER_NAMES[jar["loader"]], lambda s: s == "modloader", True),
               ("Client", lambda s: s == "environment", True),
               ("Server", lambda s: s == "environment", True)]
    if jar["java"]:
        wanted.append((f"Java {jar['java']}", lambda s: s == "java", False))
    ids = []
    for name, accept_type, required in wanted:
        found = find(name, accept_type)
        if found is None and required:
            raise ApiError(f"CurseForge has no game version named {name}")
        if found is not None:
            ids.append(found)
    return ids


def publish_curseforge(version, jars, notes, dry_run):
    token = os.environ.get("CURSEFORGE_TOKEN", "")
    project_id = os.environ.get("CURSEFORGE_PROJECT_ID", "")
    if not dry_run and (not token or not project_id):
        missing = "the CURSEFORGE_TOKEN secret" if not token else "the CURSEFORGE_PROJECT_ID variable"
        notice(f"CurseForge skipped: {missing} is not set")
        return
    headers = {"X-Api-Token": token, "User-Agent": f"Konyaco/TuKuai/{version} (github.com/Konyaco/TuKuai)"}
    print(f"CurseForge: project {project_id or '<CURSEFORGE_PROJECT_ID>'}")
    if not token:
        print("  CURSEFORGE_TOKEN is not set, so the game version names below are not checked or turned into ids")
    else:
        types = request("GET", f"{CURSEFORGE}/api/game/version-types", headers)
        versions = request("GET", f"{CURSEFORGE}/api/game/versions", headers)
    for jar in jars:
        print(f"- {jar['path'].name}")
        names = jar["game_versions"] + [LOADER_NAMES[jar["loader"]], "Client", "Server"]
        names += [f"Java {jar['java']}"] if jar["java"] else []
        metadata = {
            "changelog": notes,
            "changelogType": "markdown",
            "displayName": display_name(version, jar),
            "gameVersions": curseforge_versions(types, versions, jar) if token else names,
            "releaseType": "release",
            "relations": {"projects": [{"slug": d, "type": "requiredDependency"} for d in DEPENDENCIES[jar["loader"]]]},
        }
        url = f"{CURSEFORGE}/api/projects/{project_id or '<CURSEFORGE_PROJECT_ID>'}/upload-file"
        if dry_run:
            show("POST", url, {"metadata": metadata}, jar["path"], notes)
            continue
        body, content_type = multipart({"metadata": metadata}, "file", jar["path"])
        made = request("POST", url, headers, body, content_type)
        print(f"  uploaded as file {made['id']}")


def main():
    parser = argparse.ArgumentParser(description="Uploads the release jars to Modrinth and CurseForge.")
    parser.add_argument("version", help="the mod version, such as 0.9.0")
    parser.add_argument("folder", help="the folder holding tukuai-<loader>-<minecraft>-<version>.jar")
    parser.add_argument("--sites", default="modrinth,curseforge", help="comma-separated: modrinth, curseforge")
    parser.add_argument("--dry-run", action="store_true", help="print the requests and only read from the sites")
    args = parser.parse_args()
    sites = [s.strip() for s in args.sites.split(",") if s.strip()]
    for site in sites:
        if site not in ("modrinth", "curseforge"):
            parser.error(f"unknown site {site}")
    try:
        notes = changelog(args.version)
        jars = find_jars(args.version, args.folder)
        releases = request("GET", f"{MODRINTH}/tag/game_version",
                           {"User-Agent": f"Konyaco/TuKuai/{args.version} (github.com/Konyaco/TuKuai)"})
        releases = [v["version"] for v in releases if v["version_type"] == "release"]
        for jar in jars:
            declared, accepts = minecraft_range(jar["path"], jar["loader"])
            jar["game_versions"] = sorted((v for v in releases if accepts(v)), key=numbers)
            jar["java"] = java_version(jar["minecraft"])
            if jar["minecraft"] not in jar["game_versions"]:
                raise ApiError(f"{jar['path'].name} declares Minecraft {declared}, which leaves out {jar['minecraft']}")
            print(f"{jar['path'].name}: declares Minecraft {declared}, so {', '.join(jar['game_versions'])}")
        if args.dry_run:
            print(f"Changelog ({len(notes)} characters):\n{notes}")
        for site in sites:
            (publish_modrinth if site == "modrinth" else publish_curseforge)(args.version, jars, notes, args.dry_run)
    except ApiError as error:
        print(f"::error::{error}")
        sys.exit(1)


if __name__ == "__main__":
    main()
