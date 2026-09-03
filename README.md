![](https://wiki.ptms.ink/images/6/69/Taboolib-png-blue-v2.png)

## TabooLib Framework

[![](https://app.codacy.com/project/badge/Grade/3e9c747cd4aa484ab7cd74b7666c4c43)](https://www.codacy.com/gh/TabooLib/TabooLib/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=TabooLib/TabooLib&amp;utm_campaign=Badge_Grade)
[![](https://www.codefactor.io/repository/github/taboolib/taboolib/badge)](https://www.codefactor.io/repository/github/taboolib/taboolib)
![](https://img.shields.io/github/contributors/taboolib/taboolib)
![](https://img.shields.io/github/languages/code-size/taboolib/taboolib)

TabooLib 正式创建于 2018/02/06, 为 Minecraft（Java 版）提供一个跨平台的插件开发框架，旨在替代频繁的操作，以及解决一些令人头疼的问题。

+ 基于 `Kotlin` 独特的语法。
+ 仅占 `30+ KB` 插件体积。
+ 魔术般的工具。

## 版本信息

| 构建版本                                                                                                                                                               | 发行时间                                                                                                                                                              | 发行者                                                                                                                                                                   | 插件版本                                                                                                                                                                            |
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ![](https://img.shields.io/badge/dynamic/json?label=Version&query=%24.tag_name&url=https%3A%2F%2Fapi.github.com%2Frepos%2FTabooLib%2FTabooLib%2Freleases%2Flatest) | ![](https://img.shields.io/badge/dynamic/json?label=Date&query=%24.created_at&url=https%3A%2F%2Fapi.github.com%2Frepos%2FTabooLib%2FTabooLib%2Freleases%2Flatest) | ![](https://img.shields.io/badge/dynamic/json?label=Author&query=%24.author.login&url=https%3A%2F%2Fapi.github.com%2Frepos%2FTabooLib%2FTabooLib%2Freleases%2Flatest) | ![](https://img.shields.io/badge/dynamic/json?label=Plugin&query=%24.tag_name&url=https%3A%2F%2Fapi.github.com%2Frepos%2FTabooLib%2Ftaboolib-gradle-plugin%2Freleases%2Flatest) |

## 配套插件

可下载由 [kirraObj](https://github.com/CziSKY) 主导开发的 [IntelliJ IDEA 插件](https://github.com/TabooLib/taboolib-development)，快速创建项目。

![](.assets/idea-plugin.png)

## 构件仓库

本 fork 的构件发布于自有 Maven 仓库 `maven.wcpe.top`，坐标组为 `io.izzel.taboolib`，版本号遵循 `6.3.0-<短哈希>`（每个提交一版，最新版本见 [Releases](https://github.com/wcpe/taboolib/releases/latest)）。

```kotlin
taboolib {
    env {
        install(Basic)
        repoTabooLib = "https://maven.wcpe.top/repository/maven-public/"
    }
    version { taboolib = "<版本号>" }
}
```

## 相关链接

+ [TabooLib 版本迁移文档 (6.1 -> 6.2)](https://docs.tabooproject.org/migration/migration-6-1-to-6-2)
+ [TabooLib 主页](https://tabooproject.org)
+ [TabooLib 文档](https://docs.tabooproject.org)
+ [TabooLib 构件仓库](https://repo.tabooproject.org)
+ [TabooLib 非官方文档 (枫溪文档)](https://taboolib.feishu.cn/wiki/Lzf8wFEsfiHclskCuGkctoUNn9b)

## 使用统计

![](https://bstats.org/signatures/bukkit/taboolib-6.svg)