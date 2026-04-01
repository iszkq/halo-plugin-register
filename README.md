# Halo 邀请码注册插件

这是一个 Halo 2.x 插件项目，用来把站点注册改成“必须输入邀请码才能注册”。

## 功能

- 拦截站点注册请求，未携带或携带无效邀请码时直接拒绝注册
- 在主题前台自动注入“邀请码”输入框，并把邀请码附加到注册请求
- 提供 Console 管理页，支持新增、启用/停用、删除邀请码
- 支持邀请码有效期、使用次数上限、备注
- 支持在插件设置里自定义注册页路径和注册 API 路径
- 支持邀请页自定义品牌名和logo

## 目录

- `src/main/java`：插件后端代码
- `src/main/resources/plugin.yaml`：插件清单
- `src/main/resources/settings.yaml`：插件设置表单
- `ui`：Halo Console 管理页

## 构建

前提：

- Java 21
- Gradle 8+
- Node.js 20+（或由 Gradle Node 插件自动下载）
- pnpm

构建命令：

```bash
gradle build
```

## 上传到 GitHub 打包

仓库里已经包含 GitHub Actions 工作流：`.github/workflows/build.yml`。

你只需要：

```bash
git init
git add .
git commit -m "feat: init halo invite register plugin"
git branch -M main
git remote add origin 你的仓库地址
git push -u origin main
```

推送后 GitHub 会自动执行打包流程：

- 使用 Java 21
- 使用 Gradle 8.14
- 执行 `gradle clean build --stacktrace`
- 产物上传到 Actions 的 Artifacts

打包完成后，可在 GitHub 仓库：

- `Actions` -> 对应工作流 -> `Artifacts`

下载生成的插件 Jar。

如果你后面给仓库打 Tag，也可以再继续加“自动创建 Release 并上传 Jar”。

## 安装后建议配置

1. 打开 Halo Console -> 插件 -> 邀请码注册
2. 先在“设置”里确认注册页面路径和注册 API 路径
3. 再到插件管理页里创建邀请码
4. 用浏览器无痕模式测试注册

## 重要说明

不同主题、不同 Halo 小版本，注册页面路由或注册 API 可能略有区别，所以这些都做成了可配置项。  
如果你的主题注册页不是 `/register` 或 `/uc/register`，改成你自己的路径即可。
