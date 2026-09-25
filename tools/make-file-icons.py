"""Bundles the Material Icon Theme's file and folder icons for the file tree.

Material Icon Theme (https://github.com/material-extensions/vscode-material-icon-theme) is the
MIT-licensed icon set many VS Code users know. This copies the dark-theme SVGs it maps to and
writes one small lookup file:

    app/src/main/assets/fileicons/<icon>.svg
    app/src/main/assets/fileicons/map.json   {"ext": {}, "names": {}, "folders": {}, "foldersOpen": {}, ...}

Usage: py -3 tools/make-file-icons.py <path to the unpacked npm package (the folder with dist/ and icons/)>
       (get it with: npm pack material-icon-theme && tar xzf material-icon-theme-*.tgz)
"""
import json
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "fileicons")

# VS Code maps many extensions through a language id rather than the extension itself, so the
# theme's languageIds need the editor's extension lists to be useful outside VS Code.
LANGUAGE_EXTENSIONS = {
    "html": ["html", "htm", "xhtml"],
    "css": ["css"],
    "scss": ["scss"],
    "less": ["less"],
    "javascript": ["js", "mjs", "cjs"],
    "javascriptreact": ["jsx"],
    "typescript": ["ts", "mts", "cts"],
    "typescriptreact": ["tsx"],
    "python": ["py", "pyi", "pyw"],
    "json": ["json"],
    "jsonc": ["jsonc"],
    "markdown": ["md", "markdown"],
    "java": ["java"],
    "kotlin": ["kt", "kts"],
    "c": ["c"],
    "cpp": ["cpp", "cc", "cxx", "hpp", "hh", "hxx"],
    "csharp": ["cs"],
    "go": ["go"],
    "rust": ["rs"],
    "ruby": ["rb"],
    "php": ["php"],
    "swift": ["swift"],
    "dart": ["dart"],
    "lua": ["lua"],
    "shellscript": ["sh", "bash", "zsh", "fish"],
    "powershell": ["ps1", "psm1"],
    "bat": ["bat", "cmd"],
    "yaml": ["yml", "yaml"],
    "xml": ["xml", "xsd", "xsl"],
    "sql": ["sql"],
    "dockerfile": ["dockerfile"],
    "plaintext": ["txt"],
    "vue": ["vue"],
    "svelte": ["svelte"],
    "r": ["r"],
    "perl": ["pl", "pm"],
    "scala": ["scala"],
    "groovy": ["groovy"],
    "haskell": ["hs"],
    "elixir": ["ex", "exs"],
    "clojure": ["clj", "cljs"],
    "fsharp": ["fs", "fsx"],
    "objective-c": ["m"],
    "objective-cpp": ["mm"],
    "makefile": ["mk"],
    "ini": ["ini"],
    "properties": ["properties"],
    "diff": ["diff", "patch"],
    "log": ["log"],
    "toml": ["toml"],
    "graphql": ["graphql", "gql"],
    "terraform": ["tf"],
    "zig": ["zig"],
    "julia": ["jl"],
    "nim": ["nim"],
    "erlang": ["erl"],
}


def main(package):
    manifest = json.load(open(os.path.join(package, "dist", "material-icons.json"), encoding="utf-8"))
    definitions = manifest["iconDefinitions"]

    ext = {k.lower(): v for k, v in manifest["fileExtensions"].items()}
    for language, icon in manifest.get("languageIds", {}).items():
        for e in LANGUAGE_EXTENSIONS.get(language, []):
            ext.setdefault(e, icon)
    lookup = {
        "ext": ext,
        "names": {k.lower(): v for k, v in manifest["fileNames"].items()},
        "folders": {k.lower(): v for k, v in manifest["folderNames"].items()},
        "foldersOpen": {k.lower(): v for k, v in manifest["folderNamesExpanded"].items()},
        "file": manifest["file"],
        "folder": manifest["folder"],
        "folderOpen": manifest["folderExpanded"],
    }

    used = set(lookup["ext"].values()) | set(lookup["names"].values()) | set(lookup["folders"].values()) \
        | set(lookup["foldersOpen"].values()) | {lookup["file"], lookup["folder"], lookup["folderOpen"]}

    # Point every entry straight at its file name.
    files = {}
    for icon in used:
        path = definitions[icon]["iconPath"]
        files[icon] = os.path.basename(path)
    for key in ("ext", "names", "folders", "foldersOpen"):
        lookup[key] = {k: files[v][:-4] for k, v in lookup[key].items()}
    for key in ("file", "folder", "folderOpen"):
        lookup[key] = files[lookup[key]][:-4]

    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    for name in sorted(set(files.values())):
        shutil.copy(os.path.join(package, "icons", name), os.path.join(OUT, name))
    shutil.copy(os.path.join(package, "LICENSE"), os.path.join(OUT, "LICENSE"))
    with open(os.path.join(OUT, "map.json"), "w", encoding="utf-8") as f:
        json.dump(lookup, f, separators=(",", ":"), sort_keys=True)
    print(f"{len(set(files.values()))} icons, {sum(len(lookup[k]) for k in ('ext', 'names', 'folders'))} mappings")


if __name__ == "__main__":
    main(sys.argv[1])
