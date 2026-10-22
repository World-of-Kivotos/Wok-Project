#!/usr/bin/env python3
"""PR 规范检查: main 只收 dev 的发布 PR; 其余 PR 按生产代码行数打 size 标签并拦截超限, 再按模板逐段核对描述。

pr-checks 工作流从 base 分支检出后执行本脚本, PR 改不了它对自己的判定; 只读 PR 元数据, 不执行 PR 里的代码。
"""
import json
import os
import re
import subprocess
import sys

POLICY_PATH = ".github/labels.json"
RELEASE_BASE = "main"
RELEASE_HEAD = "dev"
REQUIRED_SECTIONS = ["变更内容", "关联", "风险面", "验证", "实机验收", "待拍板"]
SPLIT_SECTION = "拆分说明"
RISK_NONE = "无"
GAMETEST_LINE = re.compile(r"All \d+ required tests passed")


def gh(*args):
    return subprocess.run(["gh", *args], check=True, encoding="utf-8", stdout=subprocess.PIPE).stdout


def is_production_code(path):
    name = path.rsplit("/", 1)[-1]
    if path.startswith("src/main/java/") or re.match(r"standalone/[^/]+/src/main/java/", path):
        # GameTest 与 testutil 脚手架和生产代码同在 src/main/java, 按命名剔除。
        return name.endswith(".java") and "GameTest" not in name and "/testutil/" not in path
    if path.startswith("webui/src/") and not path.startswith("webui/src/mock/"):
        return name.endswith((".ts", ".tsx", ".js", ".jsx", ".css"))
    return False


def list_files(repo, number, expected_count):
    out = gh("api", "--paginate", f"repos/{repo}/pulls/{number}/files",
             "--jq", ".[] | [.filename, .additions, .deletions, .status] | @tsv")
    files = []
    for line in out.splitlines():
        path, additions, deletions, status = line.split("\t")
        # 整个删掉的文件不用逐行审, 只按增删算会把清理类 PR 误判成超大。
        changed = int(additions) + (0 if status == "removed" else int(deletions))
        files.append((path, changed))
    # 文件接口最多返回 3000 个文件, 少了就是清单被截断, 行数统计不可信。
    if len(files) != expected_count:
        raise RuntimeError(f"PR 文件清单不完整: 接口返回 {len(files)} 个, PR 记录为 {expected_count} 个")
    return files


def pick_size_label(policy, lines):
    for entry in policy["size"]:
        if entry["below"] is None or lines < entry["below"]:
            return entry
    raise RuntimeError(f"labels.json 的 size 分档没有兜底档(below 为 null), 行数 {lines} 无处可放")


def xl_threshold(policy):
    bounded = [entry["below"] for entry in policy["size"] if entry["below"] is not None]
    return max(bounded)


def apply_size_label(repo, number, policy, chosen, current_labels):
    gh("label", "create", chosen["name"], "--repo", repo, "--color", chosen["color"],
       "--description", chosen["description"], "--force")
    stale = [entry["name"] for entry in policy["size"]
             if entry["name"] != chosen["name"] and entry["name"] in current_labels]
    args = ["pr", "edit", str(number), "--repo", repo, "--add-label", chosen["name"]]
    if stale:
        args += ["--remove-label", ",".join(stale)]
    gh(*args)


def parse_sections(body):
    text = re.sub(r"<!--.*?-->", "", body or "", flags=re.S)
    sections = {}
    current = None
    for line in text.splitlines():
        heading = re.match(r"^##\s+(.+?)\s*$", line)
        if heading:
            current = heading.group(1)
            sections[current] = []
        elif current is not None:
            sections[current].append(line)
    return {name: "\n".join(lines).strip() for name, lines in sections.items()}


def check_form(sections, touches_src, is_xl):
    errors = []
    for name in REQUIRED_SECTIONS:
        if name not in sections:
            errors.append(f"PR 描述缺少「## {name}」段, 请按模板填写")
        elif not sections[name]:
            errors.append(f"「## {name}」段是空的")
    risk = sections.get("风险面", "")
    checked = re.findall(r"^\s*[-*]\s*\[[xX]\]\s*(.+?)\s*$", risk, flags=re.M)
    if "风险面" in sections:
        if not checked:
            errors.append("「## 风险面」一项都没勾; 都不涉及请勾「无」")
        elif RISK_NONE in checked and len(checked) > 1:
            errors.append("「## 风险面」勾了「无」又勾了其他项, 二者矛盾")
    if touches_src and "验证" in sections and not GAMETEST_LINE.search(sections["验证"]):
        errors.append("改动了 src/, 但「## 验证」里没有 run/logs/latest.log 的「All N required tests passed」原文")
    if is_xl and not sections.get(SPLIT_SECTION):
        errors.append(f"生产代码达到 XL 档, 「## {SPLIT_SECTION}」必填")
    return errors


def write_summary(lines):
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")


def main():
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as f:
        pr = json.load(f)["pull_request"]
    repo = os.environ["GITHUB_REPOSITORY"]
    number = pr["number"]
    base = pr["base"]["ref"]
    head_ref = pr["head"]["ref"]
    same_repo = pr["head"]["repo"] is not None and pr["head"]["repo"]["full_name"] == repo
    errors = []

    if base == RELEASE_BASE:
        # 发布 PR 只作记录, 由维护者快进合入; 体量与表单在各切片进 dev 时已检查过。
        if not (same_repo and head_ref == RELEASE_HEAD):
            errors.append(f"{RELEASE_BASE} 只接受来自本仓库 {RELEASE_HEAD} 分支的发布 PR; 功能与修复请改投 {RELEASE_HEAD}")
        summary = [f"### PR 规范检查: 发布 PR({head_ref} -> {base})", ""]
        summary += [f"- FAIL {e}" for e in errors] or ["- PASS 来源分支正确"]
        write_summary(summary)
        for e in errors:
            print(f"::error::{e}")
        return 1 if errors else 0

    with open(POLICY_PATH, encoding="utf-8") as f:
        policy = json.load(f)
    files = list_files(repo, number, pr["changed_files"])
    code_files = [(p, c) for p, c in files if is_production_code(p)]
    lines = sum(c for _, c in code_files)
    chosen = pick_size_label(policy, lines)
    threshold = xl_threshold(policy)
    is_xl = chosen["below"] is None
    touches_src = any(p.startswith("src/") for p, _ in files)

    current_labels = {label["name"] for label in pr["labels"]}
    if same_repo:
        apply_size_label(repo, number, policy, chosen, current_labels)
    else:
        print("::notice::fork 来的 PR 拿不到写权限, 不打 size 标签")

    if is_xl:
        errors.append(f"生产代码 {lines} 行, 达到 dev 上限 {threshold} 行; 请按切片拆成堆叠 PR, 确需整体合入由维护者绕过")
    errors += check_form(parse_sections(pr["body"]), touches_src, is_xl)

    summary = [f"### PR 规范检查: {chosen['name']}, 生产代码 {lines} 行(上限 {threshold})", ""]
    summary += [f"- FAIL {e}" for e in errors] or ["- PASS 体量与描述均符合规范"]
    if code_files:
        summary += ["", "| 生产代码文件(前 20) | 计入行数 |", "|---|---|"]
        for path, changed in sorted(code_files, key=lambda x: -x[1])[:20]:
            summary.append(f"| `{path}` | {changed} |")
    write_summary(summary)
    for e in errors:
        print(f"::error::{e}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
