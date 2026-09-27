"""Run the StopTextSpam SMS benchmark through OpenRouter.

Uses only the Python standard library so the benchmark does not need to add
project dependencies. Run it with ``uv run`` per the repository workflow.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import os
import statistics
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


API_URL = "https://openrouter.ai/api/v1/chat/completions"
DEFAULT_MODELS = (
    "google/gemini-3.1-flash-lite-preview",
    "~google/gemini-flash-latest",
    "~x-ai/grok-latest",
)


def app_v1_prompt(sender: str, body: str) -> str:
    """Historical first app prompt, retained for comparisons."""
    return (
        "Analyze this SMS message and determine if it is spam. "
        "Spam includes: political campaigns, scams, phishing, unsolicited marketing, "
        "automated robotexts, fake delivery notifications, loan offers, "
        "cryptocurrency schemes, and any other unwanted automated messages.\n\n"
        f"Sender: {sender}\n"
        f"Message: {body}\n\n"
        "Respond with ONLY a JSON object, no other text:\n"
        '{"spam": true/false, "reason": "brief reason if spam, empty string if not"}'
    )


def cautious_v2_prompt(sender: str, body: str) -> str:
    """Candidate prompt that protects legitimate transactional messages."""
    return (
        "Classify this SMS as spam or not spam using the sender and message only. "
        "Spam includes scams, phishing, impersonation, unsolicited sales or political "
        "outreach, deceptive wrong-number openers, and unwanted automated messages. "
        "Not spam includes plausible personal conversations and legitimate transactional "
        "messages such as requested delivery updates, receipts, appointments, rideshare "
        "updates, authentication codes, and account alerts that do not ask for passwords, "
        "sensitive information, money transfers, or payment through the message. "
        "A link, urgency, automated wording, or Reply STOP line is not sufficient by itself. "
        "Consider whether the domain matches the claimed organization, whether the user is "
        "asked to pay or disclose information, and whether the details are internally "
        "consistent. When evidence is genuinely ambiguous, choose not spam because the app "
        "suppresses notifications classified as spam.\n\n"
        f"Sender: {sender}\n"
        f"Message: {body}\n\n"
        "Respond with ONLY a JSON object, no other text:\n"
        '{"spam": true/false, "reason": "brief reason if spam, empty string if not"}'
    )


def political_v3_prompt(sender: str, body: str) -> str:
    """User policy: every politically focused message is spam."""
    return (
        "Classify this SMS as spam or not spam using the sender and message only. "
        "POLITICAL AUTO-REJECT RULE: If the message has any political focus, always "
        "classify it as spam. This includes candidates, elected officials, political "
        "parties, campaigns, elections, voting or voter registration, political "
        "fundraising, polls or surveys about politics, petitions, ballot measures, "
        "public-policy advocacy, ideological persuasion, rallies, and political "
        "volunteering. Apply this rule even when the message is legitimate, neutral, "
        "informational, nonpartisan, opted-in, or from an official organization. "
        "Do not trigger the political rule merely because a nonpolitical message uses "
        "a word such as campaign, vote, poll, party, candidate, primary, bill, left, "
        "right, red, or blue in an unrelated context. "
        "Also classify scams, phishing, impersonation, unsolicited marketing, loan "
        "offers, cryptocurrency schemes, fake delivery notices, and other unwanted "
        "automated messages as spam. Legitimate nonpolitical transactional messages, "
        "authentication codes, delivery updates, appointments, receipts, government "
        "service notices, and plausible personal conversations are not spam.\n\n"
        f"Sender: {sender}\n"
        f"Message: {body}\n\n"
        "Respond with ONLY a JSON object, no other text:\n"
        '{"spam": true/false, "reason": "brief reason if spam, empty string if not"}'
    )


PROMPTS = {
    "app-v1": app_v1_prompt,
    "cautious-v2": cautious_v2_prompt,
    "political-v3": political_v3_prompt,
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--dataset",
        type=Path,
        default=Path(__file__).with_name("sms_cases.jsonl"),
    )
    parser.add_argument("--models", nargs="+", default=list(DEFAULT_MODELS))
    parser.add_argument("--prompt", choices=sorted(PROMPTS), default="political-v3")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--max-tokens", type=int, default=100)
    parser.add_argument(
        "--reasoning-effort",
        choices=("none", "minimal", "low", "medium", "high", "max"),
    )
    parser.add_argument("--structured-output", action="store_true")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--difficulty", choices=("easy", "hard"))
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def load_cases(path: Path) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    seen: set[str] = set()
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        case = json.loads(raw)
        required = {
            "id",
            "label",
            "category",
            "difficulty",
            "sender",
            "body",
            "source_ids",
            "rationale",
        }
        missing = required - case.keys()
        if missing:
            raise ValueError(f"{path}:{line_number}: missing {sorted(missing)}")
        if case["id"] in seen:
            raise ValueError(f"{path}:{line_number}: duplicate id {case['id']}")
        if case["label"] not in {"spam", "ham"}:
            raise ValueError(f"{path}:{line_number}: invalid label {case['label']}")
        if case["difficulty"] not in {"easy", "hard"}:
            raise ValueError(
                f"{path}:{line_number}: invalid difficulty {case['difficulty']}"
            )
        seen.add(case["id"])
        cases.append(case)
    return cases


def extract_json_object(content: Any) -> dict[str, Any]:
    if not isinstance(content, str):
        raise ValueError(f"Response content is not text: {type(content).__name__}")
    content = content.strip()
    if content.startswith("```"):
        content = content.removeprefix("```json").removeprefix("```")
        content = content.removesuffix("```").strip()
    start = content.find("{")
    end = content.rfind("}")
    if start < 0 or end < start:
        raise ValueError(f"No JSON object in response: {content[:160]!r}")
    parsed = json.loads(content[start : end + 1])
    if not isinstance(parsed.get("spam"), bool):
        raise ValueError(f"Response has no boolean spam field: {parsed!r}")
    return parsed


def call_model(
    api_key: str,
    model: str,
    prompt_name: str,
    case: dict[str, Any],
    timeout: float,
    retries: int,
    max_tokens: int,
    reasoning_effort: str | None,
    structured_output: bool,
) -> dict[str, Any]:
    prompt = PROMPTS[prompt_name](case["sender"], case["body"])
    payload = {
        "model": model,
        "max_tokens": max_tokens,
        "messages": [{"role": "user", "content": prompt}],
    }
    if reasoning_effort:
        payload["reasoning"] = {"effort": reasoning_effort}
    if structured_output:
        payload["response_format"] = {
            "type": "json_schema",
            "json_schema": {
                "name": "spam_classification",
                "strict": True,
                "schema": {
                    "type": "object",
                    "properties": {
                        "spam": {"type": "boolean"},
                        "reason": {"type": "string"},
                    },
                    "required": ["spam", "reason"],
                    "additionalProperties": False,
                },
            },
        }
    encoded = json.dumps(payload).encode("utf-8")
    last_error = "unknown error"
    started = time.perf_counter()

    for attempt in range(retries + 1):
        request = urllib.request.Request(
            API_URL,
            data=encoded,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
                "HTTP-Referer": "https://local.stoptextspam.invalid/benchmark",
                "X-Title": "StopTextSpam benchmark",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                data = json.loads(response.read().decode("utf-8"))
            content = data["choices"][0]["message"]["content"]
            parsed = extract_json_object(content)
            predicted = "spam" if parsed["spam"] else "ham"
            usage = data.get("usage") or {}
            return {
                "case_id": case["id"],
                "expected": case["label"],
                "predicted": predicted,
                "correct": predicted == case["label"],
                "category": case["category"],
                "difficulty": case["difficulty"],
                "requested_model": model,
                "resolved_model": data.get("model"),
                "provider": data.get("provider"),
                "prompt_version": prompt_name,
                "max_tokens": max_tokens,
                "reasoning_effort": reasoning_effort,
                "structured_output": structured_output,
                "reason": str(parsed.get("reason", "")),
                "latency_seconds": round(time.perf_counter() - started, 4),
                "usage": {
                    "prompt_tokens": usage.get("prompt_tokens"),
                    "completion_tokens": usage.get("completion_tokens"),
                    "total_tokens": usage.get("total_tokens"),
                    "cost": usage.get("cost"),
                },
                "error": None,
                "attempts": attempt + 1,
            }
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")[:500]
            last_error = f"HTTP {exc.code}: {body}"
            retryable = exc.code == 429 or 500 <= exc.code < 600
            if not retryable or attempt >= retries:
                break
        except (
            urllib.error.URLError,
            TimeoutError,
            json.JSONDecodeError,
            KeyError,
            TypeError,
            ValueError,
        ) as exc:
            last_error = f"{type(exc).__name__}: {exc}"
            if attempt >= retries:
                break
        time.sleep(0.75 * (2**attempt))

    return {
        "case_id": case["id"],
        "expected": case["label"],
        "predicted": None,
        "correct": False,
        "category": case["category"],
        "difficulty": case["difficulty"],
        "requested_model": model,
        "resolved_model": None,
        "provider": None,
        "prompt_version": prompt_name,
        "max_tokens": max_tokens,
        "reasoning_effort": reasoning_effort,
        "structured_output": structured_output,
        "reason": "",
        "latency_seconds": round(time.perf_counter() - started, 4),
        "usage": {},
        "error": last_error,
        "attempts": retries + 1,
    }


def read_existing(path: Path) -> tuple[list[dict[str, Any]], set[tuple[str, str]]]:
    rows: list[dict[str, Any]] = []
    completed: set[tuple[str, str]] = set()
    if not path.exists():
        return rows, completed
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip():
            continue
        row = json.loads(raw)
        rows.append(row)
        completed.add((row["case_id"], row["requested_model"]))
    return rows, completed


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return math.nan
    ordered = sorted(values)
    index = min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1)
    return ordered[index]


def safe_ratio(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else math.nan


def model_metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    valid = [row for row in rows if row["predicted"] in {"spam", "ham"}]
    tp = sum(row["expected"] == "spam" and row["predicted"] == "spam" for row in valid)
    tn = sum(row["expected"] == "ham" and row["predicted"] == "ham" for row in valid)
    fp = sum(row["expected"] == "ham" and row["predicted"] == "spam" for row in valid)
    fn = sum(row["expected"] == "spam" and row["predicted"] == "ham" for row in valid)
    recall = safe_ratio(tp, tp + fn)
    specificity = safe_ratio(tn, tn + fp)
    precision = safe_ratio(tp, tp + fp)
    f1 = safe_ratio(2 * tp, 2 * tp + fp + fn)
    costs = [row.get("usage", {}).get("cost") for row in valid]
    costs = [float(cost) for cost in costs if cost is not None]
    latencies = [float(row["latency_seconds"]) for row in valid]
    return {
        "total": len(rows),
        "valid": len(valid),
        "errors": len(rows) - len(valid),
        "tp": tp,
        "tn": tn,
        "fp": fp,
        "fn": fn,
        "accuracy": safe_ratio(tp + tn, len(valid)),
        "precision": precision,
        "recall": recall,
        "specificity": specificity,
        "f1": f1,
        "balanced_accuracy": (recall + specificity) / 2,
        "median_latency": statistics.median(latencies) if latencies else math.nan,
        "p95_latency": percentile(latencies, 0.95),
        "reported_cost": sum(costs) if costs else None,
    }


def pct(value: float) -> str:
    return "n/a" if math.isnan(value) else f"{value:.1%}"


def build_summary(
    rows: list[dict[str, Any]], cases_by_id: dict[str, dict[str, Any]], output: Path
) -> str:
    by_model: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_model[row["requested_model"]].append(row)

    lines = [
        "# StopTextSpam benchmark results",
        "",
        f"Generated: {datetime.now(timezone.utc).isoformat()}",
        f"Raw results: `{output.as_posix()}`",
        "",
        "## Overall",
        "",
        "| Requested model | Resolved model(s) | Accuracy | Spam recall | Ham specificity | F1 | FP | FN | Errors | Median latency | Reported cost |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    metrics_by_model: dict[str, dict[str, Any]] = {}
    for model, model_rows in by_model.items():
        metrics = model_metrics(model_rows)
        metrics_by_model[model] = metrics
        resolved = sorted({row["resolved_model"] for row in model_rows if row["resolved_model"]})
        cost = metrics["reported_cost"]
        latency_text = (
            "n/a"
            if math.isnan(metrics["median_latency"])
            else f"{metrics['median_latency']:.2f}s"
        )
        lines.append(
            f"| `{model}` | {', '.join(f'`{item}`' for item in resolved) or 'n/a'} | "
            f"{pct(metrics['accuracy'])} | {pct(metrics['recall'])} | "
            f"{pct(metrics['specificity'])} | {pct(metrics['f1'])} | "
            f"{metrics['fp']} | {metrics['fn']} | {metrics['errors']} | "
            f"{latency_text} | "
            f"{'n/a' if cost is None else f'${cost:.6f}'} |"
        )

    lines.extend(["", "## Hard cases", ""])
    lines.extend(
        [
            "| Model | Accuracy | Spam recall | Ham specificity | FP | FN |",
            "|---|---:|---:|---:|---:|---:|",
        ]
    )
    for model, model_rows in by_model.items():
        metrics = model_metrics([row for row in model_rows if row["difficulty"] == "hard"])
        lines.append(
            f"| `{model}` | {pct(metrics['accuracy'])} | {pct(metrics['recall'])} | "
            f"{pct(metrics['specificity'])} | {metrics['fp']} | {metrics['fn']} |"
        )

    lines.extend(["", "## Accuracy by category", ""])
    categories = sorted({row["category"] for row in rows})
    lines.append("| Model | " + " | ".join(categories) + " |")
    lines.append("|---|" + "---:|" * len(categories))
    for model, model_rows in by_model.items():
        cells = []
        for category in categories:
            category_rows = [row for row in model_rows if row["category"] == category]
            valid = [row for row in category_rows if row["predicted"] is not None]
            cells.append(pct(safe_ratio(sum(row["correct"] for row in valid), len(valid))))
        lines.append(f"| `{model}` | " + " | ".join(cells) + " |")

    lines.extend(["", "## Misclassified cases", ""])
    for model, model_rows in by_model.items():
        lines.extend([f"### `{model}`", ""])
        mistakes = [row for row in model_rows if row["predicted"] and not row["correct"]]
        errors = [row for row in model_rows if row["error"]]
        if errors:
            lines.append(f"API or parse errors: **{len(errors)}**.")
            lines.append("")
        if not mistakes:
            lines.extend(["None.", ""])
            continue
        for row in mistakes:
            case = cases_by_id[row["case_id"]]
            lines.append(
                f"- **{row['case_id']}** ({row['category']}, {row['difficulty']}): "
                f"expected `{row['expected']}`, predicted `{row['predicted']}`. "
                f"Model reason: {row['reason'] or '(none)'}. "
                f"Benchmark rationale: {case['rationale']}"
            )
        lines.append("")

    if len(by_model) > 1:
        lines.extend(["## Pairwise correctness", ""])
        models = list(by_model)
        lookup = {
            model: {row["case_id"]: row for row in model_rows}
            for model, model_rows in by_model.items()
        }
        for index, left in enumerate(models):
            for right in models[index + 1 :]:
                common = sorted(set(lookup[left]) & set(lookup[right]))
                left_only = sum(
                    lookup[left][case_id]["correct"]
                    and not lookup[right][case_id]["correct"]
                    for case_id in common
                )
                right_only = sum(
                    lookup[right][case_id]["correct"]
                    and not lookup[left][case_id]["correct"]
                    for case_id in common
                )
                lines.append(
                    f"- `{left}` alone correct on **{left_only}** cases; "
                    f"`{right}` alone correct on **{right_only}** cases."
                )
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    args = parse_args()
    api_key = os.environ.get("OPENROUTER_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("OPENROUTER_API_KEY is not set")
    if args.workers < 1:
        raise SystemExit("--workers must be at least 1")
    if args.output.exists() and not args.resume and not args.overwrite:
        raise SystemExit(
            f"{args.output} already exists; use --resume or --overwrite intentionally"
        )
    if args.overwrite and args.output.exists():
        args.output.unlink()

    cases = load_cases(args.dataset)
    if args.difficulty:
        cases = [case for case in cases if case["difficulty"] == args.difficulty]
    if args.limit is not None:
        cases = cases[: args.limit]
    if not cases:
        raise SystemExit("No benchmark cases selected")

    existing_rows, completed = read_existing(args.output) if args.resume else ([], set())
    jobs = [
        (model, case)
        for model in args.models
        for case in cases
        if (case["id"], model) not in completed
    ]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    write_lock = threading.Lock()
    finished = 0
    print(
        f"Running {len(jobs)} requests across {len(args.models)} model(s) "
        f"and {len(cases)} case(s) with prompt {args.prompt}"
    )

    with args.output.open("a", encoding="utf-8", buffering=1) as output_handle:
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
            future_map = {
                executor.submit(
                    call_model,
                    api_key,
                    model,
                    args.prompt,
                    case,
                    args.timeout,
                    args.retries,
                    args.max_tokens,
                    args.reasoning_effort,
                    args.structured_output,
                ): (model, case["id"])
                for model, case in jobs
            }
            for future in concurrent.futures.as_completed(future_map):
                model, case_id = future_map[future]
                row = future.result()
                with write_lock:
                    output_handle.write(json.dumps(row, ensure_ascii=False) + "\n")
                    output_handle.flush()
                    existing_rows.append(row)
                    finished += 1
                    status = "ok" if not row["error"] else "error"
                    print(f"[{finished}/{len(jobs)}] {model} {case_id}: {status}")

    cases_by_id = {case["id"]: case for case in load_cases(args.dataset)}
    summary_path = args.summary or args.output.with_suffix(".md")
    summary_path.write_text(
        build_summary(existing_rows, cases_by_id, args.output), encoding="utf-8"
    )
    print(f"Raw results: {args.output}")
    print(f"Summary: {summary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
