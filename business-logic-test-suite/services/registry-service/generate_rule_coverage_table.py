"""
Parses rule IDs from BUSINESS_RULES.md headings and cross-references them
against test class names in the tests/ directory.

Writes reports/rule_coverage_table.md.
Run: python generate_rule_coverage_table.py

Heading format in BUSINESS_RULES.md:
  ### BR-CF-001: Padding length must cover sequence start width
  ### BR-CS-001: Template must exist before generation
  ### BR-LC-001: Updates are append-only
  ### BR-CM-001: IDGen required for bill number generation

Test class naming convention:
  class TestBR_CF_001_padding_length_vs_start
  class TestBR_CS_001_template_must_exist
"""
import os
import re


def collect_rules(rules_path="BUSINESS_RULES.md"):
    category_labels = {
        "CF": "Cross-field",
        "CS": "Cross-schema",
        "LC": "Lifecycle",
        "CM": "Cross-module",
    }
    rules = []
    try:
        with open(rules_path) as f:
            for line in f:
                m = re.match(r'^###\s+(BR-([A-Z]+)-\d+):\s+(.+)', line.strip())
                if m:
                    rule_id  = m.group(1)
                    cat_code = m.group(2)
                    title    = m.group(3).strip()
                    category = category_labels.get(cat_code, cat_code)
                    rules.append((rule_id, title, category))
    except FileNotFoundError:
        print(f"WARNING: {rules_path} not found.")
    return rules


def collect_covered_ids(test_dir="tests"):
    covered = set()
    pattern = re.compile(r'class\s+TestBR_([A-Z]+)_(\d+)_')
    for root, _, files in os.walk(test_dir):
        for fname in files:
            if not fname.startswith("test_") or not fname.endswith(".py"):
                continue
            with open(os.path.join(root, fname)) as f:
                for line in f:
                    for m in pattern.finditer(line):
                        covered.add(f"BR-{m.group(1)}-{m.group(2)}")
    return covered


def generate(rules_path="BUSINESS_RULES.md", test_dir="tests",
             output="reports/rule_coverage_table.md"):
    rules   = collect_rules(rules_path)
    covered = collect_covered_ids(test_dir)

    if not rules:
        print("No rules found in BUSINESS_RULES.md — nothing to report.")
        return

    covered_count = sum(1 for rid, _, _ in rules if rid in covered)
    total_count   = len(rules)

    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w") as f:
        f.write("# Business rule test coverage\n\n")
        f.write(f"**{covered_count} / {total_count} rules covered**\n\n")
        f.write("| Rule ID | Title | Category | Covered |\n")
        f.write("|---------|-------|----------|---------|\n")
        for rule_id, title, category in rules:
            status = "✅" if rule_id in covered else "❌"
            f.write(f"| `{rule_id}` | {title} | {category} | {status} |\n")

    uncovered = [(rid, t) for rid, t, _ in rules if rid not in covered]
    if uncovered:
        print(f"\n⚠️  {len(uncovered)} uncovered rule(s):")
        for rid, title in uncovered:
            print(f"   {rid}  {title}")
    else:
        print(f"✅  All {total_count} rules have test coverage.")
    print(f"\nWritten: {output}")


if __name__ == "__main__":
    generate()
