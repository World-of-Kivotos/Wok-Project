#!/usr/bin/env python3
"""以 claude[bot] 身份发布 Claude 审查的结论: 推送自动修复、发评论、整理标签、发行内批注。

claude-pr-review 工作流的 publish job 用 claude-code-action 换到 Claude GitHub App 的安装令牌(环境变量 GH_TOKEN),
再让 Claude 原样执行本脚本。脚本与 labels.json 都取自默认分支; 审查材料由 review job 校验后产出。
"""
import base64
import json
import os
import subprocess

# 这些前缀的标签归审查流程维护, 每轮按最新结论增删; size/ 归 pr-checks。
OWNED_PREFIXES = ("type:", "area:", "risk:", "review:", "ci:")


def run(args, input_text=None, env=None):
    return subprocess.run(args, check=True, encoding="utf-8", stdout=subprocess.PIPE,
                          input=input_text, env=env).stdout


def gh(*args, input_text=None):
    return run(["gh", *args], input_text=input_text)


def git(*args, env=None):
    return run(["git", *args], env=env)


def push_fixes(out, repo, head_sha, head_ref):
    bundle = os.path.join(out, "fixes.bundle")
    git("bundle", "verify", bundle)
    git("fetch", bundle, "HEAD")
    fixes = git("rev-parse", "FETCH_HEAD").strip()
    with open(os.path.join(out, "fixes_head"), encoding="utf-8") as f:
        expected = f.read().strip()
    if fixes != expected:
        raise RuntimeError(f"bundle 内容 {fixes} 与审查记录的提交 {expected} 不一致")
    git("merge-base", "--is-ancestor", head_sha, fixes)
    # 令牌走环境变量里的 git 配置, 不进命令行参数。
    credential = base64.b64encode(f"x-access-token:{os.environ['GH_TOKEN']}".encode()).decode()
    env = dict(os.environ, GIT_CONFIG_COUNT="1",
               GIT_CONFIG_KEY_0="http.https://github.com/.extraheader",
               GIT_CONFIG_VALUE_0=f"AUTHORIZATION: basic {credential}")
    # 非强制推送: 审查期间 PR 分支若已被人更新, 这里会被拒绝, 新提交自会触发下一轮审查。
    git("push", f"https://github.com/{repo}.git", f"{fixes}:refs/heads/{head_ref}", env=env)


def label_definitions(policy):
    defs = {}
    for group in ("type", "risk", "review"):
        for entry in policy[group]:
            defs[entry["name"]] = entry
    for key in ("static_only", "ingame"):
        defs[policy[key]["name"]] = policy[key]
    registry = json.loads(git("show", "HEAD:docs/modules/module-registry.json"))
    for module in registry["modules"]:
        name = "area:" + module["id"].removeprefix("wok-")
        defs[name] = {"name": name, "color": policy["area"]["color"],
                      "description": f"模块 {module['id']}({module['name']})"}
    for extra in policy["area"]["extra"]:
        name = "area:" + extra["id"]
        defs[name] = {"name": name, "color": policy["area"]["color"], "description": extra["description"]}
    return defs


def sync_labels(repo, number, policy, wanted):
    defs = label_definitions(policy)
    unknown = [name for name in wanted if name not in defs]
    if unknown:
        raise RuntimeError(f"标签不在默认分支的 labels.json 与模块清单里: {unknown}")
    for name in wanted:
        entry = defs[name]
        gh("label", "create", name, "--repo", repo, "--color", entry["color"],
           "--description", entry["description"], "--force")
    current = gh("pr", "view", str(number), "--repo", repo, "--json", "labels",
                 "--jq", ".labels[].name").splitlines()
    stale = sorted(name for name in current
                   if (name.startswith(OWNED_PREFIXES) or name == policy["ingame"]["name"]) and name not in wanted)
    args = ["pr", "edit", str(number), "--repo", repo, "--add-label", ",".join(wanted)]
    if stale:
        args += ["--remove-label", ",".join(stale)]
    gh(*args)


def post_inline(repo, number, head_sha, comments):
    if not comments:
        return
    payload = [{"path": c["path"], "line": c["line"], "side": "RIGHT", "body": c["body"]} for c in comments]
    review = {"commit_id": head_sha, "event": "COMMENT",
              "body": "行内批注, 完整问题清单见同一轮的审查评论。", "comments": payload}
    try:
        gh("api", "--method", "POST", f"repos/{repo}/pulls/{number}/reviews", "--input", "-",
           input_text=json.dumps(review, ensure_ascii=False))
        return
    except subprocess.CalledProcessError:
        # 整份批注里只要有一条的行不在 diff 里, GitHub 就整份拒收; 退回逐条发, 只丢落不上的那几条。
        print("::warning::行内批注整份提交被拒, 改为逐条发")
    failed = []
    for comment in payload:
        try:
            gh("api", "--method", "POST", f"repos/{repo}/pulls/{number}/comments", "--input", "-",
               input_text=json.dumps(dict(comment, commit_id=head_sha), ensure_ascii=False))
        except subprocess.CalledProcessError:
            failed.append(f"{comment['path']}:{comment['line']}")
    if len(failed) == len(payload):
        raise RuntimeError(f"行内批注全部发送失败: {failed}")
    for location in failed:
        # 问题本身已列在审查评论的表里, 落不上行只损失定位。
        print(f"::warning::行内批注没能落到 {location}(不在本 PR 的 diff 里)")


def main():
    out = os.environ["REVIEW_OUT"]
    repo = os.environ["GITHUB_REPOSITORY"]
    number = os.environ["PR_NUMBER"]
    head_sha = os.environ["HEAD_SHA"]
    with open(os.path.join(out, "route"), encoding="utf-8") as f:
        route = f.read().strip()
    with open(os.path.join(out, "result.json"), encoding="utf-8") as f:
        result = json.load(f)
    with open(os.environ["LABELS_FILE"], encoding="utf-8") as f:
        policy = json.load(f)

    if route == "B":
        push_fixes(out, repo, head_sha, os.environ["HEAD_REF"])
    gh("pr", "comment", number, "--repo", repo, "--body-file", os.path.join(out, "comment.md"))
    sync_labels(repo, number, policy, result["labels"])
    post_inline(repo, number, head_sha, result["inline_comments"])

    with open(os.environ["DONE_MARKER"], "w", encoding="utf-8") as f:
        f.write("ok\n")
    print("发布完成")


if __name__ == "__main__":
    main()
