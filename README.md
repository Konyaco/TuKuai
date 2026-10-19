# 土块棋牌室 TuKuai

在 Minecraft 里摆一张牌桌，和朋友或电脑一起打斗地主。以后还会加入更多棋牌游戏。

支持 Minecraft 26.1.2，NeoForge 和 Fabric 都能用。

## 能做什么

- **斗地主**：三种规则可选，分别是「经典叫分」「抢地主」和「不洗牌」（炸弹更多）。
- **多人加电脑**：几个人坐一桌，空位可以让电脑补上。电脑有简单、普通、困难三档，困难会先在心里把牌局推演很多遍再出牌。
- **在世界里看得见**：牌桌上摆着每个人的手牌（背面朝上）、刚出的牌和底牌，周围的人都能看到。
- **两种视角**：「牌桌」视角把牌桌铺满屏幕；「沉浸」视角不挡住世界，抬头就是真实的牌桌。按 Tab 切换。
- **回放**：每局打完自动存档。可以一步步重看，能看到所有人的牌，也能换成任何一家的视角。
- **外观**：用染料右键牌桌换桌布颜色，界面右上角换牌背。
- **掉线不卡局**：有人走开或掉线，电脑先替他打；连续超时两次会自动托管，回来点「取消托管」就能接着打。

只用游戏内积分，不涉及真实金钱。

## 怎么玩

1. 合成一张牌桌：第一排放 3 块任意颜色的地毯，第二排放 3 块任意木板，第三排左右两格各放 1 根木棍。创造模式在「功能方块」栏里。
2. 把牌桌放下，右键打开。点「坐这里」入座，空位点「加电脑」，或者直接点「空位都补电脑」。
3. 选好规则和出牌时限，点「开始」。

出牌时：

| 操作 | 作用 |
| --- | --- |
| 左键点牌 | 选中或取消这张牌 |
| 右键 | 清空选择 |
| 空格 / 回车 | 出选中的牌；没选牌时就是「不出」 |
| H | 提示，多按几次换一种出法 |
| Tab | 切换牌桌 / 沉浸视角 |

回放：

- 一局结束后，结算框里点「看回放」。
- `/tukuai replays` 列出最近的对局，点名字就能打开；`/tukuai replay <名字>` 按名字打开。
- 回放里空格暂停或继续，左右方向键前后一步，Home / End 跳到开头或结尾，Tab 换视角。

回放文件存在存档目录的 `tukuai/replays` 里。

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
| `engine` | 规则、电脑、回放等纯 Kotlin 代码，不依赖 Minecraft，可以单独跑单元测试 |
| `common` | 两个加载器共用的 Minecraft 代码和资源（方块、网络、界面、渲染、贴图、语言文件） |
| `fabric`、`neoforge` | 各加载器的入口、注册和事件转接，越薄越好 |

`engine` 和 `common` 的源码由两个加载器各自编译进自己的 jar，所以每个加载器只发布一个 jar。
`engine` 里不能引用任何 `net.minecraft` 的类；Mixin 以后放在 `common/src/main/java`，用 Java 写。

### 加一个新游戏

1. 在 `engine` 里实现 `GameDefinition`（规则、发牌、出招、每个座位能看到什么、电脑），在 `TuKuai.init()` 里注册到 `GameRegistry`。
2. 在 `common/.../client` 里实现 `GameUi`（界面怎么画、桌面上摆什么牌），在 `TuKuaiClient.init()` 里注册到 `GameUis`。
3. 在语言文件里加上 `tukuai.game.<id>` 和各个规则的名字。

牌桌、座位、电脑代打、计时、同步、存档和回放都是通用的，不用再写一遍。

### 测试

- `./gradlew :engine:test` 跑规则和电脑的测试，包括「困难电脑在同样的牌局里比普通电脑赢得多」。
- `./gradlew :fabric:runClientGameTest` 会真的打开游戏打一局：坐下、加电脑、托管打完、看回放，每一步截图。CI 上用 xvfb 跑，截图以 base64 打在日志里。

提交信息统一写成 `[type](scope) Title`，例如 `[feat](engine) Add Dou Dizhu hand types`。

依赖版本都在 `gradle.properties`。`kotlin_version` 不能高于 Kotlin for Forge 和 Fabric Language Kotlin 自带的 Kotlin 版本。

## 许可证

[MIT](LICENSE)
