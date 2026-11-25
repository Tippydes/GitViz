# 0001 · 使用 JDK 17 编译，JavaFX 22.0.2 作为界面库

- 状态：已接受（2026-11-23）
- 相关：#1（项目骨架）、#18（设置页"跟随系统"主题）

## 背景

- 课程要求用 Java GUI 框架实现界面。组员的本机与示范环境以 JDK 17 为主。
- #18 的"跟随系统"主题需要读取系统的深浅色设置。JavaFX 提供这个能力的入口是 `Platform.getPreferences().getColorScheme()`，类型 `javafx.application.ColorScheme`。
- 核对过：`javafx-graphics` 21.0.13 不含 `ColorScheme`，22.0.2 含有。
- 核对过：JavaFX 22.0.2 的所有类文件都是 JDK 17 目标（class 版本 61），可以在 JDK 17 上运行。

## 决定

- 编译目标：`maven.compiler.release=17`。
- 界面库：JavaFX 22.0.2。

## 后果

- 不需要为了深浅色主题升级 JDK。
- #18 直接使用 `Platform.getPreferences()`，不需要绕过 JavaFX 的替代方案。
- 合并前 #1 的代码使用的是 21.0.13，升级到 22.0.2 时，界面、测试和截图都重新验证过。
- 升级 JavaFX 的主版本时，要先再次核对类文件目标版本。JavaFX 更高的版本可能要求更新的 JDK，届时需要新的 ADR。

## 何时重新审视

- 组员普遍切换到 JDK 21 或更高，并且需要 JavaFX 新版本的其它能力时。
- 课程或教师指定了 JDK 版本时。
