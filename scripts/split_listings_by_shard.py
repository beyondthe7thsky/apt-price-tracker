#!/usr/bin/env python3
import argparse
import json
import re
from collections import defaultdict
from pathlib import Path


def region_group_by_index(index: int) -> int:
    if 0 <= index <= 6:
        return 1
    if 7 <= index <= 12:
        return 2
    if 13 <= index <= 21:
        return 3
    if 22 <= index <= 29:
        return 4
    if 30 <= index <= 37:
        return 5
    if 38 <= index <= 69:
        return 6
    if 70 <= index <= 113:
        return 7
    return 8


def load_region_order(bot_runner_path: Path) -> list[str]:
    source = bot_runner_path.read_text(encoding="utf-8")
    names = re.findall(r'mapOf\("name"\s+to\s+"([^"]+)"', source)
    if not names:
        raise SystemExit(f"No region names found in {bot_runner_path}")
    return names


def split_listings(input_path: Path, output_dir: Path, bot_runner_path: Path) -> int:
    region_names = load_region_order(bot_runner_path)
    region_to_group = {
        name: region_group_by_index(idx)
        for idx, name in enumerate(region_names)
    }

    payload = json.loads(input_path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise SystemExit(f"Input must be JSON array: {input_path}")

    shards: dict[int, list[dict]] = defaultdict(list)
    unmapped: list[dict] = []

    for item in payload:
        if not isinstance(item, dict):
            continue
        region_name = str(item.get("regionName", "")).strip()
        group = region_to_group.get(region_name)
        if group is None:
            unmapped.append(item)
            continue
        shards[group].append(item)

    if unmapped:
        sample = sorted({str(x.get("regionName", "")).strip() for x in unmapped})[:20]
        raise SystemExit(
            "Found unmapped regionName values. "
            f"count={len(unmapped)}, sample={sample}. "
            "Keep apt-listings.json as fallback or update region mapping first."
        )

    output_dir.mkdir(parents=True, exist_ok=True)
    for shard in range(1, 9):
        out_path = output_dir / f"apt-listings-s{shard}.json"
        out_path.write_text(
            json.dumps(shards.get(shard, []), ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )

    total = sum(len(v) for v in shards.values())
    print(f"split complete: total={total}, input={len(payload)}")
    for shard in range(1, 9):
        print(f"  s{shard}: {len(shards.get(shard, []))}")
    return total


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Split data/apt-listings.json into shard files using BotRunner region-group rules"
    )
    parser.add_argument(
        "--input",
        default="data/apt-listings.json",
        help="Input listings JSON path",
    )
    parser.add_argument(
        "--output-dir",
        default="data",
        help="Output directory for apt-listings-s1..s8.json",
    )
    parser.add_argument(
        "--bot-runner",
        default="src/main/kotlin/me/aptprice/util/BotRunner.kt",
        help="BotRunner.kt path to extract region order",
    )
    args = parser.parse_args()

    input_path = Path(args.input)
    output_dir = Path(args.output_dir)
    bot_runner_path = Path(args.bot_runner)

    if not input_path.exists():
        raise SystemExit(f"Input not found: {input_path}")
    if not bot_runner_path.exists():
        raise SystemExit(f"BotRunner not found: {bot_runner_path}")

    split_listings(input_path, output_dir, bot_runner_path)


if __name__ == "__main__":
    main()
