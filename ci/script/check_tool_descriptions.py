#!/usr/bin/env python3
"""Keep AI tool descriptions out of hardcoded literals.

Every tool the model can call is registered in ToolRegistration.kt with a
`descriptionGenerator`. That description is the model's only instruction on
when and how to use the tool, so it has to be tellable to a human translator
and it has to survive review.

A description written as a string literal inside the registration is neither:
it cannot be localized, and it is invisible to translators and to the
localization checks. During cleanup 78 such literals were found and moved into
strings.xml. This check exists so the next one is caught at review time
instead of months later.

Lines the candidate did not touch are left alone. Descriptions that were
already hardcoded before this work started are reported as notes, not errors,
so the check can be adopted while those are still being migrated. The moment
someone adds a literal, or rewrites an old one instead of moving it, the line
is new and the check fails.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

from check_output import Diagnostic, report


REGISTRATION_PATH = "app/src/main/java/com/ai/assistance/operit/core/tools/ToolRegistration.kt"

# Matches `registerTool(` and `registerSoftwareSettingsTool(` alike: both take
# name / descriptionGenerator / executor and both were found carrying literals.
REGISTER_CALL_RE = re.compile(r"\bregister(?:SoftwareSettings)?Tool\(")

# A descriptionGenerator whose body opens with a double-quoted string, e.g.
#     descriptionGenerator = { "Close the current browser tab" }
# Excluded are template literals with interpolation, which depend on a runtime
# value and so are not a fixed translatable string.
LITERAL_DESCRIPTION_RE = re.compile(
    r"descriptionGenerator\s*=\s*\{\s*"
    r"(?:[A-Za-z_][A-Za-z0-9_]*\s*->\s*)?"
    r'"((?:[^"\\\n]|\\.)*)"'
)

NAME_RE = re.compile(r'name\s*=\s*"([^"]*)"')

HUNK_RE = re.compile(r"^@@ -\S+ \+(\d+)(?:,(\d+))? @@")


@dataclass(frozen=True)
class DescriptionLiteral:
    tool: str
    line: int
    text: str


def run_git(*args: str, text: bool = True) -> str | bytes:
    result = subprocess.run(["git", *args], check=True, capture_output=True, text=text)
    return result.stdout


def show_file(sha: str, path: str) -> str | None:
    result = subprocess.run(
        ["git", "show", f"{sha}:{path}"],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        return None
    return result.stdout.decode("utf-8", errors="replace")


def changed_paths(base_sha: str, candidate_sha: str) -> set[str]:
    output = bytes(
        run_git(
            "diff",
            "--name-only",
            "--no-renames",
            "--diff-filter=ACMRDT",
            "-z",
            base_sha,
            candidate_sha,
            text=False,
        )
    )
    return {os.fsdecode(value) for value in output.split(b"\0") if value}


def added_line_numbers(base_sha: str, candidate_sha: str, path: str) -> set[int]:
    """Line numbers in the candidate file that the diff adds.

    This is what separates a literal someone just wrote from one that has been
    sitting there since before the migration began.
    """
    diff = str(
        run_git(
            "diff",
            "--unified=0",
            "--no-color",
            base_sha,
            candidate_sha,
            "--",
            path,
        )
    )

    added: set[int] = set()
    current: int | None = None
    for line in diff.splitlines():
        hunk = HUNK_RE.match(line)
        if hunk:
            current = int(hunk.group(1))
            continue
        if current is None:
            continue
        if line.startswith("+++") or line.startswith("---"):
            continue
        if line.startswith("+"):
            added.add(current)
            current += 1
        elif line.startswith(" "):
            current += 1
    return added


def enclosing_call(text: str, position: int) -> tuple[int, int]:
    """Return the (start, end) offsets of the register call containing `position`."""
    start = text.rfind("registerTool(", 0, position + 1)
    settings = text.rfind("registerSoftwareSettingsTool(", 0, position + 1)
    start = max(start, settings)
    if start == -1:
        return 0, len(text)

    open_paren = text.index("(", start)
    depth = 0
    index = open_paren
    while index < len(text):
        character = text[index]
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
            if depth == 0:
                return start, index + 1
        index += 1
    return start, len(text)


def collect_literals(text: str | None) -> list[DescriptionLiteral]:
    if text is None:
        return []

    literals: list[DescriptionLiteral] = []
    for match in LITERAL_DESCRIPTION_RE.finditer(text):
        call_start, call_end = enclosing_call(text, match.start())
        call = text[call_start:call_end]
        name_match = NAME_RE.search(call)
        tool = name_match.group(1) if name_match else "<unnamed>"
        line = text.count("\n", 0, match.start()) + 1
        literals.append(DescriptionLiteral(tool=tool, line=line, text=match.group(1)))
    return literals


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--candidate", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    if REGISTRATION_PATH not in changed_paths(args.base, args.candidate):
        return report(
            "Tool descriptions",
            [],
            notes=[f"{REGISTRATION_PATH} is unchanged; nothing to check."],
        )

    literals = collect_literals(show_file(args.candidate, REGISTRATION_PATH))
    touched = added_line_numbers(args.base, args.candidate, REGISTRATION_PATH)

    errors: list[Diagnostic] = []
    pre_existing = 0
    for literal in literals:
        if literal.line not in touched:
            pre_existing += 1
            continue
        errors.append(
            Diagnostic(
                code="hardcoded-tool-description",
                path=REGISTRATION_PATH,
                line=literal.line,
                message=(
                    f"tool '{literal.tool}' describes itself with a hardcoded string literal "
                    f'("{literal.text}"); add a toolreg_* entry to '
                    f"app/src/main/res/values/strings.xml and reference it via s(R.string....)"
                ),
            )
        )

    remaining = pre_existing
    notes = [
        f"hardcoded literals touched by this candidate: {len(errors)}",
        f"hardcoded literals left over from before the migration "
        f"(not blocking, still to be moved): {remaining}",
    ]
    if remaining:
        notes.append(
            "run ci/script/check_tool_descriptions.py locally to list them; "
            "they fail the check once a line is touched"
        )

    return report("Tool descriptions", errors, notes=notes)


if __name__ == "__main__":
    raise SystemExit(main())
