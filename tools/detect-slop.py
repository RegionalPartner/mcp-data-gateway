#!/usr/bin/env python3
"""
GenAI slop detector — pre-commit hook for mcp-data-gateway.

Covers two modes depending on file type:

  MARKDOWN (.md)
    Analyzes full prose with three signals:
      1. Fingerprint phrases  — rare in human writing, endemic in LLM output (+8 pts each)
      2. Transition density   — overuse of "furthermore", "par ailleurs", etc. (+5-10 pts)
      3. Sentence burstiness  — LLMs produce suspiciously uniform sentences (+10-20 pts)

  CODE (.java / .rs / .kts)
    Extracts comment and docstring text first, then applies:
      1. General fingerprint phrases (same as markdown)
      2. Code-specific fingerprints — "is responsible for", "helper method to", etc. (+8 pts)
      3. Comment density — AI over-comments (>35% lines: +10 pts, >25%: +5 pts)
      4. Transition density in comment text
      5. Sentence burstiness in comment text

Thresholds (configurable via --warn / --fail):
  score >= 20  yellow WARNING  (commit passes)
  score >= 35  red    FAIL     (commit blocked, unless --warn-only)

Usage:
  python3 tools/detect-slop.py [--warn-only] [--warn N] [--fail N] <file> [<file>...]

Exit codes: 0 = clean or warn-only, 1 = at least one file exceeds fail threshold.
"""

from __future__ import annotations

import argparse
import re
import statistics
import sys
from pathlib import Path

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Fingerprint phrases — shared between docs and code analysis
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

_GENERAL_FINGERPRINTS: list[str] = [
    # English
    r"it(?:'s| is) worth noting",
    r"\bdelve into\b",
    r"\bseamlessly\b",
    r"\brest assured\b",
    r"\bfeel free to\b",
    r"(?:let(?:'s| us)) dive into",
    r"without further ado",
    r"\bin the ever[- ]evolving\b",
    r"i hope this (?:helps?|clarifies?)",
    r"as an ai (?:language model|assistant)",
    r"\bunderscores? the importance\b",
    r"(?:^|(?<=[.!?])\s+)(?:certainly|absolutely)[!,]",
    r"(?:^|(?<=[.!?])\s+)of course[!,]",
    # French
    r"il (?:convient|est important) de noter",
    r"n'hésitez pas à",
    r"\bplonger dans\b",
    r"en tant qu'(?:assistant|modèle) (?:ia|i\.a\.)",
    r"dans un paysage en (?:constante )?évolution",
    r"j'espère que (?:cela|ça) (?:vous aide|répond|clarifie)",
    r"(?:^|(?<=[.!?])\s+)bien sûr[!,]",
]

# Code-specific fingerprints — appear in AI-generated comments and docstrings.
# These are uncommon in human-written comments for this project's codebase.
_CODE_FINGERPRINTS: list[str] = [
    # "This X is responsible for Y" — classic AI Javadoc opener
    r"(?:this|the) (?:class|method|function|interface|struct|trait|service|handler)\s+is responsible for",
    # "Helper X to/for Y"
    r"\bhelper (?:class|method|function|utility)\s+(?:to|for|that)\b",
    # "This X handles/provides/implements/manages/ensures"
    r"(?:this|the) (?:class|method|function|struct)\s+(?:handles|provides|implements|manages|ensures)\b",
    # TODO placeholders that signal AI skipped an implementation detail
    r"(?://|#|(?<=\s)\*)\s*todo[:\s]+(?:add|implement|handle|consider|include|improve)\b",
    # "We " in code comments — first-person plural is rare in human code comments
    r"(?:^|//|#|\*)\s*we (?:use|need|want|can|should|must|have)\b",
    # Javadoc @param / @return that just restates the parameter name verbatim
    r"@param\s+\w+\s+(?:the|a|an)\s+\w+\s*$",
    r"@returns?\s+(?:the|a|an)\s+\w+\s*$",
    # "Note:" at the start of a comment line — AI loves this preamble
    r"(?:^|//|#|\*)\s*note:\s+(?:this|the|that|it)\b",
]

FINGERPRINT_PTS = 8

_ALL_FINGERPRINT_RE: list[tuple[str, re.Pattern[str]]] = [
    (spec, re.compile(spec, re.IGNORECASE | re.MULTILINE))
    for spec in _GENERAL_FINGERPRINTS + _CODE_FINGERPRINTS
]
_GENERAL_FINGERPRINT_RE: list[tuple[str, re.Pattern[str]]] = [
    (spec, re.compile(spec, re.IGNORECASE | re.MULTILINE))
    for spec in _GENERAL_FINGERPRINTS
]

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Transition words — shared
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

_TRANSITION_RE = re.compile(
    r"\b(furthermore|moreover|additionally|in addition|in conclusion|"
    r"to summarize|in summary|notably|crucially|it is important|"
    r"de plus|par ailleurs|en outre|en conclusion|pour résumer|"
    r"notamment|il est crucial|il est important)\b",
    re.IGNORECASE,
)
TRANSITION_HIGH   = 0.025
TRANSITION_MED    = 0.013
TRANSITION_PTS_H  = 10
TRANSITION_PTS_M  = 5

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Burstiness — shared
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

_SENTENCE_SPLIT = re.compile(r"[.!?]+\s+")
BURST_MIN    = 8     # minimum sentence count to score burstiness
BURST_CV_VL  = 0.25  # very low CV → very uniform → LLM-like
BURST_CV_L   = 0.35
BURST_PTS_VL = 20
BURST_PTS_L  = 10

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Comment density thresholds (code mode only)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

DENSITY_HIGH     = 0.35   # > 35 % of non-blank lines are comments
DENSITY_MED      = 0.25
DENSITY_PTS_HIGH = 10
DENSITY_PTS_MED  = 5
MIN_COMMENT_WORDS = 25    # skip code files with almost no comments

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Score thresholds
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

DEFAULT_WARN = 20
DEFAULT_FAIL = 35

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# File-type routing
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

_MARKDOWN_EXTS = {".md", ".rst", ".txt"}

# Maps extension → comment style family
_CODE_LANGS: dict[str, str] = {
    ".java": "c-style",   # // and /* */
    ".rs":   "c-style",   # // /// and /* */
    ".kt":   "c-style",
    ".kts":  "c-style",
    ".ts":   "c-style",
    ".js":   "c-style",
    ".py":   "hash",      # # and """ """
    ".sh":   "hash",
}


def _file_mode(path: Path) -> str:
    ext = path.suffix.lower()
    if ext in _MARKDOWN_EXTS:
        return "markdown"
    lang = _CODE_LANGS.get(ext)
    return lang if lang else "skip"


# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Markdown prose extractor
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

def _extract_prose(text: str) -> str:
    text = re.sub(r"(`{3,}|~{3,}).*?\1", "", text, flags=re.DOTALL)
    text = re.sub(r"`[^`\n]+`", " ", text)
    text = re.sub(r"^#{1,6}\s+.*$", "", text, flags=re.MULTILINE)
    text = re.sub(r"^\s*[-*+]\s+", " ", text, flags=re.MULTILINE)
    text = re.sub(r"^\s*\d+\.\s+", " ", text, flags=re.MULTILINE)
    text = re.sub(r"^\|.*\|$", "", text, flags=re.MULTILINE)
    text = re.sub(r"https?://\S+", " ", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Code comment extractor
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

def _extract_comments(text: str, style: str) -> tuple[str, float]:
    """Return (comment_text, comment_line_density).

    density = comment lines / total non-blank lines.
    """
    lines = text.splitlines()
    comment_parts: list[str] = []
    in_block = False
    comment_count = 0
    total_count = 0

    if style == "c-style":
        for line in lines:
            s = line.strip()
            if not s:
                continue
            total_count += 1

            if in_block:
                comment_count += 1
                content = re.sub(r"^\s*\*+\s?", "", s).rstrip("*/").strip()
                if content:
                    comment_parts.append(content)
                if "*/" in s:
                    in_block = False

            elif s.startswith("/*"):
                in_block = True
                comment_count += 1
                content = re.sub(r"^/\*+\s?", "", s).rstrip("*/").strip()
                if content:
                    comment_parts.append(content)
                if "*/" in s:
                    in_block = False

            elif s.startswith("///") or s.startswith("//"):
                comment_count += 1
                content = re.sub(r"^//+\s?", "", s)
                comment_parts.append(content)

    elif style == "hash":
        in_docstring = False
        docstring_delim = ""
        for line in lines:
            s = line.strip()
            if not s:
                continue
            total_count += 1

            if in_docstring:
                comment_count += 1
                if docstring_delim in s:
                    in_docstring = False
                else:
                    comment_parts.append(s)

            elif s.startswith('"""') or s.startswith("'''"):
                delim = s[:3]
                in_docstring = True
                docstring_delim = delim
                comment_count += 1
                content = s[3:].rstrip(delim).strip()
                if content:
                    comment_parts.append(content)
                if s.count(delim) >= 2 and len(s) > 3:
                    in_docstring = False

            elif s.startswith("#") and not s.startswith("#!"):
                comment_count += 1
                content = re.sub(r"^#+\s?", "", s)
                comment_parts.append(content)

    density = comment_count / total_count if total_count > 0 else 0.0
    return "\n".join(comment_parts), density


# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Shared scoring primitives
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

def _score_fingerprints(
    text: str,
    patterns: list[tuple[str, re.Pattern[str]]],
) -> tuple[int, list[str]]:
    total, reasons = 0, []
    for spec, compiled in patterns:
        hits = compiled.findall(text)
        if hits:
            pts = FINGERPRINT_PTS * len(hits)
            total += pts
            preview = str(hits[0])[:50].replace("\n", " ")
            reasons.append(f"  fingerprint ×{len(hits)}  \"{preview}\"  +{pts}")
    return total, reasons


def _score_transitions(text: str) -> tuple[int, list[str]]:
    words = text.split()
    if not words:
        return 0, []
    density = len(_TRANSITION_RE.findall(text)) / len(words)
    if density >= TRANSITION_HIGH:
        return TRANSITION_PTS_H, [
            f"  transition density {density:.1%} (>{TRANSITION_HIGH:.1%})  +{TRANSITION_PTS_H}"
        ]
    if density >= TRANSITION_MED:
        return TRANSITION_PTS_M, [
            f"  transition density {density:.1%} (moderate)  +{TRANSITION_PTS_M}"
        ]
    return 0, []


def _score_burstiness(text: str) -> tuple[int, list[str]]:
    sentences = [
        s.strip()
        for s in _SENTENCE_SPLIT.split(text)
        if len(s.strip().split()) >= 5
    ]
    if len(sentences) < BURST_MIN:
        return 0, []
    lengths = [len(s.split()) for s in sentences]
    mean = statistics.mean(lengths)
    std  = statistics.stdev(lengths)
    cv   = std / mean if mean > 0 else 1.0
    if cv < BURST_CV_VL:
        return BURST_PTS_VL, [
            f"  sentence burstiness CV={cv:.2f} (very uniform, LLM-like)  +{BURST_PTS_VL}"
        ]
    if cv < BURST_CV_L:
        return BURST_PTS_L, [
            f"  sentence burstiness CV={cv:.2f} (low)  +{BURST_PTS_L}"
        ]
    return 0, []


# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Mode-specific scorers
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

def _score_markdown(text: str) -> tuple[int, list[str]]:
    prose = _extract_prose(text)
    if len(prose.split()) < 80:
        return -1, []   # -1 = skip (too short)

    total, reasons = 0, []
    for sub_score, sub_reasons in [
        _score_fingerprints(prose, _GENERAL_FINGERPRINT_RE),
        _score_transitions(prose),
        _score_burstiness(prose),
    ]:
        total += sub_score
        reasons.extend(sub_reasons)
    return total, reasons


def _score_code(text: str, style: str) -> tuple[int, list[str]]:
    comment_text, density = _extract_comments(text, style)
    if len(comment_text.split()) < MIN_COMMENT_WORDS:
        return -1, []   # too few comments to be meaningful

    total, reasons = 0, []

    # All fingerprints (general + code-specific) applied to comment text
    sub_score, sub_reasons = _score_fingerprints(comment_text, _ALL_FINGERPRINT_RE)
    total += sub_score
    reasons.extend(sub_reasons)

    # Transition density in comment text
    sub_score, sub_reasons = _score_transitions(comment_text)
    total += sub_score
    reasons.extend(sub_reasons)

    # Burstiness of comment sentences
    sub_score, sub_reasons = _score_burstiness(comment_text)
    total += sub_score
    reasons.extend(sub_reasons)

    # Comment density
    if density >= DENSITY_HIGH:
        total += DENSITY_PTS_HIGH
        reasons.append(
            f"  comment density {density:.0%} (>{DENSITY_HIGH:.0%} of lines)  +{DENSITY_PTS_HIGH}"
        )
    elif density >= DENSITY_MED:
        total += DENSITY_PTS_MED
        reasons.append(
            f"  comment density {density:.0%} (>{DENSITY_MED:.0%} of lines)  +{DENSITY_PTS_MED}"
        )

    return total, reasons


# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Entry point
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

def main() -> int:
    parser = argparse.ArgumentParser(
        description="Detect AI-generated slop in documentation and code files."
    )
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument(
        "--warn-only", action="store_true",
        help="Print findings but always exit 0 (non-blocking).",
    )
    parser.add_argument(
        "--warn", type=int, default=DEFAULT_WARN, metavar="N",
        help=f"Score threshold for a yellow warning (default: {DEFAULT_WARN}).",
    )
    parser.add_argument(
        "--fail", type=int, default=DEFAULT_FAIL, metavar="N",
        help=f"Score threshold for a red failure (default: {DEFAULT_FAIL}).",
    )
    args = parser.parse_args()

    RED    = "\033[31m"
    YELLOW = "\033[33m"
    RESET  = "\033[0m"
    DIM    = "\033[2m"

    exit_code = 0

    for path in args.files:
        try:
            raw = path.read_text(encoding="utf-8", errors="ignore")
        except OSError as exc:
            print(f"[slop] cannot read {path}: {exc}", file=sys.stderr)
            continue

        mode = _file_mode(path)
        if mode == "skip":
            continue

        if mode == "markdown":
            total, reasons = _score_markdown(raw)
            kind = "prose"
        else:
            total, reasons = _score_code(raw, mode)
            kind = "comments"

        if total == -1:
            continue  # file too short / too few comments

        if total >= args.fail:
            color, label = RED, "FAIL"
            if not args.warn_only:
                exit_code = 1
        elif total >= args.warn:
            color, label = YELLOW, "WARN"
        else:
            continue  # clean

        print(
            f"{color}[slop:{label}]{RESET} {path}"
            f"  {DIM}({kind}, score={total}){RESET}"
        )
        for line in reasons:
            print(line)

    if exit_code != 0:
        print(
            f"\n{RED}[slop] Commit blocked — high AI-generated content score.{RESET}\n"
            "  Review flagged files and revise the prose/comments.\n"
            "  Override (use sparingly): git commit --no-verify",
            file=sys.stderr,
        )

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
