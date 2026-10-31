---
name: gpt-image-2
description: Generate, edit, and batch-generate images with the GPT Image 2 API when the user explicitly requests GPT Image 2 or invokes this skill. Use the built-in image generation workflow for ordinary image requests that do not specify this model.
---

# Gpt Image 2

Use the bundled Image API CLI for GPT Image 2 generation and editing:

```sh
python3 "${CODEX_HOME:-$HOME/.codex}/skills/.system/imagegen/scripts/image_gen.py" generate \
  --model gpt-image-2 \
  --prompt-file /path/to/prompt.txt \
  --size 1024x1024 \
  --quality high \
  --output-format png \
  --out output/imagegen/result.png
```

For edits, use the `edit` subcommand with one or more `--image` arguments and a prompt that says what to change and what to preserve. The CLI also supports distinct prompt jobs through `generate-batch` and a JSONL input file. Use one job per distinct asset; use `--n` for multiple variations of the same prompt. Keep batch size and output resolution proportionate to the user's request.

Use the user's requested output path. For project assets without a specified destination, save under `output/imagegen/` with a descriptive, non-colliding name. Check existing targets before generation; never overwrite an asset unless the user explicitly asked for replacement. After generation, inspect the images and report their paths and any material limitations.

The API requires `OPENAI_API_KEY` in the process environment. Never ask the user to paste a key into chat, print it, place it in a prompt or artifact, or include it in a command's visible arguments. Use an already configured environment. Load a project `.env` only when the user has identified it as the credential source, and keep loading and execution in the same shell invocation. The API call sends prompt and any input images to OpenAI; invoke it only for an image request or other explicit authorization. If access fails, report the status and stop retrying until the credential or network configuration changes.

Use `gpt-image-2` explicitly for every request. It supports `low`, `medium`, `high`, and `auto` quality. For fixed sizes, follow the model's constraints: dimensions are multiples of 16, each edge is at most 3840 px, aspect ratio is at most 3:1, and total area is 655,360–8,294,400 pixels. `1024x1024` is a practical draft size; use higher quality or resolution when the requested deliverable needs it.

Do not request a transparent API background: GPT Image 2 does not support it. If transparency is required, explain this constraint and ask before switching to another model or applying chroma-key post-processing. Do not pass `--input-fidelity`; image edits use high input fidelity by default.

Use a dry run to check request parameters and output paths before a costly or large batch. Do not use formatter or unrelated project tooling to validate image outputs. Follow the repository's asset and design conventions when the user is creating project imagery.

The CLI may need outbound network access. If the sandbox blocks the API request, use the normal approval flow for the exact generation command; do not try another model or an unrelated endpoint to work around the restriction.
