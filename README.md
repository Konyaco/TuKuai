# 土块棋牌室 TuKuai

在 Minecraft 里摆一张牌桌，和朋友或电脑一起打斗地主。以后还会加入更多棋牌游戏。

- Minecraft 26.1.2，同时支持 NeoForge 和 Fabric
- 用 Kotlin 编写
- 只使用游戏内积分，不涉及真实金钱

> 目前在做第 1 步：斗地主的规则引擎和电脑玩家已经完成（`engine` 目录），游戏里还只有一个空的牌桌方块。

## 玩家需要安装

| 加载器 | 还需要 |
| --- | --- |
| NeoForge 26.1.2 | [Kotlin for Forge](https://modrinth.com/mod/kotlin-for-forge) 6.4.0 或更新 |
| Fabric | [Fabric API](https://modrinth.com/mod/fabric-api)、[Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) 1.13.13 或更新 |

## 开发

需要 JDK 25。在仓库根目录运行：

```sh
./gradlew build                 # 编译、测试，并打出两个加载器的 jar
./gradlew :engine:test          # 只跑规则引擎的单元测试
./gradlew :fabric:runClient     # 用 Fabric 启动游戏
./gradlew :neoforge:runClient   # 用 NeoForge 启动游戏
```

打好的 jar 在 `fabric/build/libs` 和 `neoforge/build/libs`。

## 代码结构

| 目录 | 放什么 |
| --- | --- |
| `engine` | 规则、电脑、牌谱等纯 Kotlin 代码，不依赖 Minecraft，可以单独跑单元测试 |
| `common` | 两个加载器共用的 Minecraft 代码和资源（方块、网络、界面、渲染、贴图、语言文件） |
| `fabric`、`neoforge` | 各加载器的入口、注册和事件转接，越薄越好 |

`engine` 和 `common` 的源码由两个加载器各自编译进自己的 jar，所以每个加载器只发布一个 jar。
`engine` 里不能引用任何 `net.minecraft` 的类；Mixin 以后放在 `common/src/main/java`，用 Java 写。

提交信息统一写成 `[type](scope) Title`，例如 `[feat](engine) Add Dou Dizhu hand types`。

依赖版本都在 `gradle.properties`。`kotlin_version` 不能高于 Kotlin for Forge 和 Fabric Language Kotlin 自带的 Kotlin 版本。

## 许可证

[MIT](LICENSE)
