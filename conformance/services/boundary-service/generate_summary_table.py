"""
Auto-generates reports/test_summary_table.md from schema.yaml.
Run: python generate_summary_table.py
"""
import os
import yaml

AUTH_ERRORS = [
    ("Missing auth token",  "401", "`error`, `message`", "Layer 3"),
    ("Invalid auth token",  "401", "`error`, `message`", "Layer 3"),
]
MUTATION_ERRORS = [
    ("Missing required fields", "400", "`error`, `message`", "Layer 3"),
    ("Wrong field types",        "400", "`error`, `message`", "Layer 3"),
    ("Invalid enum value",       "400", "`error`, `message`", "Layer 3"),
]
PATH_PARAM_ERROR = ("Resource not found", "404", "`error`, `message`", "Layer 3")


def layer_for_status(status):
    return "Layer 2" if int(status) < 300 else "Layer 3"


def rows_for_operation(method, path, operation):
    rows = []
    for status, resp_obj in sorted(operation.get("responses", {}).items()):
        code = str(status)
        desc = resp_obj.get("description", "")
        if code.startswith("2"):
            rows.append((f"`{method.upper()}`", path, code, desc or "Success",
                         "—", "—", layer_for_status(code)))
        else:
            content = resp_obj.get("content", {})
            schema_fields = "—"
            if content:
                schema = next(iter(content.values()), {}).get("schema", {})
                required = schema.get("required", [])
                if required:
                    schema_fields = ", ".join(f"`{f}`" for f in required)
            rows.append((f"`{method.upper()}`", path, "—", desc or f"Error {code}",
                         f"`{code}`", schema_fields, layer_for_status(code)))

    if "{" in path and not any(r[4] == "`404`" for r in rows):
        rows.append((f"`{method.upper()}`", path, "—", *PATH_PARAM_ERROR))

    if method.upper() in ("POST", "PUT", "PATCH"):
        for err in MUTATION_ERRORS:
            if not any(r[3] == err[0] for r in rows):
                rows.append((f"`{method.upper()}`", path, "—", *err))

    if operation.get("security"):
        for err in AUTH_ERRORS:
            if not any(r[3] == err[0] for r in rows):
                rows.append((f"`{method.upper()}`", path, "—", *err))

    return rows


def generate_table(schema_path="schema.yaml",
                   output_path="reports/test_summary_table.md"):
    with open(schema_path) as f:
        spec = yaml.safe_load(f)

    headers = ["Method", "Endpoint", "Happy path status", "Error scenario",
               "Error status", "Error schema fields", "Test layer"]
    sep = [":------", ":-------", ":----------------:", ":-------------",
           ":-----------:", ":------------------", ":----------:"]

    all_rows = []
    for path, path_item in spec.get("paths", {}).items():
        for method, operation in path_item.items():
            if method in ("get", "post", "put", "patch", "delete", "head", "options"):
                all_rows.extend(rows_for_operation(method, path, operation))

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w") as f:
        f.write("# API request vs. error summary\n\n")
        f.write("| " + " | ".join(headers) + " |\n")
        f.write("| " + " | ".join(sep) + " |\n")
        for row in all_rows:
            f.write("| " + " | ".join(str(c) for c in row) + " |\n")

    print(f"Written: {output_path} ({len(all_rows)} rows)")


if __name__ == "__main__":
    generate_table()
