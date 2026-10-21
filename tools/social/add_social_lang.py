import io
import os

base = r"D:\Wok\.pr-worktrees\social-tablet\src\main\resources\assets\miningdim\lang"
values = {"zh_cn.json": "打开社交平板", "en_us.json": "Open Social Tablet"}
key = "key.miningdim.social.open"
for file_name, value in values.items():
    path = os.path.join(base, file_name)
    text = io.open(path, encoding="utf-8").read()
    if key in text:
        print(file_name, "exists")
        continue
    index = text.rfind("}")
    if index < 0:
        raise SystemExit("no JSON object close in " + file_name)
    text = text[:index].rstrip() + ',\n  "' + key + '": "' + value + '"\n}\n'
    io.open(path, "w", encoding="utf-8", newline="\n").write(text)
    print(file_name, "updated")
