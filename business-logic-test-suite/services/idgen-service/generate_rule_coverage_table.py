"""
Parses rule IDs from BUSINESS_RULES.md headings and cross-references them
against test class names in the tests/ directory.

Also reads reports/test_results.json (written by conftest.pytest_sessionfinish)
to show live pass/fail outcomes per rule.

Writes reports/rule_coverage_table.md.
Run: python generate_rule_coverage_table.py

Heading format in BUSINESS_RULES.md:
  ### BR-CF-001: Padding length must cover sequence start width

Test class naming convention:
  class TestBR_CF_001_padding_length_vs_start

Columns:
  Rule ID | Title | Category | Tests | Result
  Tests:  ✅ test class exists  ❌ no test written
  Result: ✅ all passed  ⚠️ X/Y passed  ❌ all failed  — not run
"""
import json
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


def load_test_results(results_path="reports/test_results.json"):
    try:
        with open(results_path) as f:
            return json.load(f)
    except FileNotFoundError:
        return None


def result_cell(outcomes):
    """Convert a list of outcomes ('passed'/'failed'/'error') to a display cell."""
    if not outcomes:
        return "—"
    passed = outcomes.count("passed")
    total  = len(outcomes)
    if passed == total:
        return "✅"
    if passed == 0:
        return "❌"
    return f"⚠️ {passed}/{total}"


def generate(rules_path="BUSINESS_RULES.md", test_dir="tests",
             results_path="reports/test_results.json",
             output="reports/rule_coverage_table.md"):
    rules        = collect_rules(rules_path)
    covered      = collect_covered_ids(test_dir)
    test_results = load_test_results(results_path)
    has_results  = test_results is not None

    if not rules:
        print("No rules found in BUSINESS_RULES.md — nothing to report.")
        return

    covered_count = sum(1 for rid, _, _ in rules if rid in covered)
    total_count   = len(rules)

    if has_results:
        passed_rules = sum(
            1 for rid, _, _ in rules
            if rid in test_results and all(o == "passed" for o in test_results[rid])
        )
    else:
        passed_rules = None

    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w") as f:
        f.write("# Business rule test coverage\n\n")
        f.write(f"**{covered_count} / {total_count} rules have test classes**")
        if passed_rules is not None:
            f.write(f" · **{passed_rules} / {total_count} rules fully passing**")
        f.write("\n\n")

        if has_results:
            f.write("| Rule ID | Title | Category | Tests | Result |\n")
            f.write("|---------|-------|----------|-------|--------|\n")
        else:
            f.write("| Rule ID | Title | Category | Tests |\n")
            f.write("|---------|-------|----------|-------|\n")

        for rule_id, title, category in rules:
            has_class = "✅" if rule_id in covered else "❌"
            row = f"| `{rule_id}` | {title} | {category} | {has_class}"
            if has_results:
                outcomes = test_results.get(rule_id, [])
                row += f" | {result_cell(outcomes)}"
            row += " |\n"
            f.write(row)

    uncovered = [(rid, t) for rid, t, _ in rules if rid not in covered]
    if uncovered:
        print(f"\n⚠️  {len(uncovered)} rule(s) with no test class:")
        for rid, title in uncovered:
            print(f"   {rid}  {title}")
    else:
        print(f"✅  All {total_count} rules have test coverage.")

    if has_results:
        failed_rules = [
            rid for rid, _, _ in rules
            if rid in test_results and any(o != "passed" for o in test_results[rid])
        ]
        not_run = [rid for rid, _, _ in rules if rid not in test_results]
        if failed_rules:
            print(f"\n❌  {len(failed_rules)} rule(s) with test failures:")
            for rid in failed_rules:
                outcomes = test_results[rid]
                passed = outcomes.count("passed")
                print(f"   {rid}  ({passed}/{len(outcomes)} passed)")
        if not_run:
            print(f"\n—   {len(not_run)} rule(s) not executed this run")

    print(f"\nWritten: {output}")


if __name__ == "__main__":
    generate()
