# StopTextSpam benchmark

The two JSONL datasets contain synthetic SMS examples and labels. `sources.json` lists references used to design the scenarios. The data is intended to test classification behavior; it is not a collection of private messages. Numeric senders use fictional 555 numbers. Example links use reserved `.example` domains; official-domain controls point to generic pages without tracking parameters. `sms_political_cases.jsonl` stresses the app's rule that politically focused messages count as spam.

The runner uses Python's standard library. It sends each selected sender and message body to OpenRouter and writes model output, request identifiers, latency, and usage metadata to a JSONL file plus a Markdown summary. Keep results private unless you review them before publication. The `results/` directory is ignored by Git.

From the repository root in PowerShell, provide your own API key for the current session and try a small run:

```powershell
$env:OPENROUTER_API_KEY = '<your key>'
uv run benchmark/run_benchmark.py --prompt political-v3 --models '~deepseek/deepseek-v4-flash-latest' --limit 5 --output benchmark/results/trial.jsonl
Remove-Item Env:OPENROUTER_API_KEY
```

Run `uv run benchmark/run_benchmark.py --help` for dataset, model, concurrency, and output options. A full run makes many paid API requests.
