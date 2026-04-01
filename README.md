# Halo 邀请码注册插件

这是一个适用于 Halo 2.x 的邀请码注册插件，用来将站点注册流程改为“先校验邀请码，再继续注册”。

## 功能特性

- 拦截注册页面访问，在正式注册前先展示邀请码校验页。
- 拦截注册提交请求，未通过邀请码校验时不允许继续注册。
- 提供 Console 管理界面，用于维护邀请码。
- 支持邀请码有效期、使用次数上限等基础控制能力。
- 支持自定义邀请码输入框文案、无效提示文案。
- 支持配置注册页面路径和注册 API 路径，适配不同主题或接入方式。
- 支持注册页品牌配置，可跟随站点设置，也可使用自定义品牌。
- 支持配置站长联系方式文字和图片，在注册页中弹窗展示。

## 品牌展示规则

插件支持两种品牌来源：

- 跟随站点设置
  会优先读取站点标题和站点 Logo / Favicon；如果读取不到完整信息，则回退到默认 `Halo` 标识。
- 使用自定义品牌
  - 设置了自定义 `Logo` 时，页面顶部只显示 `Logo`。
  - 没有设置自定义 `Logo` 时，页面会显示“品牌名称首字符的 fallback 标识 + 完整品牌名称”。
  - 如果品牌名称也留空，则回退为默认 `Halo`。

## 安装后建议配置

1. 打开 Halo Console，进入插件设置页。
2. 确认是否启用邀请码注册。
3. 根据站点实际情况检查注册页面路径和注册 API 路径。
4. 按需设置邀请码输入框标题、占位提示、帮助文案和错误提示。
5. 如果希望注册页带有品牌效果，配置品牌来源、自定义品牌名称和 Logo。
6. 如果希望用户可以联系站长，补充联系方式文字或二维码图片。
7. 在插件管理页创建邀请码后，用浏览器无痕模式测试注册流程。

## 配置说明

常用配置项包括：

- `enabled`
  是否启用邀请码注册。
- `inviteFieldName`
  注册请求中邀请码字段的名称，默认是 `inviteCode`。
- `inputLabel`
  邀请码输入框标题。
- `inputPlaceholder`
  邀请码输入框占位提示。
- `inputHelpText`
  输入框下方帮助文案。
- `invalidMessage`
  邀请码无效、过期或不可用时的提示文案。
- `brandSource`
  品牌来源，可选“跟随站点设置”或“使用自定义品牌”。
- `brandName`
  自定义品牌名称。
- `brandLogo`
  自定义品牌 Logo，可上传、从附件库选择或直接填写链接。
- `contactInfo`
  站长联系方式文字说明。
- `contactImage`
  站长联系方式图片，例如二维码。
- `registrationPagePatterns`
  注册页面路径，支持逐行配置和 Ant 风格匹配。
- `registrationApiPatterns`
  注册提交 API 路径，支持逐行配置和 Ant 风格匹配。

## 开发环境

- Java 21
- Gradle 8+
- Node.js 和 pnpm 可由 Gradle Node 插件自动下载

## 构建

在项目根目录执行：

```bash
gradle build
```

如果只需要构建前端，可在 `ui` 目录中执行：

```bash
pnpm build
```

## 项目结构

- `src/main/java`
  插件后端代码。
- `src/main/resources/plugin.yaml`
  插件声明文件。
- `src/main/resources/settings.yaml`
  插件设置表单定义。
- `src/main/resources/extensions/invite-register-settings.yaml`
  插件扩展设置定义。
- `ui`
  Halo Console 管理界面代码。

## 兼容性

- Halo `>= 2.22.0`

## 注意事项

- 不同主题、不同 Halo 小版本下，注册页面路径或注册 API 路径可能存在差异，建议安装后先检查配置。
- 如果你的注册入口不是 `/register`、`/uc/register` 或 `/signup`，请按实际路径修改配置。
- 如果前端页面看起来没有变化，记得重新构建并重新安装插件包，或重启对应环境后再验证。
