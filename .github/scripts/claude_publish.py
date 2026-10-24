#!/usr/bin/env python3
"""以 claude[bot] 身份发布 Claude 审查的结论: 推送自动修复、发评论、整理标签、发行内批注。

claude-pr-review 工作流的 publish job 用 claude-code-action 换到 Claude GitHub App 的安装令牌(环境变量 GH_TOKEN),
再让 Claude 原样执行本脚本。脚本与 labels.json 都取自默认分支; 审查材料由 review job 校验后产出。

同一份审查产出可能被重跑多次(publish 中途失败后重跑失败的 job): 评论与行内批注末尾带隐藏标记,
发之前先查 claude[bot] 是否已经发过同一份, 发过就跳过; 推送前先看远端是否已经是修复提交。
"""
import base64
import hashlib
import json
import os
import subprocess

# 这些前缀的标签归审查流程维护, 每轮按最新结论增删; size/ 归 pr-checks。
OWNED_PREFIXES = ("type:", "area:", "risk:", "review:", "ci:")
# 查重只认 claude[bot] 自己发的内容: 公开仓库谁都能评论, 别人贴一段同样的标记不能让发布被跳过。
BOT_LOGIN = "claude[bot]"


def log(message):
    # action 不显示 Claude 执行命令的输出, 这里同时写进 PUBLISH_LOG, 由工作流的核对步骤打印出来。
    print(message)
    with open(os.environ["PUBLISH_LOG"], "a", encoding="utf-8") as f:
        f.write(message + "\n")


def run(args, input_text=None, env=None):
    result = subprocess.run(args, encoding="utf-8", stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            input=input_text, env=env)
    if result.returncode != 0:
        # 只记命令前四段与 stderr: 正文走 stdin, 令牌走环境变量, 都不会写进日志。
        log(f"命令失败(退出码 {result.returncode}): {' '.join(args[:4])}\n{result.stderr.strip()}")
        raise subprocess.CalledProcessError(result.returncode, args, result.stdout, result.stderr)
    return result.stdout


def gh(*args, input_text=None):
    return run(["gh", *args], input_text=input_text)


def git(*args, env=None):
    return run(["git", *args], env=env)


def marker(head_sha, digest, part):
    return f"<!-- claude-review head={head_sha} digest={digest} part={part} -->"


def bot_bodies(repo, endpoint):
    out = gh("api", "--paginate", f"repos/{repo}/{endpoint}", "--jq",
             f'.[] | select(.user.login == "{BOT_LOGIN}") | .body | @json')
    return [json.loads(line) for line in out.splitlines() if line]


def already_posted(bodies, mark):
    return any(body and mark in body for body in bodies)


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
    remote = git("ls-remote", f"https://github.com/{repo}.git", f"refs/heads/{head_ref}", env=env).split()
    if remote and remote[0] == fixes:
        log(f"修复提交: 远端 {head_ref} 已是 {fixes[:8]}, 上一次重跑已推送, 跳过")
        return
    # 非强制推送: 审查期间 PR 分支若已被人更新, 这里会被拒绝, 新提交自会触发下一轮审查。
    git("push", f"https://github.com/{repo}.git", f"{fixes}:refs/heads/{head_ref}", env=env)
    log(f"修复提交: 已把 {fixes[:8]} 推到 {head_ref}")


def post_comment(repo, number, body, mark):
    if already_posted(bot_bodies(repo, f"issues/{number}/comments"), mark):
        log("审查评论: 这一份已经发过, 跳过")
        return
    gh("pr", "comment", number, "--repo", repo, "--body-file", "-", input_text=f"{body.rstrip()}\n\n{mark}\n")
    log("审查评论: 已发出")


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
    log(f"标签: 已打 {wanted}, 摘掉 {stale}")


def post_inline(repo, number, head_sha, comments, digest):
    if not comments:
        log("行内批注: 本轮没有")
        return
    review_mark = marker(head_sha, digest, "review")
    if already_posted(bot_bodies(repo, f"pulls/{number}/reviews"), review_mark):
        log("行内批注: 这一份已经整份发过, 跳过")
        return
    payload = [{"path": c["path"], "line": c["line"], "side": "RIGHT", "body": c["body"]} for c in comments]
    review = {"commit_id": head_sha, "event": "COMMENT",
              "body": f"行内批注, 完整问题清单见同一轮的审查评论。\n\n{review_mark}", "comments": payload}
    try:
        gh("api", "--method", "POST", f"repos/{repo}/pulls/{number}/reviews", "--input", "-",
           input_text=json.dumps(review, ensure_ascii=False))
        log(f"行内批注: 整份发出 {len(payload)} 条")
        return
    except subprocess.CalledProcessError:
        # 整份批注里只要有一条的行不在 diff 里, GitHub 就整份拒收; 退回逐条发, 只丢落不上的那几条。
        log("::warning::行内批注整份提交被拒, 改为逐条发")
    posted = bot_bodies(repo, f"pulls/{number}/comments")
    failed = []
    for index, comment in enumerate(payload):
        mark = marker(head_sha, digest, f"inline-{index}")
        if already_posted(posted, mark):
            log(f"行内批注: {comment['path']}:{comment['line']} 已经发过, 跳过")
            continue
        try:
            gh("api", "--method", "POST", f"repos/{repo}/pulls/{number}/comments", "--input", "-",
               input_text=json.dumps(dict(comment, body=f"{comment['body']}\n\n{mark}", commit_id=head_sha),
                                     ensure_ascii=False))
        except subprocess.CalledProcessError:
            failed.append(f"{comment['path']}:{comment['line']}")
    if len(failed) == len(payload):
        raise RuntimeError(f"行内批注全部发送失败: {failed}")
    for location in failed:
        # 问题本身已列在审查评论的表里, 落不上行只损失定位。
        log(f"::warning::行内批注没能落到 {location}(不在本 PR 的 diff 里)")
    log(f"行内批注: 逐条发完, 落不上的 {len(failed)} 条")


def main():
    out = os.environ["REVIEW_OUT"]
    repo = os.environ["GITHUB_REPOSITORY"]
    number = os.environ["PR_NUMBER"]
    head_sha = os.environ["HEAD_SHA"]
    with open(os.path.join(out, "route"), encoding="utf-8") as f:
        route = f.read().strip()
    with open(os.path.join(out, "comment.md"), encoding="utf-8") as f:
        comment = f.read()
    with open(os.path.join(out, "result.json"), encoding="utf-8") as f:
        result_text = f.read()
    result = json.loads(result_text)
    with open(os.environ["LABELS_FILE"], encoding="utf-8") as f:
        policy = json.load(f)
    # 摘要只随审查产出变: 重跑同一份产出得到同一个标记, 重新审过一轮则是新标记, 照常发。
    digest = hashlib.sha256("\0".join([route, comment, result_text]).encode("utf-8")).hexdigest()[:16]
    log(f"发布 PR #{number}, 被审提交 {head_sha[:8]}, 路线 {route}, 产出摘要 {digest}")

    try:
        if route == "B":
            push_fixes(out, repo, head_sha, os.environ["HEAD_REF"])
        post_comment(repo, number, comment, marker(head_sha, digest, "comment"))
        sync_labels(repo, number, policy, result["labels"])
        post_inline(repo, number, head_sha, result["inline_comments"], digest)
    except Exception as error:
        log(f"发布中断: {error!r}")
        raise

    with open(os.environ["DONE_MARKER"], "w", encoding="utf-8") as f:
        f.write("ok\n")
    log("发布完成")


if __name__ == "__main__":
    main()
