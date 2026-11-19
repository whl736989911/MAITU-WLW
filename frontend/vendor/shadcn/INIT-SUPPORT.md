# 官方 Nova / init 支持核验

核验日期：2026-10-22。不是把自制 CSS 叫作 Nova。

- 官方公告 https://ui.shadcn.com/docs/changelog/2025-12-shadcn-create 明确列出 Nova：Reduced padding and margins for compact layouts；支持 Vite。
- 官方 https://ui.shadcn.com/schema.json 的 style 枚举明确包含 radix-nova / base-nova / aria-nova。
- 本机实际执行 `npm exec --yes --package shadcn@4.22.1 -- shadcn init --help`，退出码0。输出包含 `--template`（支持vite）、`--base`（支持radix）、`--preset`，及 `--defaults` 的 `--preset=base-nova`。当前CLI不提供旧式 `init --style nova` 参数，因此未伪造该命令。
- 本工程采用官方 Existing Project 手工配置方式：components.json 使用 radix-nova；通过官方公开 registry 下载源码。没有声称对本目录执行过 init（未执行会覆盖工程的初始化）。
- 官方Vite安装文档：https://ui.shadcn.com/docs/installation/vite
- 官方CLI文档：https://ui.shadcn.com/docs/cli

## 组件源码

实际下载 `https://ui.shadcn.com/r/styles/radix-nova/{name}.json`，button / input / badge / card / table / sheet / label / separator。保留原始JSON和provenance.json（URL、上游路径、上游内容/登记文件/本地输出 SHA256）。本地组件由 tools/sync-shadcn.mjs 直接提取，未重新手写组件实现。

仅必要变换：cn import到本地clsx+tailwind-merge工具、内部registry路径到本地别名、官网IconPlaceholder替换Lucide XIcon、Close读屏文案翻译「关闭」。sheet基于官方Radix Dialog，有焦点锁、Esc关闭、标题说明、遮罩。

主题及业务布局是本项目自行编写的CSS（紧凑浅色、深绿导航），不宣称整个平台布局来自官方Nova模板。只选用必要组件，未为了凑库安装dnd-kit。
